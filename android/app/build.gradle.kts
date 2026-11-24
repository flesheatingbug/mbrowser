plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.mbrowser"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.mbrowser"
        minSdk = 26
        targetSdk = 34
        // CI numbers every build, so each new APK counts as an upgrade of the one before
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.3." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // Pre-release builds (CI sets MB_PRE=1 on branch "next") install as a separate app with their own data and update channel
        val pre = System.getenv("MB_PRE") == "1"
        if (pre) applicationIdSuffix = ".pre"
        manifestPlaceholders["appLabel"] = if (pre) "mbrowser Pre" else "mbrowser"
        // Release builds carry the Google client secret from the GOOGLE_CLIENT_SECRET repository secret (never committed); without it settings asks once
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"" + (System.getenv("GOOGLE_CLIENT_SECRET") ?: "") + "\"")
        resValue("string", "update_tag", if (pre) "pre-apk" else "latest-apk")
    }
    // Builds are signed with a personal key so a new APK installs over the old one. The key is stored
    // encrypted in the repo (mbrowser.keystore.enc); its passphrase is the GitHub secret KEYSTORE_PASSPHRASE.
    // Without the passphrase (a local build) the normal Android debug key is used instead.
    val ksPass: String? = System.getenv("KEYSTORE_PASSPHRASE")
    if (ksPass != null) {
        signingConfigs {
            create("mb") {
                storeFile = file("mbrowser-ci.keystore")
                storePassword = ksPass
                keyAlias = "mbrowser"
                keyPassword = ksPass
            }
        }
    }
    buildTypes {
        getByName("debug") { signingConfigs.findByName("mb")?.let { signingConfig = it } }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("mb")?.let { signingConfig = it }
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
}
