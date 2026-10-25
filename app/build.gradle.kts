plugins {
    alias(libs.plugins.android.application)
    // AGP 9's com.android.application has built-in Kotlin support - the separate
    // org.jetbrains.kotlin.android plugin is rejected, not just redundant (see the AGP 9 release
    // notes' "agp-built-in-kotlin" migration).
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "org.squaddle.app"
    // Several current-stable AndroidX libraries (navigation-compose 2.10.2 among them) require
    // compiling against API 37+ even though core itself still targets compileSdk 36 - that's
    // fine, an app's compileSdk only needs to be >= every dependency's own minimum.
    compileSdk = 37

    defaultConfig {
        applicationId = "org.squaddle"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
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

    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
}
