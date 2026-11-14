plugins {
    alias(libs.plugins.android.test)
    // ⚠ 必须加 Kotlin 插件：com.android.test 模块默认不编译 Kotlin，
    //   漏掉它的话 .kt 测试文件不参与编译，跑采集时会得到一句让人摸不着头脑的
    //   「Expected test results were not found... there are no tests to run」。
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.androidx.baselineprofile)
}

// v2.49.0：Baseline Profile 的**设备端采集**模块。
//
// 这个模块干两件事：
//   1. 通过 Macrobenchmark 跑一条 UI 测试，把真实启动路径录成 ART profile 规则；
//   2. 把规则写回 app 模块的 src/main/baselineProfiles/，下次 assembleRelease 自动带上。
//
// ⚠ com.android.test 模块**不参与 :app 的打包**，所以下面这 5 个测试依赖
//   不会让 release APK 变大一个字节。
//
// ⚠ 但**采集不是必须的**：app/src/main/baseline-prof.txt 里那份手写保底规则
//   已经能让 APK 里的 assets/dexopt/baseline.prof 含类名（verify 第 42 节会断言）。
//   采集只是把规则从「十几条」扩到「Compose/DataStore/kotlinx 全覆盖」。
android {
    namespace = "com.stardew.assistant.baselineprofile"
    compileSdk = 35

    // ⚠ minSdk **不在** android {} 顶层——TestExtension 只继承 CommonExtension，
    //   顶层只有 compileSdk，minSdk 挂在 defaultConfig 下。
    //   之所以必须显式给：com.android.test 模块默认 minSdk=1，而
    //   androidx.benchmark:benchmark-common 要求 >=21，manifest merger 会直接失败
    //   （uses-sdk:minSdkVersion 1 cannot be smaller than version 21）。
    //   这里与 app 的 minSdk 保持一致（26）。
    defaultConfig {
        minSdk = 26
    }

    // 关键：指向被测模块。这个模块的测试会安装并驱动 :app。
    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// ⚠ 用 Kotlin 2.0 的顶层 `kotlin {}` 写 jvmTarget，而不是 android.kotlinOptions ——
//   com.android.test 的 TestExtension 不提供后者，写在外面会直接编译失败；
//   而 Java 17 与 Kotlin 默认 1.8 不一致时，混编会报 "Inconsistent JVM-target compatibility"。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

baselineProfile {
    // 需要真机/模拟器在线才能采集。本项目本来就靠本机 AVD（Stardew / emulator-5554）。
    useConnectedDevices = true
    // ⚠ 插件 1.3.x **没有** `automaticGenerationDuringBuild` 这个 DSL 属性
    //   （那是后来版本才加的）。1.3.x 本来就只在显式跑 generateBaselineProfile 时才采集，
    //   assembleRelease 不会自动触发，符合本项目「构建必须离线可复现」的约定。
}

dependencies {
    implementation(libs.junit)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.rules)
    implementation(libs.androidx.test.uiautomator)
    // BaselineProfileRule 来自这里，插件不会自动加，必须显式声明
    implementation(libs.androidx.benchmark.macro.junit4)
}
