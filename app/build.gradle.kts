plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions passes these: -PversionCode=<run number> -Prepo=<owner/repo>
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val updateRepo = (project.findProperty("repo") as String?) ?: ""
val releaseKey = file("release.jks")

android {
    namespace = "app.nalssi"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.nalssi"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = "1.0.$appVersionCode"
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }
    buildFeatures { buildConfig = true }

    signingConfigs {
        create("release") {
            if (releaseKey.exists()) {
                storeFile = releaseKey
                storePassword = "nalssi-store"
                keyAlias = "nalssi"
                keyPassword = "nalssi-store"
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKey.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
