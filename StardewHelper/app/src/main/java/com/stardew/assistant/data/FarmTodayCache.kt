package com.stardew.assistant.data

import android.content.Context
import android.util.Xml
import java.io.File
import java.security.MessageDigest

/**
 * 「我的农场」缓存。
 *
 * ## 为什么要缓存
 * 存档动辄 35MB，解析一遍要几秒。每日打开「我的农场」都重解析 = 每次都卡一次。
 * 所以：**导入时解析一次**，把折好的展示模型（[FarmToday]）落盘，
 * 今日页只读这份几十行的小 JSON。
 *
 * ## 缓存键：**存档名**（c217 起）
 * ⚠ 原来只有**一个** `farm_today.json`，不带任何存档标识 ⇒ 切存档后「我的农场」
 *   显示的是**上一个存档**的农场（哪块地种了什么、今天收多少钱 —— 全是错的）。
 *   代码注释写着「宁可空着也不能显示上一份存档的数据」，但那条纪律**只守住了
 *   「导入解析失败」这一条路径**，切档这条路整个绕过去了。
 *
 *   现在**每个存档一份自己的缓存文件**（`farm_today_<token>.json`），
 *   切档读的就是那个存档自己的那份 —— 串档在结构上就不可能发生。
 *
 * ## 写盘时机：**用户点「确定导入」之后**（c217 起）
 * ⚠ 原来在 [SaveImport.parse] 的回调里**解析成功即落盘** —— 用户点「取消」
 *   照样把当前存档的农场换成了刚解析的那份。于是勾选还是旧存档的、农场却是新存档的，
 *   同一屏里自相矛盾。
 *   现在解析只把结果放进 [Pending]（内存），确认后才 [commit] 落盘。
 *
 * ## 只读
 * 存档只读不写（沿用 [SaveImport] 的既有约定）：这里只从存档里**读**，
 * 写出去的只有 App 自己文件目录里的一份 JSON。
 */
object FarmTodayCache {

    private const val FILE = "farm_today"

    /** 文件名里不许出现的字符（Windows / Android 都吃不下）。 */
    private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /**
     * 解析出来、**还没落盘**的农场数据。
     *
     * 存在进程内存里（一次导入一次 pending），确认后才 [commit]。
     * @param profile 这份数据解析时归属的存档 —— 与 [commit] 的存档不一致时拒绝落盘
     *   （「导进存档1」的数据不能落进存档2）。
     */
    data class Pending(
        val profile: String,
        val farm: FarmToday,
        val codex: CodexState?,
    )

    @Volatile
    private var pending: Pending? = null

    /** 取走待落盘的结果（并清空）。null = 没有待落盘的东西。 */
    fun takePending(): Pending? {
        val p = pending
        pending = null
        return p
    }

    /** 丢弃待落盘的结果（用户点了「取消」/ 导入失败）。 */
    fun discardPending() {
        pending = null
    }

    // ── 文件名 ────────────────────────────────────────────────────────

    /**
     * 存档名 → 缓存文件名的一部分。
     *
     * ## 为什么不能直接拿存档名当文件名
     * 存档名是**用户可改的自由文本**（[PrefsRepo.renameProfile] 只过滤「:」），
     * 留着 `/` `\` `*` `?` `"` `<` `>` `|` 与空格。直接当文件名会写到别的目录去
     * （或干脆建不出来）。
     *
     * 做法 = **可读前缀 + 名称哈希**：
     * · 前缀把文件系统不认的字符换成 `_`，其余照留（「存档1」一眼认得出）；
     * · SHA-1 前 4 字节保证「A/B」与「A_B」这类清洗后撞名的两档不共用一个文件。
     */
    internal fun tokenOf(profile: String): String {
        val parts = ArrayList<String>()
        for (cp in profile.codePoints().toArray()) {
            // ⚠ 用 **码点**判合法性，不要用 `s[0]`（char）—— 代理对（emoji 等）
            //   的第一个 char 是高代理位（0xD800~0xDBFF），既不是白名单字符，
            //   也判不出「这是不是一个完整字符」，按 char 处理会把 emoji 拆成两半。
            val bad = cp < 0x20 || Character.isWhitespace(cp) ||
                cp.toChar() in ILLEGAL_NAME_CHARS
            parts += if (bad) "_" else String(Character.toChars(cp))
        }
        val sb = StringBuilder()
        var len = 0
        for (p in parts) {
            if (len >= 16) break
            sb.append(p)
            len += p.length
        }
        val safe = sb.toString().trim('_').ifBlank { "p" }
        val digest = MessageDigest.getInstance("SHA-1").digest(profile.toByteArray(Charsets.UTF_8))
        val hex = (0 until 4).joinToString("") { "%02x".format(digest[it]) }
        return "${safe}_$hex"
    }

    private fun fileIn(dir: File, profile: String) = File(dir, "${FILE}_${tokenOf(profile)}.json")

