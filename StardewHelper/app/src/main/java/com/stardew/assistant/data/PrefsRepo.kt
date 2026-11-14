package com.stardew.assistant.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stardew.assistant.ui.AppFont
import com.stardew.assistant.ui.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "stardew_prefs")

/**
 * 偏好 + 多存档进度：主题全局；日期 / 职业 / 打勾进度按存档独立保存。
 * v1.6 的旧扁平格式（checks / game_year… ）首次启动自动迁移为「存档1」，旧键保留以便降级。
 */
/**
 * v2.29 最近查看的一条记录：**分类 id + 条目名**，**不存下标**。
 *
 * 下标会随数据包更新漂移（fishing.json 补一条鱼，后面所有鱼的下标全部 +1），
 * 那样存的结果会在一次数据包更新后**静默点开错误的条目**。存名字则稳定：
 * 改名/删除的条目反查失败即被丢弃，不会指向错的东西。
 */
data class CodexRecentRef(val groupId: String, val itemName: String)

/**
 * v2.72.2 计算页的输入条件（落盘用，语义单源在 [PrefsRepo.CALC]）。
 *
 * ## v2.92.0：**块数被拆出去**（[CalcPlots]，按存档记），这里只留「用法」
 * 原来一份 [CalcSetup] 里混着两类东西，v2.72.2 当时按「全局一份」处理：
 * - **用法**（我刚才在拿什么参数试算）：[mode]（露天/温室/姜岛）、[quality]、
 *   以及历史上留着的 [season] / [day]（v2.89.0 起季节日期已与今日页共享，
 *   这两个字段留着只为不破坏盘上旧数据与备份格式）。
 * - **事实**（这个存档的农场有多大）：三种地的**块数**。
 *
 * 混在一起的代价是：块数也成了全局一份 —— 切到块数不同的存档，计算页还按
 * 上一个存档的块数算净利，**数字是错的**（用户 2026-10-25 报）。
 * ⇒ 块数拆成 [CalcPlots]，**按存档**记；用法仍全局。
 *
 * ## 为什么三种地块是**三个具名字段**，不是 map
 * v2.72.1 先用 `mutableStateMapOf(mode to n)` 在内存里分了三种地块，
 * v2.72.2 要落盘就该一起改掉——map 的下标是裸 Int，写错一个键编译期毫无反应。
 * 改成三个具名字段后，读写都必须带 `mode`（[CalcPlots.plots] /
 * [CalcPlots.withPlots]），**「忘了带 mode」在类型上就写不出来**，
 * 与 v2.72.1 那次修复的意图一致，只是这次由类型系统兜底而不是靠人记住。
 */
data class CalcSetup(
    /** 0 露天 · 1 温室 · 2 姜岛 */
    val mode: Int = 0,
    val season: Int = 0,
    val day: Int = 1,
    /** [Calc.QUALITY_OPTIONS] 的下标 */
    val quality: Int = 0,
)

/**
 * v2.92.0：三种地的**块数**，**按存档**记（键 `cp:<存档名>`）。
 *
 * ## 为什么按存档
 * 块数是**这个存档的事实**：A 存档 50 块地、B 存档 10 块。
 * 用全局一份的话，切到 B 存档计算页还按 50 块算，出来的净利全是错的。
 *
 * ## 为什么 mode / 品质**不**跟着走
 * 那两个是「我刚才在拿什么参数试算」——是**用法**，不是这局游戏的事实。
 * 换存档继续用同一套参数才顺手（用户原话只指块数没隔离）。
 */
data class CalcPlots(
    val outdoor: Int = 10,
    val greenhouse: Int = 10,
    val island: Int = 10,
) {
    /** 当前 mode 对应的块数。越界的 mode 落回露天（与 UI 的 `outdoor = mode == 0` 同口径）。 */
    fun plots(mode: Int): Int = when (mode) {
        1 -> greenhouse
        2 -> island
        else -> outdoor
    }

    /** 只改当前 mode 的块数，另外两种原样带回。 */
    fun withPlots(mode: Int, n: Int): CalcPlots = when (mode) {
        1 -> copy(greenhouse = n)
        2 -> copy(island = n)
        else -> copy(outdoor = n)
    }
}

/**
 * v2.57 今日待办默认清单（单源：旧 TODO_ITEMS 在 TodayPage 里是 private，
 * 现在待办列表变成可自定义数据，默认值跟着数据层走，UI 与迁移共用这一份）。
 */
val TODO_DEFAULT = listOf(
    "浇水（含温室 / 姜岛）",
    "送礼（每周 2 次，生日优先）",
    "摸动物 · 添草料",
    "收蛋 / 牛奶 / 剪毛",
    "查蟹笼 · 补鱼饵",
    "推进矿井 / 钓鱼目标",
)

class PrefsRepo(private val context: Context) {

    private val THEME = stringPreferencesKey("theme_mode")
    // v2.59 字号档位（群友反馈⑦）：**全局键**——字号是阅读偏好，与存档/游戏进度无关（与 THEME 同类）。
    // 存档位**下标**（0/1/2），倍率语义单源在 ui.FontScale.SCALES；将来调倍率只改那一处，老存档不用迁移。
    private val FONT_SCALE = intPreferencesKey("font_scale_idx")
    // v2.93.0 字体档（像素 / 系统）：**全局键**——与 THEME / FONT_SCALE 同类（阅读偏好，
    // 与存档/游戏进度无关）。落盘值单源在 ui.AppFont；只有恰好 "system" 才被认，
    // 其余（脏值 / 老版本没这键）一律像素字体。
    // v2.95.0 加第三档 "custom"（用户导入的 TTF）：见 [CustomFont]。
    private val FONT_FAMILY = stringPreferencesKey("font_family")
    // v2.95.0：自定义字体的**内容哈希 id**（对应 filesDir/fonts/<id>.ttf）。
    // 单独一个键，而不是把 id 拼进 font_family —— font_family 保持「档位」语义，
    // 这个键只管「当前用哪个字体文件」。老版本没这键 ⇒ 空串 ⇒ custom 档回落像素。
    private val FONT_CUSTOM = stringPreferencesKey("font_custom_id")
    private val INTRO = booleanPreferencesKey("intro_seen")
    private val PROFILES = stringPreferencesKey("profiles_json")
    private val ACTIVE = stringPreferencesKey("active_profile")
    // v2.57：自定义今日待办。**全局键**——清单是个人习惯，不随存档走；
    // 勾选状态仍按存档分（ck:<存档名> 里的 todo: 键），两边语义不同别混。
    // 存 JSONArray 字符串（本仓库 datastore 版本无 stringListPreferencesKey；
    // 与 PROFILES 同款模式）。
    private val TODO_LIST = stringPreferencesKey("todo_list")

    // v1.6 遗留键（仅迁移用）
    private val LEGACY_YEAR = intPreferencesKey("game_year")
    private val LEGACY_SEASON = intPreferencesKey("game_season")
    private val LEGACY_DAY = intPreferencesKey("game_day")
    private val LEGACY_PROF5 = stringPreferencesKey("prof5")
    private val LEGACY_PROF10 = stringPreferencesKey("prof10")
    private val LEGACY_CHECKS = stringSetPreferencesKey("checks")

    /** v2.27：勾选键改名的一次性迁移标记（见 migrateCheckKeys） */
    private val CHECK_KEYS_V227 = booleanPreferencesKey("check_keys_v227")

    /** v2.39：作物改名的一次性迁移标记（见 migrateCropRename） */
    private val CHECK_KEYS_V239 = booleanPreferencesKey("check_keys_v239")

    /** v2.57：待办勾选键从「下标」改「文本」的一次性迁移标记（见 migrateTodoKeys） */
    private val TODO_KEYS_V257 = booleanPreferencesKey("todo_keys_v257")

    /** v2.82：作物改名（四个直译名 → 官方译名）的一次性迁移标记（见 migrateCropRenameV282） */
    private val CHECK_KEYS_V282 = booleanPreferencesKey("check_keys_v282")

    /**
     * v2.29 图鉴导航记忆（最近查看 / 上次看的分类）。
     *
     * **全局键，不是存档级键**：这是导航便利，与游戏进度无关——
     *  ① 切存档时导航历史不该清空（换了存档，最近看的还是那些条目）；
     *  ② 全局键不进 [exportJson]，所以导入别人的备份不会覆盖你的导航历史。
     *
     * 存档级键一律带存档名前缀（d: / pf5: / pf10: / ck:），这两个不带——
     * 那是「导航记忆 vs 存档进度」的分层标志，verify 第 24 节会钉。
     */
    private val CODEX_RECENT = stringPreferencesKey("codex_recent_json")
    private val CODEX_LAST_GROUP = stringPreferencesKey("codex_last_group")

