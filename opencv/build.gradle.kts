plugins {
    id("com.android.library")
}

android {
    namespace = "org.opencv"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    // 禁用 debug symbol stripping（WSL 中缺少 Linux NDK 工具链）
    buildTypes {
        debug {
            isJniDebuggable = true
        }
    }

    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols.add("**/*.so")
        }
    }

    val opencvSdkPath = if (file("D:/AndroidSDK/OpenCV-android-sdk").exists()) {
        "D:/AndroidSDK/OpenCV-android-sdk"
    } else {
        "/mnt/d/AndroidSDK/OpenCV-android-sdk"
    }
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${opencvSdkPath}/sdk/native/libs")
            java.srcDirs("${opencvSdkPath}/sdk/java/src")
            res.srcDirs("${opencvSdkPath}/sdk/java/res")
            manifest.srcFile("${opencvSdkPath}/sdk/java/AndroidManifest.xml")
        }
    }
}
