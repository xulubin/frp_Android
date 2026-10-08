plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wordbuddy.frpc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wordbuddy.frpc"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // frpc 只打包 arm64（Redmi Note 7 为 arm64-v8a）
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 关键：让 .so 被解压到 nativeLibraryDir，才能被 exec（Android 10+ 不允许从可写目录执行）
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Material Components：底部导航、MaterialButton（大圆按钮）、TextInputLayout 等
    implementation("com.google.android.material:material:1.12.0")
}
