package org.squaddle.app

import android.app.Application
import android.content.Context
import org.squaddle.app.di.AppContainer
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
        AndroidSqlDriverContext.applicationContext = this
        container = AppContainer(this)
    }
}

/** Every screen reaches the manual DI container through this, never by constructing one itself. */
public fun Context.appContainer(): AppContainer = (applicationContext as SquaddleApplication).container
