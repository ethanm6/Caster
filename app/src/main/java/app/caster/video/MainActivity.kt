// SPDX-License-Identifier: GPL-3.0-or-later
package app.caster.video

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.mediarouter.app.MediaRouteChooserDialogFragment
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import android.view.View
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAST_DIALOG_TAG = "cast_chooser_dialog"
        private val URL_REGEX = Regex("""https?://\S+""")
    }

    private lateinit var castContext: CastContext
    private lateinit var statusText: TextView
    private lateinit var chooseButton: Button
    private lateinit var urlInputLayout: TextInputLayout
    private lateinit var urlInput: TextInputEditText
    private lateinit var miniBar: MaterialCardView
    private lateinit var miniTitle: TextView
    private lateinit var miniDevice: TextView
    private lateinit var miniPlayPause: MaterialButton
    private var remoteMediaClient: RemoteMediaClient? = null

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = updateMiniBar()
        override fun onMetadataUpdated() = updateMiniBar()
    }

    /** The URL the Chromecast will fetch, exactly as received (or our local server URL). */
    private var castUrl: String? = null
    private var castMimeType: String = "video/mp4"
    private var videoTitle: String = ""
    private var pendingLoad = false
    private var localFileServed = false

    private val sessionListener = object : CastSessionAdapter() {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            attachClient(session)
            if (pendingLoad) loadMedia(session)
            updateStatus()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            attachClient(session)
            if (pendingLoad) loadMedia(session)
            updateStatus()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            attachClient(null)
            if (localFileServed) {
                LocalHttpServerService.stop(this@MainActivity)
                localFileServed = false
            }
            updateStatus()
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            statusText.text = getString(R.string.status_connect_failed)
        }
    }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) prepareLocalFile(uri, null, persistAccess = true)
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* casting works either way; notification controls just won't show if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        statusText = findViewById(R.id.status_text)
        chooseButton = findViewById(R.id.choose_button)
        chooseButton.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }

        urlInputLayout = findViewById(R.id.url_input_layout)
        urlInput = findViewById(R.id.url_input)
        urlInputLayout.setEndIconOnClickListener { castPastedUrl() }
        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                castPastedUrl()
                true
            } else false
        }
        urlInput.doAfterTextChanged { urlInputLayout.error = null }

        miniBar = findViewById(R.id.mini_bar)
        miniTitle = findViewById(R.id.mini_title)
        miniDevice = findViewById(R.id.mini_device)
        miniPlayPause = findViewById(R.id.mini_play_pause)
        miniBar.setOnClickListener {
            startActivity(Intent(this, ExpandedControlsActivity::class.java))
        }
        miniPlayPause.setOnClickListener { remoteMediaClient?.togglePlayback() }

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
        attachClient(castContext.sessionManager.currentCastSession)
        updateStatus()
    }

    override fun onPause() {
        castContext.sessionManager.removeSessionManagerListener(
            sessionListener, CastSession::class.java
        )
        remoteMediaClient?.unregisterCallback(mediaCallback)
        remoteMediaClient = null
        super.onPause()
    }

    private fun attachClient(session: CastSession?) {
        remoteMediaClient?.unregisterCallback(mediaCallback)
        remoteMediaClient = if (session?.isConnected == true) session.remoteMediaClient else null
        remoteMediaClient?.registerCallback(mediaCallback)
        updateMiniBar()
    }

    private fun updateMiniBar() {
        val client = remoteMediaClient?.takeIf { it.hasMediaSession() }
        miniBar.visibility = if (client != null) View.VISIBLE else View.GONE
        if (client == null) return
        val title = client.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE)
            ?: getString(R.string.default_title)
        // Re-assigning the same text restarts the marquee scroll.
        if (miniTitle.text?.toString() != title) {
            miniTitle.text = title
            miniTitle.isSelected = true
        }
        miniDevice.text = castContext.sessionManager.currentCastSession
            ?.castDevice?.friendlyName ?: ""
        miniPlayPause.setIconResource(
            if (client.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        CastButtonFactory.setUpMediaRouteButton(this, menu, R.id.media_route_menu_item)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_source -> { openUrl(getString(R.string.url_source)); true }
        R.id.action_support -> { openUrl(getString(R.string.url_support)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    ?: sharedLinkUri(intent)
            else -> null
        }

        if (uri == null) return

        val explicitTitle = intent.getStringExtra("title")
            ?: intent.getStringExtra(Intent.EXTRA_TITLE)
            ?: intent.getStringExtra(Intent.EXTRA_SUBJECT) // browsers put the page title here

        when (uri.scheme) {
            "http", "https" -> {
                videoTitle = explicitTitle
                    ?: uri.lastPathSegment
                    ?: getString(R.string.default_title)
                // Cast the URL exactly as received from the sending app.
                castUrl = uri.toString()
                // A shared link's intent type describes the share (text/plain),
                // not the video — never send it to the receiver as contentType.
                castMimeType = intent.type?.takeIf { it != "*/*" && !it.startsWith("text/") }
                    ?: guessMimeType(uri.toString())
                localFileServed = false
                maybeCastNow()
            }
            // Casts on its own once the background resolve completes.
            "content", "file" -> prepareLocalFile(uri, intent.type, explicitTitle)
            else -> Toast.makeText(this, R.string.unsupported_uri, Toast.LENGTH_LONG).show()
        }
    }

    /** Casts whatever http(s) URL is in the paste box, exactly as typed. */
    private fun castPastedUrl() {
        val url = firstHttpUrl(urlInput.text?.toString())
        if (url == null) {
            urlInputLayout.error = getString(R.string.invalid_url)
            return
        }
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(urlInput.windowToken, 0)
        urlInput.clearFocus()

        val uri = Uri.parse(url)
        videoTitle = uri.lastPathSegment ?: getString(R.string.default_title)
        castUrl = url
        castMimeType = guessMimeType(url)
        localFileServed = false
        maybeCastNow()
    }

    /** The first http(s) URL in shared text — browsers share links as text/plain. */
    private fun sharedLinkUri(intent: Intent): Uri? =
        firstHttpUrl(intent.getStringExtra(Intent.EXTRA_TEXT))?.let(Uri::parse)

    /** The first http(s) URL in [text] — pasted or shared text may wrap the link in prose. */
    private fun firstHttpUrl(text: String?): String? =
        text?.let { URL_REGEX.find(it)?.value }

    /**
     * Resolves the file's mime type and display name — content-provider round
     * trips that can stall on cloud-backed providers, so they run off the main
     * thread — then starts the local server and casts.
     */
    private fun prepareLocalFile(
        uri: Uri,
        intentType: String?,
        explicitTitle: String? = null,
        persistAccess: Boolean = false
    ) {
        val ip = NetworkUtils.getLocalIpAddress(this)
        if (ip == null) {
            Toast.makeText(this, R.string.no_network, Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            if (persistAccess) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // Access still lasts until the process dies; casting proceeds.
                }
            }
            val mime = intentType?.takeIf { it != "*/*" }
                ?: contentResolver.getType(uri)
                ?: guessMimeType(uri.toString())
            val title = explicitTitle
                ?: queryDisplayName(uri)
                ?: uri.lastPathSegment
                ?: getString(R.string.default_title)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val token = LocalHttpServerService.start(this, uri, mime)
                castUrl = "http://$ip:${LocalHttpServer.PORT}/video/$token"
                castMimeType = mime
                localFileServed = true
                videoTitle = title
                maybeCastNow()
            }
        }.start()
    }

    /** The file name of a content:// or file:// URI, e.g. "Movie.mkv". */
    private fun queryDisplayName(uri: Uri): String? = when (uri.scheme) {
        "content" -> try {
            contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        "file" -> uri.lastPathSegment
        else -> null
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
            openCastMenu()
        }
    }

    /** Pop the Cast device chooser, same dialog the toolbar cast button shows. */
    private fun openCastMenu() {
        val selector = castContext.mergedSelector ?: return
        if (supportFragmentManager.findFragmentByTag(CAST_DIALOG_TAG) != null) return
        MediaRouteChooserDialogFragment().apply {
            routeSelector = selector
        }.show(supportFragmentManager, CAST_DIALOG_TAG)
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
