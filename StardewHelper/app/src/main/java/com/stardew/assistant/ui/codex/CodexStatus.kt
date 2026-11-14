package com.stardew.assistant.ui.codex

import com.stardew.assistant.data.BundleRoom
import com.stardew.assistant.data.Calc
import com.stardew.assistant.data.CodexState
import com.stardew.assistant.data.Season
import com.stardew.assistant.data.bundleHitsOf
import com.stardew.assistant.data.formatG
import com.stardew.assistant.data.isCheckable
import com.stardew.assistant.data.isItemChecked

/**
 * 图鉴详情「🎒 你的状态」段的行组装（v2.99.0 / c179，百科 × 存档第 1 层）。
 *
 * ## 边界（硬约束）
 * · **未导入存档时这一整段不出现** —— 输入里的 [CodexStateInput.state] 为 null，
 *   调用方根本不该走到这里；本函数也绝不造默认状态出来充数。
 * · **不编数据**：查不到写「未声明 / 以游戏内为准」；daysLeft 判不出就不显示那一行；
 *   好感点数缺失不补 0。
 * · 「没找到」的诚实边界：背包计数是全量折算的，映射全成功（unmapped == 0）时
 *   「没找到」= 背包里确实没有；映射有失败（unmapped > 0）时不下这个结论，
 *   由 [CodexStatusResult.footnote] 注明。
 * · 勾选判定全走单源：图鉴分类用 [isItemChecked]，收集包用 [bundleHitsOf]
 *   （内部与 bundleDoneCount 同一个键构造）—— 这里不出现任何键前缀字面量。
 * · 生日天数与今日页生日卡用**同一个** `Calc.daysUntilWithYear`、同一个 snap 日期。
 * · 金额一律走数据层的 [formatG]（今日页「今日农场」头部用的是它）——
 *   同一个数在两屏两种写法（1625666 g vs 1,625,666 g）看着像两个数。
 *
 * 纯函数：不碰 Compose、不碰 Context；GameData 侧的结构化字段（种子价 / 生日 /
 * 配方材料）由调用方查好传进来，JVM 单测可直接喂真数据。
 */
data class CodexStatusInput(
    val groupId: String,
    val itemName: String,
    val state: CodexState,
    val checks: Set<String>,
    /** 今日页同源日期（用户转盘调整后的那个），生日倒计时用。 */
    val snapYear: Int,
    val snapSeason: Int,
    val snapDay: Int,
    /** crops 组条目的种子买价（GameData.crops 命中才有；null = 查不到 → 不显示现金行）。 */
    val seedPrice: Int? = null,
    /** 是村民条目吗（调用方按 gifts 数据按名命中判定）。 */
    val isVillager: Boolean = false,
    val birthdaySeason: String? = null,
    val birthdayDay: Int? = null,
    /** 配方材料（GameData.recipeOf 命中时给；空 = 没有配方数据）。 */
    val ingredients: List<Pair<String, Int>> = emptyList(),
    val rooms: List<BundleRoom> = emptyList(),
)

data class CodexStatusResult(
    /** 键值行（KVRow 渲染）。空列表 = 这条没有可显示的状态（整卡不出现）。 */
    val lines: List<Pair<String, String>>,
    /** 弱化注脚（背包里有未识别物品之类）。null = 没有。 */
    val footnote: String? = null,
)

