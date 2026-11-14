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
    }
    // One fixed key for every build, so a new APK installs over the old one instead of being refused.
    // (A personal sideloading key kept in the repo; not suitable for publishing to an app store.)
    signingConfigs {
        create("mb") {
            storeFile = file("mbrowser.keystore")
            storePassword = "mbrowser"
            keyAlias = "mbrowser"
            keyPassword = "mbrowser"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("mb") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("mb")
        }
    }
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
}
