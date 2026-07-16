// SPDX-License-Identifier: GPL-3.0-or-later
package app.caster.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.UUID

/**
 * Foreground service hosting [LocalHttpServer] while a local file is being
 * cast, so Android doesn't kill the process mid-stream.
 */
class LocalHttpServerService : Service() {

    private var server: LocalHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = intent?.data
        val mimeType = intent?.getStringExtra(EXTRA_MIME_TYPE) ?: "video/mp4"
        val token = intent?.getStringExtra(EXTRA_TOKEN)

        if (intent?.action == ACTION_STOP || uri == null || token == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        server?.stop()
        server = LocalHttpServer(applicationContext, uri, mimeType, token, LocalHttpServer.PORT).also {
            it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.server_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, LocalHttpServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.server_notification_title))
            .setContentText(getString(R.string.server_notification_text))
            .addAction(0, getString(R.string.server_notification_stop), stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "local_server"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "app.caster.video.STOP_SERVER"
        const val EXTRA_MIME_TYPE = "mime_type"
        const val EXTRA_TOKEN = "token"

        /** Starts the server for [uri] and returns the path token for building the URL. */
        fun start(context: Context, uri: Uri, mimeType: String): String {
            val token = UUID.randomUUID().toString()
            val intent = Intent(context, LocalHttpServerService::class.java)
                .setData(uri)
                .putExtra(EXTRA_MIME_TYPE, mimeType)
                .putExtra(EXTRA_TOKEN, token)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return token
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocalHttpServerService::class.java))
        }
    }
}
