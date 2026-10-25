// Root project: no module-level config lives here, just the plugin version catalog aliases
// (declared but not applied) so `app/build.gradle.kts` can `alias(...)` them without repeating
// version coordinates.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.sqldelight) apply false
}
