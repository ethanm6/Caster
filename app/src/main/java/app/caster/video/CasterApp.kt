package app.caster.video

import android.app.Application
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.material.color.DynamicColors

class CasterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Material You: derive the app palette from the user's wallpaper (Android 12+).
        DynamicColors.applyToActivitiesIfAvailable(this)
        initCastWatchdog()
    }

    /**
     * Ends the cast session automatically when playback is aborted on the
     * Chromecast side (stopped from the TV, another sender, or an error),
     * and stops the local file server once any session ends.
     */
    private fun initCastWatchdog() {
        val castContext = try {
            CastContext.getSharedInstance(this)
        } catch (e: Exception) {
            return // Google Play Services unavailable; casting won't work anyway.
        }
        val sessionManager = castContext.sessionManager

        val mediaCallback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val status = sessionManager.currentCastSession
                    ?.remoteMediaClient?.mediaStatus ?: return
                val aborted = status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                    (status.idleReason == MediaStatus.IDLE_REASON_CANCELED ||
                        status.idleReason == MediaStatus.IDLE_REASON_ERROR)
                if (aborted) {
                    sessionManager.endCurrentSession(true)
                }
            }
        }

        sessionManager.addSessionManagerListener(object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) {
                session.remoteMediaClient?.registerCallback(mediaCallback)
            }

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                session.remoteMediaClient?.registerCallback(mediaCallback)
            }

            override fun onSessionEnded(session: CastSession, error: Int) {
                LocalHttpServerService.stop(this@CasterApp)
            }

            override fun onSessionStarting(session: CastSession) {}
            override fun onSessionStartFailed(session: CastSession, error: Int) {}
            override fun onSessionEnding(session: CastSession) {}
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            override fun onSessionSuspended(session: CastSession, reason: Int) {}
        }, CastSession::class.java)
    }
}
