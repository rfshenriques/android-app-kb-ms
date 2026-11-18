plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.remotekeyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.remotekeyboard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // one fixed signing key, so every build can be installed over the previous one
    // (CI machines are fresh each run and would otherwise create a new random key every time)
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    flavorDimensions += "mode"
    productFlavors {
        // everything, including the mouse (Accessibility) service
        create("full") { dimension = "mode" }
        // keyboard only: no Accessibility service declared, so Play Protect does not block it
        create("kbOnly") {
            dimension = "mode"
            applicationIdSuffix = ".kb"
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
    }
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
}
