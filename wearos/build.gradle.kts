import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/*
 * LFly for Wear OS: a remote for the phone app, and its speaker when asked. It holds no library and
 * has no internet of its own: the phone sends what is playing, its library and a small cover over the
 * Wearable Data Layer, and, with the watch as the speaker, the audio bytes over a Data Layer channel.
 *
 * The Data Layer only delivers between apps with the same application id *and* signing key, so this
 * takes the phone's id and is signed the way the phone is: the debug key for debug, the release
 * keystore for release.
 */

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) FileInputStream(keystorePropertiesFile).use { load(it) }
}

android {
    namespace = "org.sonora.android.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.sonora.android"
        // Wear OS 3, the oldest a Galaxy Watch 4 shipped with.
        minSdk = 30
        targetSdk = 35
        versionCode = 9
        versionName = "1.6.1"
    }

    sourceSets {
        getByName("main") { java.srcDir("protocol") }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

base.archivesName.set("LFlyWear")

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.wear.compose:compose-material:1.4.1")
    implementation("androidx.wear.compose:compose-foundation:1.4.1")
    implementation("androidx.wear.compose:compose-navigation:1.4.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    // Our own icon on the watch face while the phone plays (NowPlayingListener).
    implementation("androidx.wear:wear-ongoing:1.0.0")
    // AmbientLifecycleObserver: the always-on player.
    implementation("androidx.wear:wear:1.3.0")

    // The Data Layer: state from the phone, commands to it.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // The watch as the speaker: a player reading the audio from the phone, in a media session so
    // it plays on with the screen off. Same Media3 as the phone app.
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")

    // The now-playing tile.
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.1")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
}