    /**
     * v2.71「特别关心」的村民名集合（用户在村民页点 ★ 标记的人）。
     *
     * **全局键，不是存档级键**：村民是固定数据不是存档进度，换存档时
     * 「我在重点攻略谁」不该跟着清空（与 CODEX_RECENT 同族）。
     * 但与导航记忆不同，它**进 exportJson 备份**——这是用户手动标注的数据，
     * 丢了要重新一个个标（与 todo_list 同族：用户内容 vs 设备态）。
     *
     * 存名字不存下标：数据包改名/删人时反查失败即丢弃，不会指向错的人
     * （与 CODEX_RECENT 同一条纪律）。
     */
    private val FOLLOWED = stringSetPreferencesKey("followed_villagers")

    // v2.61 自动更新提示（群友反馈「有新版本会提示吗」）。三个都是**设备态**，
    // 不是用户数据，所以**不进 exportJson**（与 CODEX_RECENT / INTRO 同款理由：
    // 换手机时「上次检查时间」「关掉过的版本」「要不要提示」都不该被备份带走）。
    // UPD_SEEN 存的是**已关闭的版本号**而不是布尔——用户关掉 v2.61 后，
    // 下次发 v2.62 横幅还会再出现，这才是对的行为。
    private val UPD_AT = longPreferencesKey("update_checked_at")
    private val UPD_SEEN = intPreferencesKey("update_seen_version")
    private val UPD_ON = booleanPreferencesKey("update_prompt_enabled")

    /**
     * v2.69「已发现的新版本」快照（JSON 化的 [com.stardew.assistant.data.UpdateRepo.Latest]）。
     *
     * 为什么必须有它：v2.61 的横幅状态只活在内存（MainActivity 的 `remember`），
     * 当时查更新还有 20 小时节流——「发新版那天开过 App 但人不在今日页」的用户，
     * 节流记账已落盘 → 20 小时内重开不再查 → **横幅一次都没出现过**，
     * 而他本人也早就忘了这事。提示被节流顺带饿死了。
     * v2.73.3 起节流已废（每次开 App 都查），但本键的价值仍在：
     * **提示不等网络**——断网冷启动也要能把上次发现的版本摆出来。
     *
     * 落盘之后语义就分开了：**节流约束的是"发请求"，不是"给提示"**。
     * 横幅是否显示只看这份快照，所以节流窗口内重开也能立刻看到上次的横幅。
     */
    private val UPD_PENDING = stringPreferencesKey("update_pending_json")

    /**
     * v2.69：启动弹窗「已经为哪个版本弹过」。
     *
     * 存**版本号**而不是布尔——发 v2.70 后应该再弹一次。
     * 与 [UPD_SEEN] 的区别：那个是用户主动划掉横幅（「别再提」），
     * 这个只是「这次已经打断过你了」（点稍后就不再打断，但横幅仍在）。
     * 两者混用会让「稍后」变成永久屏蔽。
     */
    private val UPD_DIALOG = intPreferencesKey("update_dialog_version")

    /**
     * v2.70.0 底栏样式开关：true = 液态玻璃胶囊，false = 经典木纹栏。
     *
     * 默认开（用户验收玻璃效果后主动要了开关，当前主力观感是玻璃）。
     * 设备态**不进备份**：跟主题/字号不同，玻璃能不能开取决于本机 API（33+），
     * 把 true 搬到旧手机只会得到一个无效开关——这类"能力相关偏好"不跨机搬运
     * （与 update_* 同族，verify 55.3 钉住）。
     */
    private val GLASS_BAR = booleanPreferencesKey("glass_bar")

    /**
     * c228 玻璃浓度档（设备态，不进备份，与 GLASS_BAR 同族）。0=轻 / 1=标准 / 2=浓。
     *
     * 默认 1 = 标准：与 c227 及更早的观感逐像素一致（基准白纱/暗纱 + blur 基准值）。
     * 存**档位下标**而不是浓度系数本身：系数是 ui 层的实现细节（GlassLadder 单源），
     * 落盘存 UI 概念，将来调系数不用迁移旧值。
     */
    private val GLASS_LEVEL = intPreferencesKey("glass_level")
    // c215 音效开关（设备态，不进备份；默认开 = 既有行为）
    private val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
    // c233 局域网接收的「后台常驻」（设备态，不进备份）：开 = 接收会话起前台服务保活
    private val LAN_FGS = booleanPreferencesKey("lan_foreground")

    /**
     * c207：图鉴勾选锁定（设备态，不进备份）。true = 图鉴格子右上角的勾选角标只显示、
     * 点了不记录，防止查图鉴时手滑误勾。
     *
     * ⚠ 这是**界面行为偏好**，不是勾选数据——勾选数据仍然只存在 checks 集合里
     * （续 26.11 零迁移：不为勾选本身新增任何键）。设备态不进备份，与 GLASS_BAR 同族。
     */
    private val CODEX_MARK_LOCKED = booleanPreferencesKey("codex_mark_locked")

    /**
     * v2.72「Mod 扩展包」三个键，**全是设备态，一个都不进 exportJson**。
     *
     * 三个的理由各不相同，写在这里免得以后有人「顺手」把它们加进备份：
     *  - [MOD_INDEX_URL] 是**配置**（这个仓库地址属于当前这台机器的用法）。
     *  - [MOD_ENABLED] / [MOD_INSTALLED] 描述的是「**这台手机上**装了哪些包」。
     *    跟着备份走到另一台机器，会得到「开着 SVE 但一个村民都没有」的幽灵包——
     *    启用集合必须与**本机实际存在的目录**同进同退（都由 ModsPage 一起写）。
     */
    private val MOD_INDEX_URL = stringPreferencesKey("mod_index_url")
    private val MOD_ENABLED = stringSetPreferencesKey("mod_enabled")
    private val MOD_INSTALLED = stringSetPreferencesKey("mod_installed")

    /**
     * v2.72.2 计算页的输入条件（模式 / 品质；季节日期 v2.89.0 起与今日页共享）。
     *
     * **全局键，不是存档级键**：这里记的是「我刚才在拿什么参数试算」——
     * 那是**用法**，不是这局游戏的事实。换存档继续用同一套参数才顺手。
     *
     * ⚠ v2.92.0：**块数已经拆出去了**（见 [calcPlotsKey]）。原来一份里混着
     *   「用法」与「事实」两类东西，块数属于后者（这个存档的农场有多大），
     *   跟着全局走会让切到块数不同的存档时算出错的净利（用户 2026-10-25 报）。
     *
     * 与导航记忆的差别是**进 exportJson 备份**：它属于「用户手动填的内容」，
     * 丢了要重新一个个拨转盘（与 FOLLOWED「标★要一个个点」同一条理由），
     * 而 CODEX_RECENT 那种「顺手翻过」的记忆丢了无所谓。
     */
    private val CALC = stringPreferencesKey("calc_setup_json")

    /**
     * v2.92.0：计算页三种地的**块数**，**按存档**记（`cp:<存档名>`）。
     *
     * 与存档级键（`d:` / `pf5:` / `pf10:` / `ck:`）同类：块数是**这个存档的事实**
     * （A 存档 50 块地、B 存档 10 块），跟着存档走才对。
     */
    private fun calcPlotsKey(n: String) = stringPreferencesKey("cp:$n")

    /** 最近查看最多留几条 */
    private val CODEX_RECENT_LIMIT = 12

    /**
     * v2.27 勾选键重映射。
     *
     * 锅炉房（房间序 3）· 冒险家（包序 2）的首项「史莱姆泥」在 item_index.json 里
     * 根本查不到（Slime 的条目名是「黏液」），已改名。勾选键是按物品**名字**拼的
     * （cc:3:2:史莱姆泥），改名后老存档里这一项会静默变回未勾，必须搬一次。
     *
     * 注意：一定要**落盘重写**，不能在读取时做只读映射——只读映射下用户取消勾选时
     * 旧键还在集合里，下次读又映射回来，等于取消不掉。
     *
     * v2.100.0 / c182：键**不再手写**（改调 [bundleCheckKey]）——
     *   `cc:` 前缀只允许出现在 ProgressCalc.kt 一个地方（键模板单源），
     *   这里手写一份就等于第二份模板，将来改格式两处一起漏。
     *   产出的键与原来**逐字节相同**（老用户已勾的条目照旧生效）。
     */
    private val CHECK_KEY_REMAP = mapOf(
        bundleCheckKey(3, 2, "史莱姆泥") to bundleCheckKey(3, 2, "黏液"),
    )

