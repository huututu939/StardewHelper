pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "StardewHelper"
include(":app")
// v2.49.0：设备端采集模块。com.android.test 模块不参与 app 打包，
// 它的测试依赖也不会进 release APK。
include(":baselineprofile")
