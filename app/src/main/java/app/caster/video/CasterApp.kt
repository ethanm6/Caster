package app.caster.video

import android.app.Application
import com.google.android.material.color.DynamicColors

class CasterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: derive the app palette from the user's wallpaper (Android 12+).
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
