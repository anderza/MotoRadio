plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rockradionet.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.motoradio.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.9"
    }

    // Тот же ключ, что у build-apk.sh: сборки из GitHub и из облака ставятся поверх друг друга
    signingConfigs {
        create("motoradio") {
            storeFile = rootProject.file("motoradio.keystore")
            storePassword = "motoradio"
            keyAlias = "motoradio"
            keyPassword = "motoradio"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // В публичный репозиторий ключ не кладём: без него собирается неподписанный APK, его подписывают тем же ключом отдельно
            if (rootProject.file("motoradio.keystore").exists()) signingConfig = signingConfigs.getByName("motoradio")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Звук играет Media3 (ExoPlayer), как в RockRadioNet («Колесо»): он открывает потоки, которые не берёт системный MediaPlayer
dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
}


// Обложки станций: при сборке в GitHub Actions скачиваются и встраиваются в приложение
// (tools/bundle_covers.py). Если что-то не получилось, сборка идёт дальше, а обложки грузятся с сайтов.
val bundleCovers by tasks.registering(Exec::class) {
    workingDir = rootDir
    commandLine(
        "bash", "-c",
            "python3 -c 'import PIL' 2>/dev/null || python3 -m pip install --quiet --user --break-system-packages pillow || python3 -m pip install --quiet --user pillow || true; " +
            "python3 tools/bundle_covers.py app/src/main/assets/www/index.html || true"
    )
    isIgnoreExitValue = true
    onlyIf { System.getenv("CI") == "true" && rootDir.resolve("tools/bundle_covers.py").exists() }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(bundleCovers) }
