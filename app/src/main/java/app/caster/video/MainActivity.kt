package app.caster.video

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

class MainActivity : AppCompatActivity() {

    private lateinit var castContext: CastContext
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var chooseButton: Button

    /** The URL the Chromecast will fetch, exactly as received (or our local server URL). */
    private var castUrl: String? = null
    private var castMimeType: String = "video/mp4"
    private var videoTitle: String = ""
    private var pendingLoad = false
    private var localFileServed = false

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            if (pendingLoad) loadMedia(session)
            updateStatus()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            if (pendingLoad) loadMedia(session)
            updateStatus()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            if (localFileServed) {
                LocalHttpServerService.stop(this@MainActivity)
                localFileServed = false
            }
            updateStatus()
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            statusText.text = getString(R.string.status_connect_failed)
        }

        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            prepareLocalFile(uri, contentResolver.getType(uri))
            maybeCastNow()
        }
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* casting works either way; notification controls just won't show if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        statusText = findViewById(R.id.status_text)
        titleText = findViewById(R.id.title_text)
        chooseButton = findViewById(R.id.choose_button)
        chooseButton.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }

        castContext = CastContext.getSharedInstance(this)

        askForNotificationsIfNeeded()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        castContext.sessionManager.addSessionManagerListener(
            sessionListener, CastSession::class.java
        )
        updateStatus()
    }

    override fun onPause() {
        castContext.sessionManager.removeSessionManagerListener(
            sessionListener, CastSession::class.java
        )
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item)
        return true
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }

        if (uri == null) {
            titleText.text = getString(R.string.landing_hint)
            return
        }

        videoTitle = intent.getStringExtra("title")
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
            ?: uri.lastPathSegment
            ?: getString(R.string.default_title)

        when (uri.scheme) {
            "http", "https" -> {
                // Cast the URL exactly as received from the sending app.
                castUrl = uri.toString()
                castMimeType = intent.type?.takeIf { it != "*/*" }
                    ?: guessMimeType(uri.toString())
                localFileServed = false
            }
            "content", "file" -> prepareLocalFile(uri, intent.type)
            else -> {
                Toast.makeText(this, R.string.unsupported_uri, Toast.LENGTH_LONG).show()
                return
            }
        }

        titleText.text = videoTitle
        maybeCastNow()
    }

    private fun prepareLocalFile(uri: Uri, intentType: String?) {
        val mime = intentType?.takeIf { it != "*/*" }
            ?: contentResolver.getType(uri)
            ?: guessMimeType(uri.toString())
        val ip = NetworkUtils.getLocalIpAddress(this)
        if (ip == null) {
            Toast.makeText(this, R.string.no_network, Toast.LENGTH_LONG).show()
            return
        }
        val token = LocalHttpServerService.start(this, uri, mime)
        castUrl = "http://$ip:${LocalHttpServer.PORT}/video/$token"
        castMimeType = mime
        localFileServed = true
        if (videoTitle.isEmpty()) {
            videoTitle = uri.lastPathSegment ?: getString(R.string.default_title)
        }
        titleText.text = videoTitle
    }

    /** Load immediately if already connected, otherwise wait for the user to pick a device. */
    private fun maybeCastNow() {
        if (castUrl == null) return
        val session = castContext.sessionManager.currentCastSession
        if (session != null && session.isConnected) {
            loadMedia(session)
        } else {
            pendingLoad = true
            statusText.text = getString(R.string.status_pick_device)
        }
    }

    private fun loadMedia(session: CastSession) {
        val url = castUrl ?: return
        val remoteMediaClient = session.remoteMediaClient ?: return
        pendingLoad = false

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, videoTitle)
        }
        val mediaInfo = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(castMimeType)
            .setMetadata(metadata)
            .build()

        remoteMediaClient.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build()
        ).setResultCallback { result ->
            if (result.status.isSuccess) {
                startActivity(Intent(this, ExpandedControlsActivity::class.java))
            } else {
                statusText.text = getString(R.string.status_load_failed, result.status.statusCode)
            }
        }
        updateStatus()
    }

    private fun updateStatus() {
        val session = castContext.sessionManager.currentCastSession
        statusText.text = when {
            session != null && session.isConnected ->
                getString(R.string.status_connected, session.castDevice?.friendlyName ?: "")
            castUrl != null -> getString(R.string.status_pick_device)
            else -> getString(R.string.status_idle)
        }
    }

    private fun guessMimeType(url: String): String {
        val cleaned = url.substringBefore('?').substringBefore('#')
        return when (val ext = cleaned.substringAfterLast('.', "").lowercase()) {
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "mkv" -> "video/x-matroska"
            "" -> "video/mp4"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "video/mp4"
        }
    }

    private fun askForNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
