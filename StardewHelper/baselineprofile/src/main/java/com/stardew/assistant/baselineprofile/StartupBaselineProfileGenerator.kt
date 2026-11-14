package com.stardew.assistant.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.uiautomator.UiDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 用 UiAutomator 而非 Espresso 驱动：Espresso 要额外对齐 Compose 测试依赖的版本，
 * 稍有不慎就和 app 的 compose-bom 打架；UiAutomator 只需要无障碍树，
 * 而 Compose 默认就把 semantics 发布到无障碍树，文本节点照样能按文案点到。
 *
 * 只用最稳的几个 API（getInstance / pressHome / wait / findObject / click）：
 * UiAutomator 2.3.x 已经移除了 `UiDevice.launchApp`，`waitForIdle(long)` 也改了签名，
 * 所以启动走 Context + Intent，超时等待走 Thread.sleep，不依赖那些正在漂移的封装。
 *
 * 采集出来的规则会写进 app/src/main/baselineProfiles/，下次 assembleRelease 自动合并。
 * 采集不到也不影响发布——app/src/main/baseline-prof.txt 那份手写规则是保底。
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startupAndBrowse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val context = instrumentation.targetContext
        val pkg = context.packageName

        // 冷启动：先回桌面再拉起，保证是从零进进程（采集热启动没有意义）
        device.pressHome()
        Thread.sleep(1_500)

        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch == null) {
            throw IllegalStateException("取不到 $pkg 的启动 Intent，采集无法进行")
        }
        context.startActivity(launch)

        device.wait(Until.hasObject(By.pkg(pkg).depth(0)), 20_000)
        Thread.sleep(2_500)

        // 首屏之外的高频路径：图鉴（最大的数据集加载）、进度、村民。
        // 用文案定位，找不到就跳过——采集不该因为 UI 改版就整个挂掉。
        for (label in listOf("图鉴", "进度", "村民")) {
            device.findObject(By.text(label))?.let {
                it.click()
                Thread.sleep(2_000)
            }
        }
    }
}
