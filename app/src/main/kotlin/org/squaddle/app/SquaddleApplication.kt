package org.squaddle.app

import android.app.Application
import org.squaddle.core.store.AndroidSqlDriverContext

/**
 * [AndroidSqlDriverContext.applicationContext] must be set before any [org.squaddle.core.store.createSqlDriver]
 * call on this platform (core/src/androidMain/.../SqlDriverAndroid.kt's own doc comment names this
 * exact responsibility as M4's job). Nothing else happens here yet - [org.squaddle.app.di.AppContainer]
 * (added in a later commit) owns the actual singleton wiring.
 */
public class SquaddleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidSqlDriverContext.applicationContext = this
    }
}
