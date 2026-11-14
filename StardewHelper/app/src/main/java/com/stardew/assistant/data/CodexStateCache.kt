package com.stardew.assistant.data

import android.content.Context
import java.io.File

/**
 * 图鉴「你的状态」缓存（v2.99.0 / c179）。
 *
 * 与 [FarmTodayCache] 同一套规矩：
 * · **导入时解析一次**（35MB 要几秒），落盘折好的 [CodexState]，图鉴读这份小 JSON；
 * · 缓存生命周期完全跟着农场缓存走 —— [FarmTodayCache.commit] 落盘时一起写，
 *   换档 / 解析失败时一起清。**绝不能留着上一份存档的状态**：显示「没数据」远好过显示别人的农场。
 *
 * ## c217：文件名**带存档标识**
 * 原来只有**一个** `codex_state.json` ⇒ 切存档后图鉴条目详情里的「🎒 你的状态」
 * 显示的是上一个存档的背包 / 好感 / 田里状态（同样是错的）。
 * 现在**每个存档一份**（`codex_state_<token>.json`），与农场缓存同一套 token 规则。
 *
 * 这里只负责读写；刷新节奏统一由 [FarmTodayCache] 驱动（同一个解析出口），
 * 不另设第二个导入钩子 —— 两条刷新路径迟早走出两份不一致的缓存。
 */
object CodexStateCache {

    private const val FILE = "codex_state"

    private fun fileIn(dir: File, profile: String) =
        File(dir, "${FILE}_${FarmTodayCache.tokenOf(profile)}.json")

    private fun file(ctx: Context, profile: String) = fileIn(ctx.filesDir, profile)

    /** 读缓存。IO 操作，**调用方放后台线程**。返回 null = 没导入过（图鉴「你的状态」整段不显示）。 */
    fun read(ctx: Context, profile: String): CodexState? = readIn(ctx.filesDir, profile)

    /** 写缓存。 */
    fun write(ctx: Context, profile: String, model: CodexState) {
        writeIn(ctx.filesDir, profile, model)
    }

    /** 清掉某个存档的缓存（换档 / 解析失败时：宁可空着，也不能显示上一份存档的状态）。 */
    fun clear(ctx: Context, profile: String) {
        clearIn(ctx.filesDir, profile)
    }

    /** 存档改名时把缓存搬过去。 */
    fun renameCache(ctx: Context, old: String, new: String) {
        renameCacheIn(ctx.filesDir, old, new)
    }

    // ── 纯文件版（单测用：不碰 Context，JVM 上可跑）────────────────────

    internal fun readIn(dir: File, profile: String): CodexState? {
        val f = fileIn(dir, profile)
        if (!f.isFile) return null
        return runCatching { codexStateFromJson(f.readText()) }.getOrNull()
    }

    internal fun writeIn(dir: File, profile: String, model: CodexState) {
        runCatching { fileIn(dir, profile).writeText(model.toJson()) }
    }

    internal fun clearIn(dir: File, profile: String) {
        runCatching { fileIn(dir, profile).delete() }
    }

    internal fun renameCacheIn(dir: File, old: String, new: String) {
        val from = fileIn(dir, old)
        val to = fileIn(dir, new)
        if (from.isFile) {
            runCatching {
                if (to.exists()) to.delete()
                from.renameTo(to)
            }
        }
    }
}
