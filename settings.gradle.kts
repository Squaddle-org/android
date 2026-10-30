rootProject.name = "android"

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // org.squaddle:core's own real home (core/build.gradle.kts's GitHubPages publishing
        // target) - a plain static Maven repo on GitHub Pages, fully anonymous, no token needed
        // to resolve it (unlike GitHub Packages, which core also publishes to but which requires
        // a token even for public packages - the whole reason this exists). SQUADDLE_CORE_DIR
        // (below) still wins for local dev against an unreleased core; this is what a real
        // consumer with no sibling core checkout actually resolves from.
        maven { url = uri("https://squaddle-org.github.io/core/") }
    }
}

plugins {
    // Auto-provisions the JDK 17 build toolchain, same as core/relay's own settings.gradle.kts.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Local dev against an unreleased core (android/CLAUDE.md): SQUADDLE_CORE_DIR=../core switches
// the `org.squaddle:core` dependency below to a composite-build substitution instead of a
// published artifact - no core publish/version bump needed while iterating on both repos at once.
val coreDir: String? = System.getenv("SQUADDLE_CORE_DIR")
if (coreDir != null) {
    includeBuild(coreDir) {
        dependencySubstitution {
            substitute(module("org.squaddle:core")).using(project(":"))
        }
    }
}

include(":app")
