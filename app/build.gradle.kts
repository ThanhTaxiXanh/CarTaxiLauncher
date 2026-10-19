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

    // Mật khẩu khóa ký lấy từ biến môi trường (GitHub Secret CARTAXI_KEYSTORE_PASSWORD).
    // Không có biến này (build cục bộ) thì dùng khóa debug mặc định của Android.
    val keystorePassword: String? = System.getenv("CARTAXI_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (keystorePassword != null) {
            create("cartaxi") {
                storeFile = rootProject.file("signing/cartaxi-release.p12")
                storeType = "pkcs12"
                storePassword = keystorePassword
                keyAlias = "cartaxi"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (keystorePassword != null) {
                signingConfig = signingConfigs.getByName("cartaxi")
            }
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
    implementation("org.maplibre.gl:android-sdk:11.5.2")
}
