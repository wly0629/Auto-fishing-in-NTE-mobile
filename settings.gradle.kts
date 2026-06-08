pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AutoFish"
include(":app")

// OpenCV 模块 — 使用项目内 shim build.gradle.kts，跳过 SDK 的 CMake native 构建
// 预编译的 libopencv_java4.so 已通过 jniLibs 直接引用
include(":opencv")
project(":opencv").projectDir = file(rootProject.projectDir.toString() + "/opencv")
