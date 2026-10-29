package org.squaddle.app

import android.app.Application
import android.content.Context
import android.os.StrictMode
import org.squaddle.app.di.AppContainer
import org.squaddle.app.push.NotificationChannels
import org.squaddle.core.store.AndroidSqlDriverContext

/**
 * [AndroidSqlDriverContext.applicationContext] must be set before any [org.squaddle.core.store.createSqlDriver]
 * call on this platform (core/src/androidMain/.../SqlDriverAndroid.kt's own doc comment names this
 * exact responsibility as M4's job).
 */
public class SquaddleApplication : Application() {
    public lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        AndroidSqlDriverContext.applicationContext = this
        container = AppContainer(this)
        NotificationChannels.createAll(this)
    }

    /** M4b item 11 - logcat-only (`penaltyLog`, never `penaltyDeath`): this surfaces an
     * accidental main-thread disk/network call during development without risking a crash on a
     * violation nobody's gotten to yet. Debug builds only - never shipped. */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }
}

/** Every screen reaches the manual DI container through this, never by constructing one itself. */
public fun Context.appContainer(): AppContainer = (applicationContext as SquaddleApplication).container