    /**
     * v2.39 作物改名迁移。
     *
     * 游戏本地化里这个作物叫「金皮西葫芦」，App 原先叫「夏南瓜」（沿用了另一套译名）。
     * 群友 @Manchester / @王忠明 / @剑仙 三人在群里为此吵了一轮，最终按游戏原名改名，
     * 并在括号里保留旧名便于搜索：`金皮西葫芦（夏南瓜）`——图鉴搜索是**子串**匹配，
     * 所以搜「金皮西葫芦」和搜「夏南瓜」都能命中，两个叫法都不丢。
     *
     * 与 v2.27 同理：键是按名字拼的（`codex:crops:夏南瓜`），改名后老存档里这一项会
     * **静默变回未勾**，必须落盘重写一次。
     */
    private val CROP_RENAME_REMAP = mapOf("codex:crops:夏南瓜" to "codex:crops:金皮西葫芦（夏南瓜）")

    /**
     * v2.82 作物改名迁移（**第二条**作物改名迁移，与 v2.39 各走各的闸门）。
     *
     * v2.39 那次只改了一个（夏南瓜 → 金皮西葫芦（夏南瓜）），闸门 `CHECK_KEYS_V239`
     * 已经写进老用户的数据里，**不会再跑第二次** —— 所以这四条必须另开一个闸门。
     *
     * 这四条是**从英文直译出来的自造名**，不是社区里在用的叫法：
     *   盛夏疯狂 Summer Spangle → 夏季亮片 ｜ 红甘蓝 Red Cabbage → 红叶卷心菜
     *   朝鲜蓟 Artichoke → 洋蓟   ｜ 粉瓜 Powdermelon → 霜瓜
     * 群友 @王忠明 2026-10-22 在群里报了四条（同一个群友，上次「夏南瓜」那次也是他拍的板）。
     *
     * 与 v2.39 同理：键是按名字拼的（`codex:crops:盛夏疯狂`），改名后老存档里这一项
     * 会**静默变回未勾**，必须落盘重写一次。
     */
    private val CROP_RENAME_REMAP_V282 = mapOf(
        "codex:crops:盛夏疯狂" to "codex:crops:夏季亮片",
        "codex:crops:红甘蓝" to "codex:crops:红叶卷心菜",
        "codex:crops:朝鲜蓟" to "codex:crops:洋蓟",
        "codex:crops:粉瓜" to "codex:crops:霜瓜",
    )

    private fun dateKey(n: String) = stringPreferencesKey("d:$n")
    private fun prof5Key(n: String) = stringPreferencesKey("pf5:$n")
    private fun prof10Key(n: String) = stringPreferencesKey("pf10:$n")
    private fun checksKey(n: String) = stringSetPreferencesKey("ck:$n")

    /**
     * v2.74.0「农场年度报告」统计，**按存档位存**。
     *
     * 为什么按存档位：报告讲的是「这局游戏玩成什么样」，换存档位就该换一份报告。
     * 全局存就变成了「只保留最后一次导入的那份」，用户导入第二份存档后
     * 第一份的报告就没了。
     *
     * 存的是**派生结果**（几十个数字的 JSON，约 1KB），不是原始存档（35MB）。
     */
    private fun reportKey(n: String) = stringPreferencesKey("rp:$n")

    data class Snapshot(
        val year: Int, val season: Int, val day: Int,
        val prof5: String, val prof10: String, val theme: String,
        val checks: Set<String>,
        val profiles: List<String>, val activeProfile: String,
        val introSeen: Boolean,
        // v2.29 图鉴导航记忆（全局，不按存档分）
        val codexRecent: List<CodexRecentRef> = emptyList(),
        val codexLastGroup: String = "",
        // v2.57 自定义今日待办（全局清单；勾选仍按存档+日期+文本键）
        val todoList: List<String> = TODO_DEFAULT,
        // v2.59 字号档位下标（0/1/2，倍率单源在 FontScale.SCALES）。默认 0 = 1×，
        // 与 v2.58 及更早的锁 1× 行为逐像素一致。
        val fontScaleIdx: Int = 0,
        // v2.93.0 字体档（pixel / system / custom，单源在 ui.AppFont）。默认 pixel = 像素字体，
        // 与 v2.92.0 及更早的观感逐像素一致；system 与 custom 只影响 App 内 UI。
        val fontFamily: String = AppFont.PIXEL,
        // v2.95.0 自定义字体 id（内容哈希，对应 filesDir/fonts/<id>.ttf）。空串 = 没导入过。
        val fontCustomId: String = "",
        // v2.61 自动更新提示（设备态，不进备份）
        val updateCheckedAt: Long = 0L,
        val updateSeenVersion: Int = 0,
        val updatePromptEnabled: Boolean = true,
        // v2.69 已发现的新版本快照 + 这一版的弹窗弹过没有（同样设备态，不进备份）
        val updatePendingJson: String = "",
        val updateDialogVersion: Int = 0,
        // v2.70.0 底栏样式（设备态，不进备份；默认玻璃）
        val glassBar: Boolean = true,
        // c228 玻璃浓度档（设备态，不进备份）：0=轻 / 1=标准 / 2=浓，默认标准。
        val glassLevel: Int = 1,
        // c207 图鉴勾选锁定（设备态，不进备份）：锁上后格子右上角的勾选角标只显示、点了不记录。
        //   默认 false = 不锁，与既有行为完全一致。勾选数据（checks）与备份一概不动。
        val codexMarkLocked: Boolean = false,
        // c215 音效开关（设备态，不进备份）：关掉后勾选/翻页/完成等 UI 音效不播（触觉不受影响）。
        //   默认 true = 播，与既有行为完全一致。
        val soundEnabled: Boolean = true,
        // c233 局域网接收「后台常驻」开关（设备态，不进备份）：开 = 接收会话跑在前台服务里，
        //   App 退到后台/息屏也不被系统杀掉。默认 false = 与 c232 行为逐字一致（无常驻通知）。
        val lanForeground: Boolean = false,
        // v2.71 特别关心的村民名集合（全局，进备份）
        val followed: Set<String> = emptySet(),
        // v2.72 Mod 扩展包（设备态，不进备份）
        val modIndexUrl: String = ModRepo.DEFAULT_INDEX_URL,
        val modEnabled: Set<String> = emptySet(),
        val modInstalled: Set<String> = emptySet(),
        // v2.72.2 计算页输入条件（全局，进备份）。season/day 未落过盘时回落到当前存档的日期
        val calc: CalcSetup = CalcSetup(),
        // v2.92.0 计算页的**块数**（**按存档**，进备份）：这个存档的农场有多大
        val calcPlots: CalcPlots = CalcPlots(),
        // v2.74.0 农场年度报告统计（**按存档位**，进备份：它是用户辛苦玩出来的成果）
        val report: FarmReport? = null,
    )

