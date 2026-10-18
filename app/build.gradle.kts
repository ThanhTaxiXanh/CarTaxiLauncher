plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cartaxi.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cartaxi.launcher"
        minSdk = 30
        targetSdk = 33
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1
        versionCode = run
        versionName = "0.1.$run"
    }

    signingConfigs {
        create("cartaxi") {
            storeFile = rootProject.file("signing/cartaxi.p12")
            storeType = "pkcs12"
            storePassword = "cartaxi123"
            keyAlias = "cartaxi"
            keyPassword = "cartaxi123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("cartaxi")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.car.app:app:1.4.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.maplibre.gl:android-sdk:11.5.2")
}
