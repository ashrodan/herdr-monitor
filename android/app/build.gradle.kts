plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.ashrodan.herdrmonitor"
    // Compose 1.12 and material3 1.5 need compileSdk 37. The app still
    // targets SDK 36 (Android 16).
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.ashrodan.herdrmonitor"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8: shrinks and optimises the Compose code (a debug build is
            // several times slower to draw) and drops unused resources.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release code signed with the debug key, to measure start-up and
        // frames on a device (./gradlew installPerf). Not for distribution.
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    // Material 3 Expressive (MaterialExpressiveTheme, MotionScheme): 1.5.0-beta02
    // as the design brief says. Move to 1.5.0 stable when it is out.
    implementation("androidx.compose.material3:material3:1.5.0-beta02")
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite:1.5.0-beta02")
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")
    // material3-adaptive family (allowed): ListDetailPaneScaffold and its navigator.
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.3.0")
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.3.0")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    // Compose UI tests (androidTest). ui-test-manifest gives createComposeRule
    // its host activity in debug builds.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
