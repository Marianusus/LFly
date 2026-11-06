import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
val abiFilter = providers.gradleProperty("abiFilter").orNull

android {
    namespace = "org.sonora.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.sonora.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "1.7.1"

        if (!abiFilter.isNullOrBlank()) {
            ndk {
                abiFilters.clear()
                abiFilters.addAll(abiFilter.split(",").map { it.trim() })
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
    }
    sourceSets {
        // The phone <-> watch protocol, shared with :wearos.
        getByName("main") {
            java.srcDir("../wearos/protocol")
            // LFly Connect's wire protocol, shared with the desktop app.
            java.srcDir("../connect/protocol")
        }
    }
    androidResources {
        // The model is copied to disk once; compressing it would only slow that copy.
        noCompress += "onnx"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Reorderable drag & drop for the queue sheet (Compose 1.7 compatible)
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Media3 (ExoPlayer & Session)
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    // Automix: on-device inference of the Beat This! beat tracker (CPJKU, MIT).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    // DJ voice: Piper neural voices through sherpa-onnx (k2-fsa, Apache-2.0), the build that links
    // its own ONNX Runtime statically, repacked without x86 where it would clash with the one above.
    implementation(files("libs/sherpa-onnx-2.0.1-static.aar"))
    // Unpacks the voice downloads (.tar.bz2).
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")

    // Coil for album artwork
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Palette for dynamic artwork dominant colors
    implementation("androidx.palette:palette-ktx:1.0.0")

    // Networking & JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // YAML parsing for LRCLIB "lyricsfile" word-level payloads
    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.google.code.gson:gson:2.11.0")

    // On-device translation (ML Kit)
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // Party: nearby device discovery / transport + QR join
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("com.google.android.gms:play-services-cast-framework:22.2.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Wear OS: the LFly watch app talks to the phone over the Wearable Data Layer.
    implementation("com.google.android.gms:play-services-wearable:19.0.0")

    // LFly Connect: each device serves a small WebSocket on the local network (pure Java, also on desktop).
    implementation("org.java-websocket:Java-WebSocket:1.5.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Core AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// The watch app travels inside the phone app, so Settings can install or update it on a paired
// watch. Same build type on both sides: the Data Layer only connects apps with the same signature.
abstract class BundleWatchApk : DefaultTask() {
    @get:InputFiles
    abstract val watchApks: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        val apk = watchApks.asFileTree.matching { include("*.apk") }.files.firstOrNull() ?: return
        apk.copyTo(File(dir, "lfly-watch.apk"), overwrite = true)
    }
}

androidComponents {
    onVariants { variant ->
        val type = variant.buildType ?: return@onVariants
        val cap = type.replaceFirstChar { it.uppercase() }
        val bundle = tasks.register<BundleWatchApk>("bundleWatchApk$cap") {
            dependsOn(":wearos:assemble$cap")
            watchApks.from(rootProject.layout.projectDirectory.dir("wearos/build/outputs/apk/$type"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleWatchApk::outputDir)
    }
}
