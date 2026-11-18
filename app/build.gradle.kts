plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "io.github.i2996.sleepcover"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.i2996.sleepcover"
        minSdk = 24
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    // 개인용 사이드로드 앱: 빌드마다 같은 키로 서명해야 업데이트 설치가 됩니다.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("signing/sleepcover.jks")
            storePassword = "sleepcover"
            keyAlias = "sleepcover"
            keyPassword = "sleepcover"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
