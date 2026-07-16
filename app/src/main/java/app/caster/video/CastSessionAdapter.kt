// SPDX-License-Identifier: GPL-3.0-or-later
package app.caster.video

import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

/** [SessionManagerListener] with no-op defaults, so listeners only override what they use. */
open class CastSessionAdapter : SessionManagerListener<CastSession> {
    override fun onSessionStarting(session: CastSession) {}
    override fun onSessionStarted(session: CastSession, sessionId: String) {}
    override fun onSessionStartFailed(session: CastSession, error: Int) {}
    override fun onSessionEnding(session: CastSession) {}
    override fun onSessionEnded(session: CastSession, error: Int) {}
    override fun onSessionResuming(session: CastSession, sessionId: String) {}
    override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
    override fun onSessionResumeFailed(session: CastSession, error: Int) {}
    override fun onSessionSuspended(session: CastSession, reason: Int) {}
}
