plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "gr.gamemap"
    compileSdk = 36

    defaultConfig {
        applicationId = "gr.gamemap"
        minSdk = 26
        targetSdk = 36
        // CI passes -PbuildNumber=<run number>; the updater compares against it.
        val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        // From 1091 on the number is the build times 10 plus the fix: 1091 = "109.1", 1100 = "110"
        // (older builds, up to 109, keep their plain number)
        versionName = "build-" + if (build >= 1000) (build / 10).toString() + (if (build % 10 != 0) ".${build % 10}" else "") else "$build"
        // Built-in TomTom key, from the TOMTOM_KEY secret at build time (never stored in the code).
        buildConfigField("String", "REPORTS_URL", "\"${System.getenv("REPORTS_URL") ?: ""}\"")
        // test builds (CI -PtestBuild=true) carry the Developer page; public builds never do
        buildConfigField("boolean", "TEST_BUILD", if ((project.findProperty("testBuild") as String?) == "true") "true" else "false")
        buildConfigField("String", "TOMTOM_KEY", "\"${System.getenv("TOMTOM_KEY") ?: ""}\"")
    }
    signingConfigs {
        // Fixed key in the repo so every build can update the previous one in place.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    // Two versions of the same app: "github" (sideloaded, updates itself from GitHub releases)
    // and "play" (Google Play: no self-updater, no install-packages permission).
    flavorDimensions += "store"
    productFlavors {
        // GitHub releases are signed with the repo's fixed key, so every build updates the last one
        create("github") { dimension = "store"; signingConfig = signingConfigs.getByName("debug") }
        create("play") { dimension = "store" }
    }
    // Play upload key: CI decodes the PLAY_KEYSTORE secret to a file and passes it in here.
    val uploadStore = System.getenv("PLAY_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    if (uploadStore != null) signingConfigs.create("upload") {
        storeFile = uploadStore
        storePassword = System.getenv("PLAY_KEYSTORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("PLAY_KEYSTORE_PASSWORD")
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (uploadStore != null) signingConfig = signingConfigs.getByName("upload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    // lint runs in its own checks; it shouldn't block a release build
    lint { checkReleaseBuilds = false; abortOnError = false }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                listOf("previewData", "previewOut").forEach { k ->
                    System.getProperty(k)?.let { test.systemProperty(k, it) }
                }
                test.maxHeapSize = "2g"
                test.setForkEvery(1) // each test class in a fresh JVM (the app keeps state in singletons)
            }
        }
    }
}

dependencies {
    implementation("androidx.car.app:app:1.4.0")
    // the car's own speed and GPS on Android Auto (CarHardwareManager)
    implementation("androidx.car.app:app-projected:1.4.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    // MQTT: real-time convoy positions through a public broker
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    // QR codes for friend links (drawing only; pure Java, no camera)
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.car.app:app-testing:1.4.0")
}