    /** 解析最近查看列表。坏数据一律退化成空列表——一条脏记录不该让 App 起不来。 */
    private fun parseRecent(raw: String?): List<CodexRecentRef> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val g = o.optString("g")
                val n = o.optString("n")
                if (g.isBlank() || n.isBlank()) null else CodexRecentRef(g, n)
            }
        }.getOrDefault(emptyList())
    }

    /** 解析自定义待办清单。未设置/坏数据退化成默认清单；空列表是合法用户状态（全删）。 */
    private fun parseTodoList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return TODO_DEFAULT
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(TODO_DEFAULT)
    }

    /**
     * v2.72.2 解析计算页输入条件。
     *
     * **未落过盘时不写季节/日期，而是回落到当前存档的日期**（`defSeason` / `defDay`）——
     * 沿用改之前「首次进计算页显示存档当天」的行为。回落发生在**读处**，写处只记
     * 用户真的动过的值，于是「从没调过」和「调过又调回」在盘上是同一份数据。
     *
     * 越界一律钳回（导入别人手改的备份 / 旧版本残留都可能带出脏值）：
     * mode∈[0,2]、season∈[0,3]、day∈[1,28]、plots∈[1,999]、quality∈QUALITY_OPTIONS。
     * 脏数据退化成默认而不是抛异常——一条坏记录不该让整个 App 起不来。
     */
    private fun parseCalc(raw: String?, defSeason: Int, defDay: Int): CalcSetup {
        val fallback = CalcSetup(
            season = defSeason.coerceIn(0, 3),
            day = defDay.coerceIn(1, 28),
        )
        if (raw.isNullOrBlank()) return fallback
        return runCatching {
            val o = JSONObject(raw)
            CalcSetup(
                mode = o.optInt("m").coerceIn(0, 2),
                season = o.optInt("s", fallback.season).coerceIn(0, 3),
                day = o.optInt("d", fallback.day).coerceIn(1, 28),
                quality = o.optInt("q").coerceIn(0, Calc.QUALITY_OPTIONS.lastIndex),
            )
        }.getOrDefault(fallback)
    }

    /**
     * v2.92.0：解析某个存档的**块数**。
     *
     * 越界一律钳回（导入别人手改的备份 / 旧版本残留都可能带出脏值）：
     * plots∈[1,999]。脏数据退化成默认而不是抛异常——一条坏记录不该让整个 App 起不来。
     */
    private fun parseCalcPlots(raw: String?): CalcPlots {
        if (raw.isNullOrBlank()) return CalcPlots()
        return runCatching {
            val o = JSONObject(raw)
            CalcPlots(
                outdoor = o.optInt("p0", 10).coerceIn(1, 999),
                greenhouse = o.optInt("p1", 10).coerceIn(1, 999),
                island = o.optInt("p2", 10).coerceIn(1, 999),
            )
        }.getOrDefault(CalcPlots())
    }

    /** [CalcSetup] → JSON 字符串。与 [parseCalc] 成对，改一处必须改另一处（verify 67.1 钉）。 */
    private fun encodeCalc(s: CalcSetup): String = JSONObject()
        .put("m", s.mode.coerceIn(0, 2))
        .put("s", s.season.coerceIn(0, 3))
        .put("d", s.day.coerceIn(1, 28))
        .put("q", s.quality.coerceIn(0, Calc.QUALITY_OPTIONS.lastIndex))
        .toString()

    /**
     * v2.92.0：[CalcPlots] → JSON 字符串。与 [parseCalcPlots] 成对（verify 67.1 钉）。
     *
     * ⚠ 键名沿用旧的 `p0`/`p1`/`p2` —— 旧备份的 `calc_setup` 里就是这三个键，
     *   导入时能直接取过来当初始块数（迁移路径见 [importJson]）。
     */
    private fun encodeCalcPlots(p: CalcPlots): String = JSONObject()
        .put("p0", p.outdoor.coerceIn(1, 999))
        .put("p1", p.greenhouse.coerceIn(1, 999))
        .put("p2", p.island.coerceIn(1, 999))
        .toString()

    val snapshot: Flow<Snapshot> = context.dataStore.data.map { p ->
        val migrated = p[PROFILES] != null
        val names = if (migrated) parseNames(p[PROFILES]!!) else listOf(DEFAULT_PROFILE)
        var active = if (migrated) p[ACTIVE] ?: DEFAULT_PROFILE else DEFAULT_PROFILE
        if (active !in names) active = names.first()
        val prof = active
        var year = 1; var season = 0; var day = 1
        var prof5 = ""; var prof10 = ""; var checks: Set<String> = emptySet()
        if (migrated) {
            p[dateKey(prof)]?.split("|")?.let {
                // v2.17 C3：导入/手改备份可能带出越界值（season=7 → Season.names 下标崩），统一钳制
                if (it.size == 3) {
                    year = (it[0].toIntOrNull() ?: 1).coerceAtLeast(1)
                    season = (it[1].toIntOrNull() ?: 0).coerceIn(0, 3)
                    day = (it[2].toIntOrNull() ?: 1).coerceIn(1, 28)
                }
            }
            prof5 = p[prof5Key(prof)] ?: ""
            prof10 = p[prof10Key(prof)] ?: ""
            checks = p[checksKey(prof)] ?: emptySet()
        } else {
            // 迁移尚未完成时直接读旧键，保证过渡期数据可见
            year = (p[LEGACY_YEAR] ?: 1).coerceAtLeast(1)
            season = (p[LEGACY_SEASON] ?: 0).coerceIn(0, 3)
            day = (p[LEGACY_DAY] ?: 1).coerceIn(1, 28)
            prof5 = p[LEGACY_PROF5] ?: ""; prof10 = p[LEGACY_PROF10] ?: ""
            checks = p[LEGACY_CHECKS] ?: emptySet()
        }
        Snapshot(
            year, season, day, prof5, prof10, p[THEME] ?: ThemeMode.SYSTEM, checks, names, active,
            p[INTRO] ?: false,
            parseRecent(p[CODEX_RECENT]),
            p[CODEX_LAST_GROUP] ?: "",
            // 未设置过=默认清单；用户删空后存的是空列表（两者要能区分，别用 isNullOrEmpty）
            parseTodoList(p[TODO_LIST]),
            // v2.59 字号档位：越界/脏值一律钳回 0（标准档 = v2.58 及更早的锁 1× 行为）
            (p[FONT_SCALE] ?: 0).coerceIn(0, 2),
            // v2.93.0 字体档 / v2.95.0 加第三档 custom。三值守卫：**只有恰好 system / custom 才认**，
            // 其余（脏值 / 老版本没这键）一律像素字体（脏值不许把整 App 的字体换掉）。
            //
            // ⚠️ custom **还要本地真有那个字体文件**才算数：字体文件不进 JSON 备份，
            //   换机导入备份后文件不在 ⇒ 回落像素字体，绝不停在「选中了一个不存在的字体」。
            //   这条与写处（setFontFamily）是同一把尺。
            resolveFontFamily(
                p[FONT_FAMILY],
                p[FONT_CUSTOM] ?: "",
                CustomFont.exists(context, p[FONT_CUSTOM] ?: ""),
            ),
            // v2.95.0 自定义字体 id（内容哈希）。**不导出进备份**（字体文件也不带，
            // 只带 id 换机后必然失效，还会让人误以为字体跟着走了）。
            p[FONT_CUSTOM] ?: "",
            // v2.61 更新提示状态。负数时间戳（手改备份/时钟回拨）一律当 0 = 从未检查，
            // 否则「现在 - 未来时间」是负数，会让节流判定误以为刚查过而永远不再查。
            (p[UPD_AT] ?: 0L).coerceAtLeast(0L),
            (p[UPD_SEEN] ?: 0).coerceAtLeast(0),
            p[UPD_ON] ?: true,
            // v2.69 待提示的新版本快照。脏值不在这里判——反序列化归 UpdateRepo.decodeLatest
            // （PrefsRepo 不该知道 Latest 的形状），它在那边 runCatching 成 null 当"没有"。
            p[UPD_PENDING] ?: "",
            (p[UPD_DIALOG] ?: 0).coerceAtLeast(0),
        // v2.70.0 底栏样式开关（默认玻璃；能力门槛在 UI 侧判，这里只存偏好）
        p[GLASS_BAR] ?: true,
        // c228 玻璃浓度档（0=轻/1=标准/2=浓；默认标准 = 既有观感）
        (p[GLASS_LEVEL] ?: 1).coerceIn(0, 2),
            // c207 图鉴勾选锁定（默认不锁 = 既有行为）
            p[CODEX_MARK_LOCKED] ?: false,
            // c215 音效开关（默认开 = 既有行为）
            p[SOUND_ENABLED] ?: true,
            // c233 后台常驻（默认关 = c232 行为一字不变：不想要常驻通知的用户没变化）
            p[LAN_FGS] ?: false,
            // v2.71 特别关心（默认空集合 = 一个都没标）
            p[FOLLOWED] ?: emptySet(),
            // v2.72 Mod 扩展包：URL 没设过就用 [ModRepo.DEFAULT_INDEX_URL] 兜底，
            // 省掉一次迁移——「默认值」在读处兜，写处只记用户真的改过的那个值。
            p[MOD_INDEX_URL] ?: ModRepo.DEFAULT_INDEX_URL,
            p[MOD_ENABLED] ?: emptySet(),
            p[MOD_INSTALLED] ?: emptySet(),
            // v2.72.2：season/day 的默认值取**本次快照里刚解析出来的存档日期**，
            // 所以这一行必须排在上面 season/day 赋值之后（顺序即依赖）。
            parseCalc(p[CALC], season, day),
            // v2.92.0：块数**按存档**（cp:<存档名>），不再跟全局那份走
            parseCalcPlots(p[calcPlotsKey(prof)]),
            // v2.74.0 农场报告（坏数据退化成 null，parseReport 内部已兜）
            parseReport(p[reportKey(prof)]),
        )
    }

    /** 读农场报告。坏数据一律 null（一条脏记录不该让 App 起不来）。 */
    private fun parseReport(raw: String?): FarmReport? = FarmReport.fromJson(raw)

    /**
     * v2.95.0：字体档的**唯一判定口径**（读处与写处共用同一个函数）。
     *
     * ## 为什么单独抽一个函数
     * 「哪些落盘值算合法」这个规则必须只有**一处**定义。读处（快照解析）与写处
     * （[setFontFamily]）各写一遍，迟早会漂移——一边认 custom 一边不认，
     * 表现就是「界面上选着自定义字体，实际显示的是像素字体」这种查不出来的毛病。
     *
     * ## 三条规则
     * ① `system` 一律认（系统字体随时可用，不需要任何文件）。
     * ② `custom` **还要本地真有那个字体文件**才认 —— 换机导入备份时字体文件不在
     *    （字体文件不进 JSON 备份）⇒ 回落像素字体。
     * ③ 其余（脏值 / 老版本没这键 / null）一律像素字体。
     */
    private fun resolveFontFamily(raw: String?, customId: String, customExists: Boolean): String =
        when {
            raw == AppFont.SYSTEM -> AppFont.SYSTEM
            raw == AppFont.CUSTOM && customId.isNotBlank() && customExists -> AppFont.CUSTOM
            else -> AppFont.PIXEL
        }

    /**
     * v2.74.0：写某存档位的农场报告。传 null = 清掉这份报告。
     *
     * 与打勾导入一样走「用户确认后才落盘」：导入确认的抽屉里点「确定导入」才写这里
     * （见 SettingsPage）。所以在确认之前，一个字节都不会落盘。
     */
    suspend fun setReport(name: String, report: FarmReport?) {
        context.dataStore.edit { p ->
            if (report == null) p.remove(reportKey(name)) else p[reportKey(name)] = report.toJson()
        }
    }

    /** 读某存档位的农场报告。 */
    suspend fun reportOf(name: String): FarmReport? =
        parseReport(context.dataStore.data.first()[reportKey(name)])

    // ── v2.57 自定义今日待办 ──

    /** 覆盖全局待办清单。空列表合法（用户删掉全部，今日页显示空态文案）。 */
    suspend fun setTodoList(list: List<String>) {
        val cleaned = list.map { it.trim() }.filter { it.isNotEmpty() }
        context.dataStore.edit { it[TODO_LIST] = JSONArray(cleaned).toString() }
    }

    /**
     * v2.57：待办勾选键从「todo:年:季:日:下标」迁移为「…:文本」（幂等，一次性）。
     *
     * 自定义列表让下标失去意义——删一行会让后面所有行的勾选错位继承。
     * 旧键末段是 0..5 的纯数字且前缀形如 todo:Y:S:D 才转换（文本键理论上可含半角冒号，
     * 但旧键产生于只有默认 6 条文本的时期，末段数字判定足够安全）。
     */
    suspend fun migrateTodoKeys() {
        context.dataStore.edit { p ->
            if (p[TODO_KEYS_V257] == true) return@edit
            if (p[PROFILES] != null) {
                for (name in parseNames(p[PROFILES]!!)) {
                    val ck = checksKey(name)
                    val cur = p[ck] ?: continue
                    var changed = false
                    val next = cur.map { k ->
                        val idx = k.lastIndexOf(':')
                        val head = k.substring(0, idx)
                        val tail = k.substring(idx + 1)
                        val i = tail.toIntOrNull()
                        if (i != null && i in TODO_DEFAULT.indices &&
                            Regex("^todo:\\d+:\\d+:\\d+$").matches(head)
                        ) {
                            changed = true
                            "$head:${TODO_DEFAULT[i]}"
                        } else k
                    }.toSet()
                    if (changed) p[ck] = next
                }
            }
            p[TODO_KEYS_V257] = true
        }
    }

    // ── v2.29 图鉴导航记忆 ──

    /** 记住上次看的分类。存 groupId 而非下标：下标会随数据包更新漂移。 */
    suspend fun setLastCodexGroup(groupId: String) {
        if (groupId.isBlank()) return
        context.dataStore.edit { it[CODEX_LAST_GROUP] = groupId }
    }

    /**
     * 记一条最近查看：同一条重复查看时**移到最前**而不是新增一条，
     * 否则连翻十次同一个条目会把最近列表填满同一样东西。
     */
    suspend fun recordRecent(groupId: String, itemName: String) {
        if (groupId.isBlank() || itemName.isBlank()) return
        context.dataStore.edit { p ->
            val cur = parseRecent(p[CODEX_RECENT]).filterNot { it.groupId == groupId && it.itemName == itemName }
            val next = listOf(CodexRecentRef(groupId, itemName)) + cur
            val trimmed = next.take(CODEX_RECENT_LIMIT)
            p[CODEX_RECENT] = JSONArray().let { arr ->
                trimmed.forEach { arr.put(JSONObject().put("g", it.groupId).put("n", it.itemName)) }
                arr
            }.toString()
        }
    }

    suspend fun ensureMigrated() {
        val p = context.dataStore.data.first()
        if (p[PROFILES] != null) return
        context.dataStore.edit {
            it[PROFILES] = saveNames(listOf(DEFAULT_PROFILE))
            it[ACTIVE] = DEFAULT_PROFILE
            it[dateKey(DEFAULT_PROFILE)] = "${p[LEGACY_YEAR] ?: 1}|${p[LEGACY_SEASON] ?: 0}|${p[LEGACY_DAY] ?: 1}"
            it[prof5Key(DEFAULT_PROFILE)] = p[LEGACY_PROF5] ?: ""
            it[prof10Key(DEFAULT_PROFILE)] = p[LEGACY_PROF10] ?: ""
            it[checksKey(DEFAULT_PROFILE)] = p[LEGACY_CHECKS] ?: emptySet()
        }
    }

    /**
     * v2.92.0：计算页块数从「全局一份」拆到「按存档」，做**一次性迁移**。
     *
     * ## 为什么必须迁
     * 升级前用户拨好的块数在全局 `calc_setup` 的 p0/p1/p2 里。拆分后新键
     * `cp:<存档名>` 是空的 → 块数回到默认 10，用户得重新拨三下。
     * 迁移把那三个值复制给**每个存档**（老数据本来就只有一份，分不出是谁的）。
     *
     * ## 幂等
     * 只要有任一存档已有 `cp:` 键，就认为迁过了（用户已经按新口径调过），
     * 不再覆盖 —— 否则用户在新口径下调的块数会被老值盖回去。
     */
    suspend fun migrateCalcPlots() {
        context.dataStore.edit { p ->
            val names = parseNames(p[PROFILES] ?: return@edit)
            if (names.isEmpty()) return@edit
            if (names.any { p.contains(calcPlotsKey(it)) }) return@edit
            val legacy = p[CALC]?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@edit
            if (!legacy.has("p0") && !legacy.has("p1") && !legacy.has("p2")) return@edit
            val plots = encodeCalcPlots(
                CalcPlots(
                    outdoor = legacy.optInt("p0", 10),
                    greenhouse = legacy.optInt("p1", 10),
                    island = legacy.optInt("p2", 10),
                )
            )
            names.forEach { p[calcPlotsKey(it)] = plots }
        }
    }

    /**
     * v2.27：勾选键改名迁移（幂等，一次性）。
     *
     * 对**所有**存档逐个把 CHECK_KEY_REMAP 里的旧键换成新键。不依赖具体存档名，
     * 玩家建的每个存档都要搬。标记键无论改没改动都置位，避免每次启动都进 edit 块。
     */
    suspend fun migrateCheckKeys() {
        context.dataStore.edit { p ->
            if (p[CHECK_KEYS_V227] == true) return@edit
            if (p[PROFILES] != null) {
                for (name in parseNames(p[PROFILES]!!)) {
                    val ck = checksKey(name)
                    val cur = p[ck] ?: continue
                    if (cur.none { it in CHECK_KEY_REMAP }) continue
                    p[ck] = cur.map { CHECK_KEY_REMAP[it] ?: it }.toSet()
                }
            }
            p[CHECK_KEYS_V227] = true
        }
    }

    /**
     * v2.39：作物改名迁移（幂等，一次性）。
     *
     * 与 [migrateCheckKeys] 同款写法，**必须落盘重写**——只读映射下用户取消勾选时
     * 旧键还在集合里，下次读又映射回来，等于取消不掉（v2.27 踩过）。
     * 同样遍历**所有**存档：玩家自建的存档也要搬，不只是当前活跃那个。
     */
    suspend fun migrateCropRename() {
        context.dataStore.edit { p ->
            if (p[CHECK_KEYS_V239] == true) return@edit
            if (p[PROFILES] != null) {
                for (name in parseNames(p[PROFILES]!!)) {
                    val ck = checksKey(name)
                    val cur = p[ck] ?: continue
                    if (cur.none { it in CROP_RENAME_REMAP }) continue
                    p[ck] = cur.map { CROP_RENAME_REMAP[it] ?: it }.toSet()
                }
            }
            p[CHECK_KEYS_V239] = true
        }
    }

    /**
     * v2.82 作物改名后的勾选键重写（盛夏疯狂/红甘蓝/朝鲜蓟/粉瓜 → 官方译名）。
     *
     * 与 [migrateCropRename] 同款写法，**必须落盘重写**——只读映射下用户取消勾选时
     * 旧键还在集合里，下次读又映射回来，等于取消不掉（v2.27 踩过）。
     * 同样遍历**所有**存档：玩家自建的存档也要搬，不只是当前活跃那个。
     */
    suspend fun migrateCropRenameV282() {
        context.dataStore.edit { p ->
            if (p[CHECK_KEYS_V282] == true) return@edit
            if (p[PROFILES] != null) {
                for (name in parseNames(p[PROFILES]!!)) {
                    val ck = checksKey(name)
                    val cur = p[ck] ?: continue
                    if (cur.none { it in CROP_RENAME_REMAP_V282 }) continue
                    p[ck] = cur.map { CROP_RENAME_REMAP_V282[it] ?: it }.toSet()
                }
            }
            p[CHECK_KEYS_V282] = true
        }
    }

    suspend fun current(): Snapshot = snapshot.first()

    private suspend fun activeName(): String = current().activeProfile

    suspend fun setDate(year: Int, season: Int, day: Int) {
        val prof = activeName()
        context.dataStore.edit { it[dateKey(prof)] = "$year|$season|$day" }
    }

    suspend fun setProfession(prof5: String, prof10: String) {
        val prof = activeName()
        context.dataStore.edit { p -> p[prof5Key(prof)] = prof5; p[prof10Key(prof)] = prof10 }
    }

    suspend fun setTheme(mode: String) {
        context.dataStore.edit { it[THEME] = mode }
    }

    /** v2.59 设置字号档位（0=标准/1=大/2=特大）。越界钳回 0，绝不让脏值把字体放到失控倍率。 */
    suspend fun setFontScale(idx: Int) {
        context.dataStore.edit { it[FONT_SCALE] = idx.coerceIn(0, 2) }
    }

    /**
     * v2.93.0 设置字体档（AppFont.PIXEL / AppFont.SYSTEM / AppFont.CUSTOM）。
     *
     * 写处与读处**共用同一把尺**（[resolveFontFamily]）：只认 system；custom 还得
     * 本地真有那个字体文件；其余一律像素 —— 脏值不许把整 App 的字体换掉。
     * **全局键**：字体是设备上的阅读偏好，跟玩到第几个存档无关。
     *
     * ⚠️ 选 custom 前必须先把字体文件落好（[CustomFont.importFont] 成功后
     *   先 [setFontCustomId] 再调这里），否则这里会把它落回像素。
     */
    suspend fun setFontFamily(f: String) {
        context.dataStore.edit {
            val curId = it[FONT_CUSTOM] ?: ""
            it[FONT_FAMILY] = resolveFontFamily(
                f,
                curId,
                CustomFont.exists(context, curId),
            )
        }
    }

    /**
     * v2.95.0：记下当前用的是哪个自定义字体（内容哈希 id）。
     *
     * 只写 id，不写字体文件（文件在 filesDir/fonts/<id>.ttf）。
     * 传空串 = 清掉（删除字体后调用）。
     */
    suspend fun setFontCustomId(id: String) {
        context.dataStore.edit { it[FONT_CUSTOM] = id }
    }

    // ── v2.61 自动更新提示 ──

    /**
     * 记一次更新检查（**成功与失败都记**）。
     *
     * v2.73.3 起「该不该查」由 [com.stardew.assistant.data.UpdateRepo.shouldCheck]
     * 的**进程级去重**判定，本字段只作「上次检查时间」的记录，不参与判定——
     * 读侧的 `coerceAtLeast(0L)` 钳制仍保留（时钟回拨的手改值照样挡在读侧）。
     */
    suspend fun markUpdateChecked(at: Long) {
        context.dataStore.edit { it[UPD_AT] = at.coerceAtLeast(0L) }
    }

    /** 用户划掉横幅：记住关掉的是**哪个版本**，下个版本还会再提示。 */
    suspend fun markUpdateSeen(versionCode: Int) {
        context.dataStore.edit { it[UPD_SEEN] = versionCode.coerceAtLeast(0) }
    }

    /** 「更新提示」总开关（默认开）。关掉后连每次开 App 的那次检查都不做，App 回到纯离线。 */
    suspend fun setUpdatePrompt(on: Boolean) {
        context.dataStore.edit { it[UPD_ON] = on }
    }

    /**
     * v2.69：把「已发现的新版本」快照落盘，让横幅跨进程存活。
     *
     * 传空串 = 清除。清除的两种时机：
     * ① 用户点 ✕（配合 [markUpdateSeen]，一个记版本门槛一个清数据，缺一个下个版本会出错）；
     * ② 启动时发现 `pending.versionCode <= 当前版本` —— 用户已经装上新版了，自动自愈，
     *    不需要额外的「我已更新」状态。
     */
    suspend fun setUpdatePending(json: String) {
        context.dataStore.edit { it[UPD_PENDING] = json }
    }

    /**
     * v2.69：记下「这一版的启动弹窗已经打断过了」。
     *
     * ⚠ 点「稍后」只调这里，**不调** [markUpdateSeen]——后者是用户主动划掉横幅的语义。
     * 混用会让「稍后」变成永久屏蔽，横幅就此消失，那提示就白做了。
     */
    suspend fun markUpdateDialogShown(versionCode: Int) {
        context.dataStore.edit { it[UPD_DIALOG] = versionCode.coerceAtLeast(0) }
    }

    /** v2.70.0 底栏样式开关：true = 液态玻璃胶囊，false = 经典木纹栏。 */
    suspend fun setGlassBar(on: Boolean) {
        context.dataStore.edit { it[GLASS_BAR] = on }
    }

    /** c228 玻璃浓度档：0=轻 / 1=标准 / 2=浓（越界钳到合法档，与读处同一把尺）。 */
    suspend fun setGlassLevel(level: Int) {
        context.dataStore.edit { it[GLASS_LEVEL] = level.coerceIn(0, 2) }
    }

    /** c215 音效开关：true = 播 UI 音效（勾选/完成/翻页/错误/点击），false = 全部静音（触觉不受影响）。 */
    suspend fun setSoundEnabled(on: Boolean) {
        context.dataStore.edit { it[SOUND_ENABLED] = on }
        // 同步进程级标志：PixelSounds.play 是非组合上下文（普通类），读不了 DataStore 流，
        // 走这个进程级单例做即时开关（设置页改完下一次播放就生效，不用重启）。
        com.stardew.assistant.ui.PixelSounds.enabled = on
    }

    /**
     * c233 局域网接收「后台常驻」开关：true = 接收会话由前台服务保活。
     *
     * 默认关（设备态偏好，不进备份）：这是**加一项可选能力**，不是改既有行为——
     * 关着的时候接收流程与 c232 逐字一致，也不出现任何常驻通知。
     */
    suspend fun setLanForeground(on: Boolean) {
        context.dataStore.edit { it[LAN_FGS] = on }
    }

    /**
     * c207 图鉴勾选锁定：true = 锁上，图鉴格子右上角的勾选角标只显示、点了不记录（防手滑误勾）。
     * 只锁「标记」这个动作，已勾的记录照常显示，进度页打勾不受影响。
     */
    suspend fun setCodexMarkLocked(on: Boolean) {
        context.dataStore.edit { it[CODEX_MARK_LOCKED] = on }
    }

    /**
     * v2.72 记 Mod 仓库地址（index.json 或单包 .zip 直链）。
     *
     * 空串是合法值——用户把地址清空就是「不填」，取包时报「还没有填仓库地址」，
     * 这比悄悄退回默认地址诚实（他可能正在排查「为什么连的不是我填的那个」）。
     */
    suspend fun setModIndexUrl(url: String) {
        context.dataStore.edit { it[MOD_INDEX_URL] = url.trim() }
    }

    /** v2.72：整份替换启用集合。**不是** toggle——装包/删包要一次性把两者对齐。 */
    suspend fun setModEnabled(ids: Set<String>) {
        context.dataStore.edit { it[MOD_ENABLED] = ids.filter { it.isNotBlank() }.toSet() }
    }

    /** v2.72：整份替换已装集合。 */
    suspend fun setModInstalled(ids: Set<String>) {
        context.dataStore.edit { it[MOD_INSTALLED] = ids.filter { it.isNotBlank() }.toSet() }
    }

    /**
     * v2.72.2 记下计算页当前这组输入条件（整份替换，不是增量）。
     *
     * 整份替换而不是「改哪个字段写哪个键」：三块地的块数 + 季节日期 + 品质本就是
     * 一组参数，增量写法迟早出现「盘上季节是新的、块数是旧的」这种半新半旧状态。
     * 写前钳制，读时（[parseCalc]）再钳一次——两层都留着，因为备份能绕过写侧。
     */
    suspend fun setCalcSetup(s: CalcSetup) {
        context.dataStore.edit { it[CALC] = encodeCalc(s) }
    }

    /**
     * v2.92.0：记下**当前存档**的计算页块数。
     *
     * ⚠ 写的是 `cp:<当前存档名>`，不是全局那份 —— 块数是**这个存档的事实**
     *   （用户 2026-10-25 报：不同存档之间块数没隔离，切过去算出的净利是错的）。
     */
    suspend fun setCalcPlots(p: CalcPlots) {
        val prof = activeName()
        context.dataStore.edit { it[calcPlotsKey(prof)] = encodeCalcPlots(p) }
    }

    /**
     * v2.71 切换某村民的「特别关心」。点一次标上，再点一次取消（与 toggleCheck 同款语义）。
     * 名字空白直接忽略——不存无名标记。
     */
    suspend fun toggleFollowed(name: String) {
        if (name.isBlank()) return
        context.dataStore.edit { p ->
            val cur = p[FOLLOWED] ?: emptySet()
            p[FOLLOWED] = if (name in cur) cur - name else cur + name
        }
    }

    suspend fun setIntroSeen(seen: Boolean = true) {
        context.dataStore.edit { it[INTRO] = seen }
    }

    /**
     * c203：把**外观类**偏好恢复默认（主题 / 字体档 / 字号 / 底栏样式）。
     *
     * ## 为什么只恢复这四样
     * 「恢复默认」最怕两件事：① 把用户的游戏进度一起清了（收集包勾选、待办、
     * 存档日期）——那是不可逆的损失；② 偷偷删用户自己的文件（自定义 TTF）。
     * 所以这里**只动阅读/外观偏好**，进度一概不碰，自定义字体文件也只把**档位**
     * 切回像素（文件留在盘上，用户想用还能在设置页重新选中）。
     *
     * 设备态的玻璃栏（[GLASS_BAR]）也不动：它能不能开取决于本机 API（33+），
     * 「恢复默认」把它写成 true 反而可能得到一个无效开关。
     * c228 玻璃浓度档（[GLASS_LEVEL]）同族不动：浓度是观感偏好不是阅读偏好，
     * 且「恢复默认」的既定口径就是「玻璃设置一概不碰」。
     */
    suspend fun resetAppearance() {
        context.dataStore.edit {
            it[THEME] = ThemeMode.SYSTEM
            it[FONT_SCALE] = 0
            it[FONT_FAMILY] = AppFont.PIXEL
            it[FONT_CUSTOM] = ""
        }
    }

    suspend fun toggleCheck(key: String) {
        val ck = checksKey(activeName())
        context.dataStore.edit { p ->
            val cur = p[ck] ?: emptySet()
            p[ck] = if (key in cur) cur - key else cur + key
        }
    }

    suspend fun replaceChecks(keys: Set<String>) {
        val prof = activeName()
        context.dataStore.edit { it[checksKey(prof)] = keys }
    }

    // ── 存档管理 ──

    suspend fun switchProfile(name: String) {
        context.dataStore.edit { p ->
            if (name in parseNames(p[PROFILES] ?: "")) p[ACTIVE] = name
        }
        // c217：切档时丢掉待落盘的农场数据 —— 那份数据属于**上一个**存档。
        //   不丢的话用户切档后点「确定导入」，pending 里的存档名对不上（commit 会拒），
        //   表现成「导入成功但农场没更新」；丢掉更干脆（重新导入即可）。
        FarmTodayCache.discardPending()
    }

    /** 新建空白存档「存档N」并切换过去，返回新名字；已达上限返回 null。
     *  v2.17 C2：MAX_PROFILES 此前只在 UI 隐藏按钮，仓储层零约束（导入可越界后按钮永久消失） */
    suspend fun addProfile(): String? {
        val cur = current()
        if (cur.profiles.size >= MAX_PROFILES) return null
        val n = (1..99).map { "存档$it" }.first { it != cur.activeProfile && it !in cur.profiles }
        context.dataStore.edit { p ->
            p[PROFILES] = saveNames(cur.profiles + n)
            p[dateKey(n)] = "1|0|1"
            p[prof5Key(n)] = ""
            p[prof10Key(n)] = ""
            p[ACTIVE] = n
        }
        return n
    }

    suspend fun renameProfile(old: String, newRaw: String) {
        val new = newRaw.trim().replace(":", "·").take(12)
        if (new.isEmpty() || new == old) return
        val cur = current()
        if (old !in cur.profiles || new in cur.profiles) return
        context.dataStore.edit { p ->
            p[checksKey(new)] = p[checksKey(old)] ?: emptySet()
            p[dateKey(new)] = p[dateKey(old)] ?: "1|0|1"
            p[prof5Key(new)] = p[prof5Key(old)] ?: ""
            p[prof10Key(new)] = p[prof10Key(old)] ?: ""
            p.remove(checksKey(old)); p.remove(dateKey(old)); p.remove(prof5Key(old)); p.remove(prof10Key(old))
            // v2.74.0：报告也是**这个存档的**成果，改名要跟着搬 —— 不搬的话
            //   改完名「农场年度报告」整块消失（存档位换了，报告还在旧位上）。
            //   ⚠ 只搬**真的存在**的那份：不搬空串（空串会被 parseReport 判成 null，
            //   但键留着就成了脏数据 —— 与 setReport 的 null→remove 口径一致）。
            val rep = p[reportKey(old)]
            if (rep != null) p[reportKey(new)] = rep
            p.remove(reportKey(old))
            // v2.92.0：块数同理（这个存档的农场有多大），改名跟着搬。
            val plots = p[calcPlotsKey(old)]
            if (plots != null) p[calcPlotsKey(new)] = plots
            p.remove(calcPlotsKey(old))
            p[PROFILES] = saveNames(cur.profiles.map { if (it == old) new else it })
            if (p[ACTIVE] == old) p[ACTIVE] = new
        }
        // c217：农场缓存与图鉴状态缓存也属于这个存档（它们是**从存档解析出来的**），
        //   改名不搬 ⇒ 改完名「我的农场」整块空着（读的是新名字那份文件，还没写）。
        FarmTodayCache.renameCache(context, old, new)
    }

    suspend fun deleteProfile(name: String) {
        val cur = current()
        if (cur.profiles.size <= 1 || name !in cur.profiles) return
        context.dataStore.edit { p ->
            p.remove(checksKey(name)); p.remove(dateKey(name)); p.remove(prof5Key(name)); p.remove(prof10Key(name))
            // v2.92.0：块数也属于这个存档，删存档要一起删（否则同名存档重建会捡到旧的块数）
            p.remove(calcPlotsKey(name))
            // v2.74.0：报告同理
            p.remove(reportKey(name))
            val rest = cur.profiles - name
            p[PROFILES] = saveNames(rest)
            if (p[ACTIVE] == name) p[ACTIVE] = rest.first()
        }
        // c217：农场缓存与图鉴状态缓存也一起删（留着的话，重建同名存档会捡到旧的农场）。
        FarmTodayCache.clear(context, name)
    }

    // ── 备份 v2：全部存档一起走 ──

    suspend fun exportJson(): String {
        val snap = current()
        val p = context.dataStore.data.first()
        val profs = JSONObject()
        snap.profiles.forEach { n ->
            profs.put(
                n, JSONObject()
                    .put("checks", JSONArray((p[checksKey(n)] ?: emptySet()).toList()))
                    .put("date", p[dateKey(n)] ?: "1|0|1")
                    .put("prof5", p[prof5Key(n)] ?: "")
                    .put("prof10", p[prof10Key(n)] ?: "")
                    // v2.74.0：农场报告同理——玩家玩出来的成果，换机丢了得重新导入存档
                    .put("report", p[reportKey(n)] ?: "")
                    // v2.92.0：块数**按存档**，跟着这个存档一起进备份
                    .put("calc_plots", p[calcPlotsKey(n)] ?: ""),
            )
        }
        return JSONObject()
            .put("app", "stardew_helper").put("version", 2)
            .put("profile_names", JSONArray(snap.profiles))
            .put("active", snap.activeProfile)
            .put("profiles", profs)
            .put("theme", snap.theme)
            // v2.59：字号档位与主题同类（阅读偏好），换机也要跟着走
            .put("font_scale", snap.fontScaleIdx)
            // v2.93.0：字体档同理（阅读偏好），换机也要跟着走
            // v2.95.0：⚠️ **custom 档不导出**——字体文件是几 MB 的二进制，进不了 JSON 备份，
            //   只带个 id 换机后必然失效，还会让人误以为字体跟着走了。
            //   导出时降级成 pixel（换机后重新导入字体即可），读处另有「文件存在才认 custom」的守卫。
            .put("font_family", if (snap.fontFamily == AppFont.CUSTOM) AppFont.PIXEL else snap.fontFamily)
            // v2.57：自定义待办清单是全局数据，导出必须带上（否则换机丢自定义）
            .put("todo_list", JSONArray(snap.todoList))
            // v2.71：特别关心名单同理——用户手动标的，换机丢了要重新标
            .put("followed_villagers", JSONArray(snap.followed.toList()))
            // v2.72.2：计算页参数同理——拨一次转盘要好几下，丢了得重拨
            .put("calc_setup", encodeCalc(snap.calc))
            .toString()
    }

    /** 导入备份，返回一句话结果摘要。
     *  v2 完整备份：同名存档以备份覆盖，本地不同名存档原样保留（并集，超 8 上限截断）。
     *  ⚠ v2.17 C1：旧实现先清空本地「全部」存档键再只写备份内的——手机上有存档1~3、
     *  备份只含存档1 时，2/3 连进度一起蒸发，且与预览文案「其他不受影响」直接矛盾。
     *  v1（旧单档案）导入进当前存档。 */
    suspend fun importJson(text: String): String {
        val o = JSONObject(text)
        if (o.has("profiles")) {
            val profs = o.getJSONObject("profiles")
            val backupNames = parseNames(o.optJSONArray("profile_names")?.toString() ?: "[]")
                .ifEmpty { profs.keys().asSequence().toList() }
            ensureMigrated()
            val localNames = parseNames(context.dataStore.data.first()[PROFILES] ?: "[]")
            val merged = (backupNames + localNames).distinct()
            val dropped = (merged.size - MAX_PROFILES).coerceAtLeast(0)
            val finalNames = merged.take(MAX_PROFILES)
            var overwritten = 0
            context.dataStore.edit { p ->
                // 只清「备份里也有」的同名存档的 4 类键（马上被备份数据覆盖）；本地其余存档不动
                backupNames.forEach { n ->
                    if (n in localNames) overwritten++
                    p.remove(checksKey(n)); p.remove(dateKey(n)); p.remove(prof5Key(n)); p.remove(prof10Key(n))
                    // v2.74.0：报告也跟着覆盖。备份里没有报告 = 这份存档没有报告（清掉旧的）
                    p.remove(reportKey(n))
                    // v2.92.0：块数同理（备份里没有 = 这份存档没设过块数）
                    p.remove(calcPlotsKey(n))
                }
                p[PROFILES] = saveNames(finalNames)
                val active = o.optString("active", finalNames.first())
                    .takeIf { it in finalNames } ?: finalNames.first()
                p[ACTIVE] = active
                backupNames.forEach { n ->
                    if (n !in finalNames) return@forEach // 超上限被截断的不落键
                    val po = profs.optJSONObject(n) ?: return@forEach
                    p[dateKey(n)] = po.optString("date", "1|0|1")
                    p[prof5Key(n)] = po.optString("prof5", "")
                    p[prof10Key(n)] = po.optString("prof10", "")
                    val a = po.optJSONArray("checks")
                    if (a != null) p[checksKey(n)] = (0 until a.length()).map { a.getString(it) }.toSet()
                    // v2.74.0：报告存原始串不解析——脏值由 parseReport 在读侧兜，
                    // 这里再解析一遍就是同一份校验写两处，迟早只改一处。
                    po.optString("report").takeIf { it.isNotBlank() }?.let { p[reportKey(n)] = it }
                    // v2.92.0：块数。旧备份没有 calc_plots 字段 → 从旧 calc_setup 里的
                    // p0/p1/p2 迁移过来（老备份只有**一份全局**块数，分不出是谁的，
                    // 就当作各存档的初始值 —— 宁可大家都用同一份，也别全丢）。
                    val legacyPlots = o.optString("calc_setup").takeIf { it.isNotBlank() }
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val plotsRaw = po.optString("calc_plots").takeIf { it.isNotBlank() }
                        ?: legacyPlots
                            ?.takeIf { it.has("p0") || it.has("p1") || it.has("p2") }
                            ?.let { lp ->
                                JSONObject()
                                    .put("p0", lp.optInt("p0", 10))
                                    .put("p1", lp.optInt("p1", 10))
                                    .put("p2", lp.optInt("p2", 10))
                                    .toString()
                            }
                    plotsRaw?.let { p[calcPlotsKey(n)] = it }
                }
                if (o.has("theme")) p[THEME] = o.getString("theme")
                // v2.59：旧备份没这个字段=不动本地字号；有则钳制后落盘（手改备份可能带 99）
                if (o.has("font_scale")) p[FONT_SCALE] = o.getInt("font_scale").coerceIn(0, 2)
                // v2.93.0：字体档同款守卫——旧备份没这个字段=不动本地字体；
                // v2.95.0：三值口径（pixel / system / custom），与读处同一把尺
                // （[resolveFontFamily]）。custom 还要求本地真有那个字体文件。
                if (o.has("font_family")) {
                    p[FONT_FAMILY] = resolveFontFamily(
                        o.getString("font_family"),
                        p[FONT_CUSTOM] ?: "",
                        CustomFont.exists(context, p[FONT_CUSTOM] ?: ""),
                    )
                }
                // v2.57：备份带待办清单才覆盖（旧备份没这个字段=不动本地清单）
                o.optJSONArray("todo_list")?.let { a ->
                    p[TODO_LIST] = JSONArray(
                        (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() },
                    ).toString()
                }
                // v2.71：特别关心同款守卫——旧备份没这个字段=不动本地名单
                o.optJSONArray("followed_villagers")?.let { a ->
                    p[FOLLOWED] = (0 until a.length()).map { a.getString(it) }.filter { it.isNotBlank() }.toSet()
                }
                // v2.72.2：计算页参数同款守卫。存原始串不解析——脏值由 parseCalc 在读侧钳，
                // 这里再解析一遍就是同一份校验写两处，迟早只改一处。
                o.optString("calc_setup").takeIf { it.isNotBlank() }?.let { p[CALC] = it }
            }
            val kept = localNames.count { it !in backupNames }
            return buildString {
                append("覆盖 $overwritten 个同名存档，保留 $kept 个本地存档")
                if (dropped > 0) append("；超出 ${MAX_PROFILES} 个上限，丢弃 $dropped 个")
            }
        } else {
            // 旧版扁平格式 → 当前存档
            val prof = activeName()
            val checks = o.optJSONArray("checks")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
            // v2.17 C3：手改备份可带出越界日期，钳制后再落盘
            val y = o.optInt("year", 1).coerceAtLeast(1)
            val s = o.optInt("season", 0).coerceIn(0, 3)
            val d = o.optInt("day", 1).coerceIn(1, 28)
            context.dataStore.edit { p ->
                p[checksKey(prof)] = checks
                p[dateKey(prof)] = "$y|$s|$d"
                p[prof5Key(prof)] = o.optString("prof5", "")
                p[prof10Key(prof)] = o.optString("prof10", "")
                if (o.has("theme")) p[THEME] = o.getString("theme")
                // v2.59：v1 扁平备份理论上不会有此字段，防御性带上（同款 has() 守卫 + 钳制）
                if (o.has("font_scale")) p[FONT_SCALE] = o.getInt("font_scale").coerceIn(0, 2)
                // v2.93.0：字体档同上（v1 扁平备份更不会有，带上是防御性）
                // v2.95.0：同款三值守卫（[resolveFontFamily]），口径与另两处一致
                if (o.has("font_family")) {
                    p[FONT_FAMILY] = resolveFontFamily(
                        o.getString("font_family"),
                        p[FONT_CUSTOM] ?: "",
                        CustomFont.exists(context, p[FONT_CUSTOM] ?: ""),
                    )
                }
            }
            return "导入到存档「$prof」"
        }
    }

    /** 导入前自动备份：写到 filesDir/backups/，只保留最近 5 份；返回备份文件绝对路径或 null */
    suspend fun autoBackup(): String? = runCatching {
        val dir = java.io.File(context.filesDir, "backups").apply { if (!exists()) mkdirs() }
        val file = java.io.File(dir, "auto_backup_${System.currentTimeMillis()}.json")
        file.writeText(exportJson())
        dir.listFiles { f -> f.name.startsWith("auto_backup_") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(5)
            ?.forEach { it.delete() }
        file.absolutePath
    }.getOrNull()

    private fun parseNames(json: String): List<String> =
        runCatching {
            JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } }
        }.getOrNull()?.ifEmpty { listOf(DEFAULT_PROFILE) } ?: listOf(DEFAULT_PROFILE)

    private fun saveNames(list: List<String>): String = JSONArray(list).toString()

    companion object {
        const val DEFAULT_PROFILE = "存档1"
        const val MAX_PROFILES = 8
    }
}
