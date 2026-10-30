import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9's com.android.application has built-in Kotlin support - the separate
    // org.jetbrains.kotlin.android plugin is rejected, not just redundant (see the AGP 9 release
    // notes' "agp-built-in-kotlin" migration).
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.roborazzi)
}

/** `null` if `git` isn't on `PATH`, this isn't a git checkout, or there's no tag at all yet -
 * never fails the build either way (M4b item 12's own fallback requirement). Run once at
 * configuration time, same as every other `git describe`-derived Android version scheme. */
fun gitDescribeTagOrNull(): String? = try {
    val process = ProcessBuilder("git", "describe", "--tags", "--always", "--dirty")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && output.isNotBlank()) output else null
} catch (e: Exception) {
    null
}

/** `versionCode` must be a plain monotonically-increasing int (Play's own requirement) - a
 * literal parse of `git describe`'s tag string into one would break the moment a tag isn't
 * strictly numeric semver, so this uses total commit count instead (always increases, and
 * already reflects every tag's own position in history). `versionName` is the human-readable
 * `git describe` string (or the hardcoded fallback) - the two are deliberately not required to
 * "match" each other numerically. */
fun gitCommitCountOrNull(): Int? = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) output.toIntOrNull() else null
} catch (e: Exception) {
    null
}

android {
    namespace = "org.squaddle.app"
    // Several current-stable AndroidX libraries (navigation-compose 2.10.2 among them) require
    // compiling against API 37+; core's own compileSdk was bumped to 37 too (M4b item 12 - its
    // ktor-client-okhttp dependency pulls in an okhttp-android version that required it for this
    // app's own release build to pass AAR metadata checks at all).
    compileSdk = 37

    defaultConfig {
        applicationId = "org.squaddle"
        minSdk = 26
        targetSdk = 37
        versionCode = gitCommitCountOrNull() ?: 1
        versionName = gitDescribeTagOrNull() ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_RELAY_HOST", "\"relay.squaddle.org\"")
    }

    // android/CLAUDE.md: foss (no Google deps at all, F-Droid) and play (Play Billing only, not
    // added yet - M5b). Dimension name is purely internal, never user-visible.
    flavorDimensions += "distribution"
    productFlavors {
        create("foss") {
            dimension = "distribution"
        }
        create("play") {
            dimension = "distribution"
            // Intentionally empty for now (M4a) - Play Billing lands in M5b.
        }
    }

    // keystore.properties is gitignored (confirmed in .gitignore) and never committed - the
    // signing config is simply absent (an unsigned release build) when it's missing, which is
    // exactly what lets CI and every contributor's own machine still build fossRelease/
    // playRelease without a real signing key.
    val keystorePropertiesFile = File(rootDir, "keystore.properties")
    val releaseSigningConfig = if (keystorePropertiesFile.exists()) {
        val keystoreProperties = Properties().apply { load(keystorePropertiesFile.inputStream()) }
        signingConfigs.create("release") {
            storeFile = File(rootDir, keystoreProperties.getProperty("storeFile"))
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    } else {
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfig != null) signingConfig = releaseSigningConfig
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

sqldelight {
    databases {
        // App-local bookkeeping only (room display names, read cursors) - deliberately a
        // separate database from core's own SquaddleDb, since none of this has protocol meaning
        // (see AppLocalDb.sq's own doc comment).
        create("AppLocalDb") {
            packageName.set("org.squaddle.app.store")
        }
    }
}

dependencies {
    implementation("org.squaddle:core:0.1.0-SNAPSHOT") // version ignored when composite-built (SQUADDLE_CORE_DIR)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.sqldelight.driver.android)
    implementation(libs.work.runtime.ktx)
    implementation(libs.unifiedpush.connector)
    implementation(libs.biometric)
    // androidx.biometric:1.1.0 transitively pulls androidx.fragment:1.2.5 (2020-era) - badly
    // skewed against activity-compose's 1.13.0, which crashes MainActivity (now a
    // FragmentActivity, needed for BiometricPrompt) the moment anything calls
    // ActivityResultLauncher.launch(): "IllegalArgumentException: Can only use lower 16 bits for
    // requestCode" - FragmentActivity's own permission-request validation rejects request codes
    // ComponentActivity's modern ActivityResultRegistry generates. Pinning a current fragment-ktx
    // here overrides the stale transitive one (Gradle's default highest-version-wins).
    implementation(libs.fragment.ktx)

    // android/CLAUDE.md: play flavor gets Play Billing (M5b, not yet added) - Install Referrer
    // (item 9, M4b) lands now since it needs no billing setup, just the flavor-specific
    // dependency; foss stays with zero Google artifacts on its classpath (CI's fossRelease check).
    "playImplementation"(libs.installreferrer)

    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.espresso.core)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")

    // Design-pass spec's "lock it in" step: Roborazzi renders each screenshot test's Composable
    // under Robolectric (JVM, no emulator) and diffs it against a committed reference PNG under
    // src/test/screenshots/ - a size/color/layout regression fails the test instead of only
    // surfacing in a manual look-through. roborazzi-compose adds the Compose-specific
    // captureRoboImage(); roborazzi-junit-rule's RoborazziRule is what actually decides
    // record-vs-verify per the -Proborazzi.test.record system property the Gradle plugin wires up.
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}

roborazzi {
    outputDir.set(file("src/test/screenshots"))
}
