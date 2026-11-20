plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Подпись — только из окружения: путь к ключу не должен жить в публичном репозитории.
// Конфигурация создаётся лишь когда ключ есть, иначе любая задача (даже тесты) падала бы на
// этапе настройки проекта — что и случилось в CI.
val keystorePath: String = System.getenv("BYECENSOR_KEYSTORE").orEmpty()
val keystorePass: String = System.getenv("BYECENSOR_KEYSTORE_PASS").orEmpty()
val haveKey: Boolean = keystorePath.isNotBlank() && keystorePass.isNotBlank()

// Ядро в репозитории не лежит (его скачивает scripts/fetch-core.sh), и сборка без него
// проходила молча: APK получался вдвое легче, а приложение падало с «нет ядра libxray.so».
// Пусть лучше падает сборка — с внятным текстом.
val kernel = file("src/main/jniLibs/arm64-v8a/libxray.so")
require(kernel.exists()) {
    "нет ядра: запустите scripts/fetch-core.sh (ожидается ${kernel.absolutePath})"
}

android {
    namespace = "ru.uvw.byecensor"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.uvw.byecensor"
        minSdk = 33
        targetSdk = 34
        versionCode = 47
        versionName = "0.9.8"
        // только arm64: ядро Xray собрано под него, и держать в APK чужие архитектуры незачем
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (haveKey) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePass
                keyAlias = System.getenv("BYECENSOR_KEY_ALIAS") ?: "byecensor"
                keyPassword = keystorePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (haveKey) signingConfig = signingConfigs.getByName("release")
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

// Отдельный релиз без ключа был бы неподписанным APK, который никто не поставит: лучше
// сказать это прямо, чем оставить человека с файлом, который «не устанавливается».
tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst {
        if (!haveKey) {
            throw GradleException(
                "для assembleRelease нужны BYECENSOR_KEYSTORE и BYECENSOR_KEYSTORE_PASS; " +
                    "assembleDebug собирается без ключа",
            )
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
