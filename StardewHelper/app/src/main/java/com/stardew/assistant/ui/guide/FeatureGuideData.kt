package com.stardew.assistant.ui.guide

import android.content.Context
import com.stardew.assistant.BAR_TAB_SCREENS
import com.stardew.assistant.Screen
import com.stardew.assistant.ui.nav.NavRequest
import org.json.JSONObject

/**
 * v2.99.0：使用指引（「功能在哪儿」）的数据模型与读取。
 *
 * ## 为什么文案放 assets 而不是写死在代码里
 * 指引讲的是**这个 App 自己**有什么，措辞要跟着 App 改。
 * 写进 Kotlin 就得改代码改包；放进 `assets/data/feature_guide.json` 后，
 * 改文案只是改一份数据。
 *
 * ## 但**数量**一律运行时现算
 * 「31 个分类 / 10 本册子 / 49 位村民」这类数字写进文案，数据一更新就说谎
 * （v2.17 D9 的老坑）。所以 JSON 里只写**占位符**（`{分类数}` 等），
 * 由页面层用真实值替换 —— 见 [FeatureGuidePage]。
 *
 * ## 跳转目标用**键**，不用字面量
 * `tab` 目标取 [BAR_TAB_SCREENS]（底栏页签表的派生），
 * `book` 目标取 [GUIDE_BOOK_BY_KEY]（攻略册表），页签/册子增删时指引自动跟着走。
 * 解析不出来 ⇒ 该条目**不显示跳转箭头**，并在页尾如实标一句「有 N 个入口没解析出来」，
 * 绝不假装「点了能跳」。
 */
sealed interface GuideJump {
    /** 切到某个主页签。 */
    data class Tab(val screen: Screen) : GuideJump

    /** 压栈到某个二级页（设置页 / 使用指引页本身）。 */
    data class Stack(val screen: Screen) : GuideJump

    /** 跳到图鉴的某个分类。 */
    data class Codex(val groupId: String) : GuideJump

    /** 打开某本攻略册。 */
    data class Book(val screen: Screen) : GuideJump

    /** 打开某位村民的详情。 */
    data class Villager(val name: String) : GuideJump
}

/** 指引里的一条功能条目。 */
data class GuideEntry(
    /** 功能名（卡片上的标题）。 */
    val name: String,
    /** 标题前的 emoji。 */
    val icon: String,
    /** 这功能干什么用。 */
    val desc: String,
    /** 在哪儿找得到（跳不了时，这句就是唯一的答案）。 */
    val where: String,
    /** 能直达时的目标；null = 跳不了，只靠 [where] 指路。 */
    val jump: GuideJump?,
)

/** 指引里的一组。 */
data class GuideGroup(
    val id: String,
    val icon: String,
    val title: String,
    /** 组标题下面那句补充说明（可空）。 */
    val note: String,
    val items: List<GuideEntry>,
)

/** 读取结果：分组 + 解析失败的跳转目标（用于如实提示，不静默）。 */
data class FeatureGuide(
    val groups: List<GuideGroup>,
    /** 解析不出来的跳转目标描述，如 `tab:xxx`。空 = 全部可跳。 */
    val broken: List<String>,
)

/** 二级页的键 → [Screen]。只放指引页真正要压栈的那几个。 */
private val STACK_SCREENS: Map<String, Screen> = mapOf(
    "settings" to Screen.Settings,
    "guide" to Screen.FeatureGuide,
    "schedule" to Screen.Schedule,
    "mods" to Screen.Mods,
)

/**
 * 进程内缓存。指引是静态资源，读一次就够 —— 但**不缓存**的话每次进页面
 * 都要开一次 assets 流，没必要。
 */
@Volatile
private var cached: FeatureGuide? = null

/**
 * 读指引数据。解析失败时返回**空分组 + 错误说明**，不抛异常：
 * 指引页挂掉不该连带整个 App 崩（v2.17 C4 的同款处理）。
 */
fun loadFeatureGuide(ctx: Context): FeatureGuide {
    cached?.let { return it }
    val out = runCatching {
        val txt = ctx.assets.open("data/feature_guide.json").bufferedReader().use { it.readText() }
        parseFeatureGuide(JSONObject(txt))
    }.getOrElse { e ->
        FeatureGuide(emptyList(), listOf("文件读取失败：${e.message ?: e::class.java.simpleName}"))
    }
    cached = out
    return out
}

/** 解析 JSON。[broken] 收集所有解析不出来的跳转目标。 */
internal fun parseFeatureGuide(root: JSONObject): FeatureGuide {
    val broken = mutableListOf<String>()
    val groups = root.optJSONArray("groups")?.let { arr ->
        (0 until arr.length()).map { gi ->
            val g = arr.getJSONObject(gi)
            val items = g.optJSONArray("items")?.let { ia ->
                (0 until ia.length()).map { ii ->
                    val it = ia.getJSONObject(ii)
                    val jumpObj = it.optJSONObject("jump")
                    val jump = if (jumpObj == null) null else parseJump(jumpObj, broken)
                    GuideEntry(
                        name = it.optString("name"),
                        icon = it.optString("icon"),
                        desc = it.optString("desc"),
                        where = it.optString("where"),
                        jump = jump,
                    )
                }
            } ?: emptyList()
            GuideGroup(
                id = g.optString("id"),
                icon = g.optString("icon"),
                title = g.optString("title"),
                note = g.optString("note"),
                items = items,
            )
        }
    } ?: emptyList()
    return FeatureGuide(groups, broken)
}

/** 解析一条跳转目标。认不出来 ⇒ 记进 [broken] 并返回 null（该条目不显示箭头）。 */
private fun parseJump(o: JSONObject, broken: MutableList<String>): GuideJump? {
    val type = o.optString("type")
    val target = o.optString("target")
    val jump: GuideJump? = when (type) {
        "tab" -> BAR_TAB_SCREENS[target]?.let { GuideJump.Tab(it) }
        "stack" -> STACK_SCREENS[target]?.let { GuideJump.Stack(it) }
        "codex" -> target.takeIf { it.isNotBlank() }?.let { GuideJump.Codex(it) }
        "book" -> (GUIDE_BOOK_BY_KEY[target]?.nav as? NavRequest.Guide)?.let { GuideJump.Book(it.screen) }
        "villager" -> target.takeIf { it.isNotBlank() }?.let { GuideJump.Villager(it) }
        else -> null
    }
    if (jump == null) broken += "$type:$target"
    return jump
}
