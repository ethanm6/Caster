package app.caster.video

import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.material.button.MaterialButton
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * TV-remote style controller for the active cast session. Also the target of
 * the cast notification (class name referenced in CastOptionsProvider).
 */
class ExpandedControlsActivity : AppCompatActivity() {

    private lateinit var castContext: CastContext
    private var remoteMediaClient: RemoteMediaClient? = null
    private var userSeeking = false

    private lateinit var deviceName: TextView
    private lateinit var videoTitle: TextView
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseButton: MaterialButton
    private lateinit var muteButton: MaterialButton

    private val progressListener = RemoteMediaClient.ProgressListener { progress, duration ->
        if (!userSeeking && duration > 0) {
            seekBar.max = duration.toInt()
            seekBar.progress = progress.toInt()
            positionText.text = formatTime(progress)
            durationText.text = formatTime(duration)
        }
    }

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = updateUi()
        override fun onMetadataUpdated() = updateUi()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = attachTo(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attachTo(session)
        override fun onSessionEnded(session: CastSession, error: Int) = finish()
        override fun onSessionStartFailed(session: CastSession, error: Int) = finish()
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_expanded_controls)

        castContext = CastContext.getSharedInstance(this)

        deviceName = findViewById(R.id.device_name)
        videoTitle = findViewById(R.id.video_title)
        positionText = findViewById(R.id.position_text)
        durationText = findViewById(R.id.duration_text)
        seekBar = findViewById(R.id.seek_bar)
        playPauseButton = findViewById(R.id.play_pause_button)
        muteButton = findViewById(R.id.mute_button)

        CastButtonFactory.setUpMediaRouteButton(
            this, findViewById<MediaRouteButton>(R.id.media_route_button)
        )

        playPauseButton.setOnClickListener { remoteMediaClient?.togglePlayback() }
        findViewById<MaterialButton>(R.id.rewind_button).setOnClickListener { seekBy(-10_000) }
        findViewById<MaterialButton>(R.id.forward_button).setOnClickListener { seekBy(30_000) }
        findViewById<MaterialButton>(R.id.volume_down_button).setOnClickListener { changeVolume(-0.05) }
        findViewById<MaterialButton>(R.id.volume_up_button).setOnClickListener { changeVolume(0.05) }
        muteButton.setOnClickListener { toggleMute() }
        findViewById<MaterialButton>(R.id.disconnect_button).setOnClickListener {
            castContext.sessionManager.endCurrentSession(true)
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) positionText.text = formatTime(progress.toLong())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                userSeeking = false
                remoteMediaClient?.seek(
                    MediaSeekOptions.Builder().setPosition(bar.progress.toLong()).build()
                )
            }
        })
    }

    override fun onResume() {
        super.onResume()
        castContext.sessionManager.addSessionManagerListener(
            sessionListener, CastSession::class.java
        )
        val session = castContext.sessionManager.currentCastSession
        if (session == null || !session.isConnected) {
            finish()
        } else {
            attachTo(session)
        }
    }

    override fun onPause() {
        castContext.sessionManager.removeSessionManagerListener(
            sessionListener, CastSession::class.java
        )
        remoteMediaClient?.removeProgressListener(progressListener)
        remoteMediaClient?.unregisterCallback(mediaCallback)
        remoteMediaClient = null
        super.onPause()
    }

    private fun attachTo(session: CastSession) {
        remoteMediaClient?.removeProgressListener(progressListener)
        remoteMediaClient?.unregisterCallback(mediaCallback)
        remoteMediaClient = session.remoteMediaClient
        remoteMediaClient?.registerCallback(mediaCallback)
        remoteMediaClient?.addProgressListener(progressListener, 500)
        deviceName.text = session.castDevice?.friendlyName ?: ""
        updateUi()
    }

    private fun updateUi() {
        val client = remoteMediaClient ?: return
        playPauseButton.setIconResource(
            if (client.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        videoTitle.text = client.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE) ?: ""
        videoTitle.isSelected = true // enable marquee

        val session = castContext.sessionManager.currentCastSession
        muteButton.isChecked = session?.isMute == true
    }

    private fun seekBy(deltaMs: Long) {
        val client = remoteMediaClient ?: return
        val target = (client.approximateStreamPosition + deltaMs)
            .coerceIn(0, maxOf(client.streamDuration, 0))
        client.seek(MediaSeekOptions.Builder().setPosition(target).build())
    }

    private fun changeVolume(delta: Double) {
        val session = castContext.sessionManager.currentCastSession ?: return
        try {
            session.volume = (session.volume + delta).coerceIn(0.0, 1.0)
        } catch (e: Exception) {
            // Device may reject volume changes mid-transition; ignore.
        }
    }

    private fun toggleMute() {
        val session = castContext.sessionManager.currentCastSession ?: return
        try {
            session.isMute = !session.isMute
        } catch (e: Exception) {
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