fun codexStatusLines(inp: CodexStatusInput): CodexStatusResult {
    val out = mutableListOf<Pair<String, String>>()
    val st = inp.state
    val nm = inp.itemName
    if (nm.isBlank()) return CodexStatusResult(emptyList())

    // ── ① 图鉴进度（13 个可打勾分类；导入存档时 SaveImport 按真实记录点亮过）──
    if (isCheckable(inp.groupId)) {
        out += "图鉴进度" to if (isItemChecked(inp.groupId, nm, inp.checks)) "已勾" else "未勾"
    }

    // ── ② 收集包提交状态（与进度页同一个键）──────────────────────────
    // v2.100.0 / c182：反查两侧都过别名归一（bundleHitsOf 内部），且「已提交」
    //   用**收集包条目自己的名字**拼键 —— 不用图鉴条目名（否则老用户已勾的条目
    //   会静默变成「未提交」）。
    val hits = bundleHitsOf(nm, inp.rooms)
    if (hits.isNotEmpty()) {
        out += "收集包" to hits.joinToString("、") { h ->
            "${h.room}·${h.bundle.name}：" + if (h.checked(inp.checks)) "已提交" else "未提交"
        }
    }

    // ── ③ 田里（作物条目；名字与今日页同一条 cropName 映射）────────────
    // 全枯死的品种（total==0，如换季枯死）只报「枯死待清理」，不报「田里 0 株在种」。
    val field = st.crops[nm]
    if (inp.groupId == "crops" && field != null) {
        if (field.total > 0) out += "田里" to "${field.total} 株在种"
        if (field.ready > 0) out += "今天能收" to "${field.ready} 株"
        val md = field.minDays
        if (md != null && md > 0) out += "最快能收" to "$md 天后"
        if (field.dead > 0) out += "枯死待清理" to "${field.dead} 株"
    }

    // ── ④ 背包库存（折算计数；「没找到」的边界见文件头注释）──────────────
    val stock = st.itemCounts[nm]
    if (stock != null) {
        out += "背包" to "$stock 个"
    } else if (st.unmappedItems == 0) {
        // 映射全成功 ⇒ 背包里确实没有这个物品（结论可靠）
        out += "背包" to "没找到"
    } else if (inp.groupId == "crops" || inp.ingredients.isNotEmpty()) {
        // ⚠ 有未识别物品 ⇒ 「没找到」**不可靠**（那几件里可能就有它）⇒ 不下这个结论
        out += "背包" to "没找到（背包里有未识别物品，以游戏内为准）"
    }

    // ── ⑤ 配方材料够不够（对背包计数）──────────────────────────────
    if (inp.ingredients.isNotEmpty()) {
        for ((mat, need) in inp.ingredients) {
            val have = st.itemCounts[mat]
            val v = when {
                have == null && st.unmappedItems > 0 -> "$need 个 · 背包里有未识别物品，以游戏内为准"
                have == null -> "背包没有（需要 $need 个）"
                have >= need -> "背包 $have 个，够（要 $need 个）"
                else -> "背包 $have 个，还差 ${need - have}（要 $need 个）"
            }
            out += "材料 · $mat" to v
        }
    }

    // ── ⑥ 村民（好感 / 本周送礼 / 下次生日）─────────────────────────
    if (inp.isVillager) {
        val fr = st.friendships[nm]
        if (fr != null) {
            val p = fr.points
            out += "好感" to if (p != null) "$p 点（约 ${p / 250} 心）" else "未声明（以游戏内为准）"
            val g = fr.giftsThisWeek
            out += "本周送礼" to when (g) {
                null -> "未声明（以游戏内为准）"
                else -> "$g 次（送了什么存档里没记，以游戏内为准）"
            }
        } else {
            out += "好感" to "存档里还没有 TA 的记录"
        }
        val bs = inp.birthdaySeason
        val bd = inp.birthdayDay
        if (bs != null && bd != null) {
            val (_, days) = Calc.daysUntilWithYear(
                inp.snapYear, Season.names[inp.snapSeason], inp.snapDay, bs, bd,
            )
            out += "下次生日" to "$bs $bd" + if (days == 0) "（就是今天！）" else "（还有 $days 天）"
        }
    }

    // ── ⑦ 种子买价 vs 现金（只有查得到买价与现金才显示，不编）───────────
    // v2.100.0 / c182：金额走数据层已有的 [formatG]（今日页「今日农场」头部用的是它）
    //   —— 同一个 host.money 在图鉴里写「1625666 g」、今日页写「1,625,666 g」，
    //   两屏两种写法，同一个数字看着像两个数。
    val sp = inp.seedPrice
    val money = st.money
    if (inp.groupId == "crops" && sp != null && sp > 0 && money != null) {
        out += "种子" to if (money >= sp) {
            "$sp g/包 · 现金 ${formatG(money)} g，可买 ${money / sp} 包"
        } else {
            "$sp g/包 · 现金 ${formatG(money)} g，不够买一包"
        }
    }

    val footnote = if (st.unmappedItems > 0) {
        "背包里还有 ${st.unmappedItems} 件名字没对上的物品，相关数量以游戏内为准"
    } else null
    return CodexStatusResult(out, footnote)
}
