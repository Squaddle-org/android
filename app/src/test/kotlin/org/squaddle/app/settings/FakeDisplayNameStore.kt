package org.squaddle.app.settings

/** In-memory [DisplayNameStore] test double - no Android `Context`/`SharedPreferences`. */
public class FakeDisplayNameStore : DisplayNameStore {
    private var name: String? = null

    override fun displayName(): String? = name

    override fun setDisplayName(name: String) {
        this.name = name
    }
}
