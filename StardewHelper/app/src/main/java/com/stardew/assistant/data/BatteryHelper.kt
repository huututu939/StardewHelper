package com.stardew.assistant.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * c234：让接收会话在息屏/退后台后**还能活**的三把钥匙。
 *
 * ## 为什么需要它（c233 轮实测出来的坑）
 * c233 加了前台服务，用户反馈：「常驻通知在，但电脑说没扫描到手机」。
 * 实测（魅族 20 / Flyme）发现三件事：
 * ① 进程**没被杀**（前台服务在、通知在、线程还在）；
 * ② 但进程**被冻结**了 —— `dumpsys activity processes` 里 `isFrozen=4`、
 *    `isFreezeExempt=false`：线程不被调度，UDP 不回应，
 *    连监听端口在 `/proc/net/tcp` 里都整个消失；
 * ③ 加进**电池优化白名单**后，同一台机、同一 App，只切这一个开关：
 *    退后台 60 秒 → 正常；息屏 90 秒 → 正常；不加 → 退后台 90 秒 → 无回应。
 *
 * ⚠ 所以**前台服务保的是「进程不被杀」，电池优化白名单保的是「进程不被冻结」**——
 *   两把钥匙缺一不可，而 c233 只有前一把。这就是「通知在但收不到」的完整机理。
 *
 * ## 三把钥匙
 * ① **电池优化白名单**（系统通用）：申请它，进程就不会被冻结；
 * ② **Flyme 自启动管理**（国产 ROM 的第二道锁）：魅族/OPPO/vivo 等会在
 *    「自启动」里再卡一道，被关掉的 App 连前台服务都不给保活；
 * ③ **通知权限**：只是让常驻通知可见（拒绝也不影响接收本身）。
 *
 * ## 为什么要自己跳系统页
 * 系统对话框（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）只覆盖第 ① 把；
 * ② 在 Flyme 上没有公开的「针对某个 App 直接跳转」入口，只能跳到列表页，
 * 让用户自己找一眼 —— 这不优雅，但是**唯一能走通的路**（跳到列表总比让他
 * 在设置里自己翻要强）。
 */
object BatteryHelper {

    /** Flyme 的自启动管理页（公开 action，魅族上实测可开）。 */
    private const val FLYME_AUTOSTART_ACTION = "com.meizu.safe.security.autostart_manager_settings"

    /**
     * 本 App 是否已在电池优化白名单里。
     *
     * ⚠ `isIgnoringBatteryOptimizations` 在 Android 6+ 可用；更低版本返回 true
     *   （那时候根本没有 Doze/冻结机制，不该拦着用户）。
     */
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)
    }

    /**
     * 系统对话框申请电池优化白名单。
     *
     * ⚠ 必须带 `package:` URI（`package:<包名>`），不带的话系统直接抛异常。
     * ⚠ 某些 ROM（如 Flyme）会拒绝这个 action——所以调用方必须包 runCatching，
     *   失败时退到 [openBatterySettings]。
     */
    fun requestIgnoreBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (isIgnoringBatteryOptimizations(ctx)) return true
        return runCatching {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${ctx.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * 跳「电池优化应用列表」页（用户自己找一眼）。
     *
     * 这是 [requestIgnoreBatteryOptimizations] 失败时的退路，也是
     * 用户想自己查白名单时的入口。
     */
    fun openBatterySettings(ctx: Context): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 跳「应用详情」页（退路中的退路：所有 ROM 都有这个页）。
     */
    fun openAppDetails(ctx: Context): Boolean = runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 跳 Flyme「自启动管理」页。
     *
     * ⚠ 只有魅族系 ROM 有这个 action；别的 ROM 返回 false，
     *   调用方要能接受「没有这一页」并给别的引导（别假装跳成功了）。
     */
    fun openAutostartSettings(ctx: Context): Boolean = runCatching {
        val intent = Intent(FLYME_AUTOSTART_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 「是否建议用户去开白名单」。
     *
     * 只在用户**主动打开了后台常驻**时才需要这把钥匙；
     * 没开那个开关的人，不该被这个提示打扰（那是他的选择，不是缺陷）。
     */
    fun needsWhitelistHelp(ctx: Context): Boolean =
        !isIgnoringBatteryOptimizations(ctx)
}
