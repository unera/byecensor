plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.uvw.byecensor"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.uvw.byecensor"
        minSdk = 33
        targetSdk = 34
        versionCode = 32
        versionName = "0.8.3"
        // только arm64: ядро Xray собрано под него, и держать в APK чужие архитектуры незачем
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            // Подпись — только из окружения: путь к ключу не должен жить в публичном
            // репозитории, а пустой пароль не должен молча превращаться в «подпись не удалась».
            val store = System.getenv("BYECENSOR_KEYSTORE")
            val pass = System.getenv("BYECENSOR_KEYSTORE_PASS")
            if (store.isNullOrBlank() || pass.isNullOrBlank()) {
                throw GradleException(
                    "release signing needs BYECENSOR_KEYSTORE and BYECENSOR_KEYSTORE_PASS; " +
                        "use assembleDebug to build without a key",
                )
            }
            storeFile = file(store)
            storePassword = pass
            keyAlias = System.getenv("BYECENSOR_KEY_ALIAS") ?: "byecensor"
            keyPassword = pass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // ядро лежит в jniLibs под именем libxray.so: Android 10+ не даёт запускать файлы из
    // каталога данных приложения, а из nativeLibraryDir — даёт
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
