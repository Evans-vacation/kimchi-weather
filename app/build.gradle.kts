plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions passes these: -PversionCode=<run number> -Prepo=<owner/repo>
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val updateRepo = (project.findProperty("repo") as String?) ?: ""
// 기상청 API허브 인증키 (GitHub secret KMA_KEY). Empty → the app just uses Open-Meteo.
val kmaKey = ((project.findProperty("kmaKey") as String?) ?: System.getenv("KMA_KEY") ?: "").trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }
val releaseKey = file("release.jks")
// Project version shown on the phone (설정 → 애플리케이션 → 날씨). Edit version.properties to change it.
val projectVersion = rootProject.file("version.properties").takeIf { it.exists() }
    ?.readLines()?.firstOrNull { it.startsWith("version=") }?.substringAfter("=")?.trim() ?: "1.0.0"

android {
    namespace = "app.nalssi"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.nalssi"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = projectVersion
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "KMA_KEY", "\"$kmaKey\"")
    }
    buildFeatures { buildConfig = true }

    // github: my phone, updates itself from GitHub Releases.
    // store:  Galaxy Store build — no self-update, no install-packages permission (the store updates it).
    flavorDimensions += "dist"
    productFlavors {
        create("github") { dimension = "dist" }
        create("store") {
            dimension = "dist"
            buildConfigField("String", "UPDATE_REPO", "\"\"")
        }
    }

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