    private fun file(ctx: Context, profile: String) = fileIn(ctx.filesDir, profile)

    // ── 读写 ──────────────────────────────────────────────────────────

    /** 读缓存。IO 操作，**调用方放后台线程**。null = 这个存档没导入过 / 没数据。 */
    fun read(ctx: Context, profile: String): FarmToday? = readIn(ctx.filesDir, profile)

    /** 写缓存（覆盖）。 */
    fun write(ctx: Context, profile: String, model: FarmToday) {
        writeIn(ctx.filesDir, profile, model)
    }

    /**
     * 清掉某个存档的缓存（两份一起清：农场 + 图鉴状态）。
     *
     * ⚠ 农场与图鉴状态是**同一条纪律**（图鉴「你的状态」也是存档态数据），
     *   只清一份就会留下一半上一份存档的数据。
     */
    fun clear(ctx: Context, profile: String) {
        clearIn(ctx.filesDir, profile)
    }

    /** 存档改名时把缓存搬过去（不然改名后农场数据就「消失」了）。 */
    fun renameCache(ctx: Context, old: String, new: String) {
        renameCacheIn(ctx.filesDir, old, new)
    }

    // ── 纯文件版（单测用：不碰 Context，JVM 上可跑）────────────────────

    internal fun readIn(dir: File, profile: String): FarmToday? {
        val f = fileIn(dir, profile)
        if (!f.isFile) return null
        return runCatching { farmTodayFromJson(f.readText()) }.getOrNull()
    }

    internal fun writeIn(dir: File, profile: String, model: FarmToday) {
        runCatching { fileIn(dir, profile).writeText(model.toJson()) }
    }

    internal fun clearIn(dir: File, profile: String) {
        runCatching { fileIn(dir, profile).delete() }
        CodexStateCache.clearIn(dir, profile)
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
        CodexStateCache.renameCacheIn(dir, old, new)
    }

    // ── 解析 → 待落盘 ─────────────────────────────────────────────────

    /**
     * 从主存档解析世界状态 → 组装展示模型 → **放进 [Pending]（不落盘）**。
     * **必须在后台线程调用**（35MB 要几秒）。
     *
     * @param source 主存档取流（[SaveImport.MainSaveSource]）
     * @param sourceLabel 来源说明（文件名 / Uri），显示用
     * @param profile 这份数据归属的存档（落盘时用）
     * @return true = 解析成功（结果在 [takePending] 里）；false = 失败
     *
     * ⚠ **失败时不动已有的缓存**：这次导入根本没发生（用户还没确认），
     *   盘上原来的农场还是这个存档自己的那份 —— 清掉反而是把对的变成没有。
     *   （旧实现在这里清缓存，是把「解析失败」与「换存档」混成了一件事。）
     */
    fun parse(
        ctx: Context,
        source: SaveImport.MainSaveSource,
        sourceLabel: String = "",
        profile: String,
    ): Boolean = try {
        val state = WorldStateParser.parse(opener = {
            configureSaveParser(Xml.newPullParser(), source.open())
        })
        // 售价只认 App 自己的 crops.json（上游的 sell_price 实测有错）
        val prices = GameData.baseCrops.associate { it.name to it.sellPrice }
        val model = FarmTodayBuilder.build(
            state = state,
            map = GameData.worldItemMap,
            prices = prices,
            sourceLabel = sourceLabel,
            savedAt = System.currentTimeMillis(),
        )
        // 图鉴「你的状态」：同一份 WorldState 再折一份。
        // ⚠ 折不出来就留 null（commit 时清掉旧的那份），不留半截数据。
        var codex: CodexState? = null
        runCatching {
            val npcNames = ReportNames.npcTable(ctx)
            // Mod 村民的第二路映射：gifts 数据里的 srcId（存档里写的就是这个英文 id）
            val srcIds = GameData.gifts
                .filter { it.srcId != null }
                .associate { it.srcId!! to it.name }
            codex = CodexStateBuilder.build(state, GameData.worldItemMap, npcNames, srcIds)
        }
        pending = Pending(profile, model, codex)
        true
    } catch (e: Throwable) {
        pending = null
        false
    }

    /**
     * 把 [Pending] 落盘到**指定存档**。
     *
     * @return true = 落盘成功；false = 没有待落盘的数据，或它归属的存档与这里不符
     *   （「导进存档1」的数据不许落进存档2）。
     */
    fun commit(ctx: Context, profile: String, p: Pending?): Boolean {
        val item = p ?: return false
        if (item.profile != profile) return false
        write(ctx, profile, item.farm)
        if (item.codex != null) {
            CodexStateCache.write(ctx, profile, item.codex)
        } else {
            // 这次折不出图鉴状态 ⇒ 把这个存档的旧状态清掉（宁可没有，不要上一份的）
            CodexStateCache.clear(ctx, profile)
        }
        return true
    }
}
