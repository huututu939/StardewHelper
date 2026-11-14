package com.stardew.assistant.data

/**
 * 「🎒 你的状态」总览（v2.100.0 / c184）。
 *
 * ## 它是什么
 * 今日页「我的农场」分段里的一张**总览卡**：一屏看完「我这个存档现在是什么样」。
 *
 * ## 为什么要有它（用户 2026-10-28 原话）
 * 「你的状态」原本是**图鉴条目详情里的一张段落卡**（显示「这一条对上我的什么」）。
 * 群友反馈「我咋没看到图鉴条目里面新增有你的状态呀」—— 原因有二：
 * ① 要**点进每一个条目**才看得到（建筑 / 晶洞 / 节日这些分类没有对应存档数据，
 *    整卡直接不出现 ⇒ 看起来像「没做」）；
 * ② 「背包」「村民好感」这两个维度**只**存在于图鉴条目详情里，
 *    今日页与进度页都是**盲区**（村民页有每个人的卡片，但不给「最高是谁」）。
 * 所以在「我的农场」里加一张总览卡 —— **图鉴那份保留**（单条状态仍有用），
 * 总览补的是「一屏看全貌」的那一层。
 *
 * ## 纪律
 * · **纯逻辑**：不碰 Context / Compose / 文件（单测可直接调）。
 * · **没导入存档 ⇒ 整卡不出现**（返回 null）：没存档却显示「0 类 0 件」是骗人。
 * · **不重复「我的农场」已有的**：现金 / 今日能收 / 田里明细 / 农场地图
 *   都在「🌾 今日农场」卡里，这张卡**不列**它们。
 * · **算不出来就不显示那一项**：好感点数存档里没记 ⇒ 不写「0 点」，
 *   写「好感点数存档里没记，以游戏内为准」。
 * · **收集包口径与进度页同源**：直接用 [ProgressStats] 的 bundleDone / bundleTotal，
 *   绝不自己再算一遍（两处各算一次必然漂移）。
 * · **图鉴进度分母实时算**：13 个可打勾分类的条目数之和，
 *   **不写死常量**（数据包一扩充，写死的分母立刻变成谎话 —— v2.25 的教训）。
 */
data class MyStatusSummary(
    /** 背包里识别出名字的**种类数**。 */
    val bagKinds: Int,
    /** 背包里识别出名字的**总件数**。 */
    val bagItems: Int,
    /** 背包里名字没对上 App 的件数（>0 ⇒「没找到」不可靠）。 */
    val unmappedItems: Int,
    /** 存档里有好感记录的村民人数。 */
    val friendCount: Int,
    /** 好感点数最高的那位村民名（null = 一个都判不出点数）。 */
    val topFriendName: String?,
    /** 那位村民的好感点数（null = 存档没记点数，不补 0）。 */
    val topFriendPoints: Int?,
    /** 图鉴已勾条目数。 */
    val codexDone: Int,
    /** 图鉴可勾条目总数（13 个可打勾分类的条目数之和，实时算）。 */
    val codexTotal: Int,
    /** 收集包已提交件数。 */
    val bundleDone: Int,
    /** 收集包总件数。 */
    val bundleTotal: Int,
)

/**
 * 折出「你的状态」总览。
 *
 * @param state 导入存档折出来的状态（null = 没导入存档 ⇒ 返回 null，整卡不出现）
 * @param checks 用户已勾的键集合（收集包 `cc:` 键 + 图鉴打勾键）
 * @param groups 图鉴全部分类（用**含 Mod** 的那份，与图鉴页看到的一致）
 * @param bundle 收集包房间（与进度页同一份）
 */
fun myStatusSummary(
    state: CodexState?,
    checks: Set<String>,
    groups: List<CodexGroup>,
    bundle: ProgressStats?,
): MyStatusSummary? {
    if (state == null) return null

    // ── 背包：识别出的种类数 / 件数 + 名字没对上的件数 ──────────────
    val bagKinds = state.itemCounts.size
    val bagItems = state.itemCounts.values.sum()

    // ── 村民：人数 + 好感点数最高的那位 ─────────────────────────────
    // ⚠ 点数为 null 的**不参与排名**（不补 0 当最高），但**计入人数**
    //   （存档里确实有 TA 的记录，只是点数没写）。
    val friends = state.friendships
    var topName: String? = null
    var topPoints: Int? = null
    for ((nm, f) in friends) {
        val p = f.points ?: continue
        if (topPoints == null || p > topPoints) {
            topPoints = p
            topName = nm
        }
    }

    // ── 图鉴进度：13 个可打勾分类的条目数（实时算，不写死）──────────
    var codexTotal = 0
    var codexDone = 0
    for (g in groups) {
        if (!isCheckable(g.id)) continue
        codexTotal += g.items.size
        codexDone += groupCheckedCount(g.id, g.items, checks)
    }

    // ── 收集包：与进度页同源（直接用 ProgressStats，不另算）──────────
    val bundleDone = bundle?.bundleChecked ?: 0
    val bundleTotal = bundle?.bundleTotal ?: 0

    return MyStatusSummary(
        bagKinds = bagKinds,
        bagItems = bagItems,
        unmappedItems = state.unmappedItems,
        friendCount = friends.size,
        topFriendName = topName,
        topFriendPoints = topPoints,
        codexDone = codexDone,
        codexTotal = codexTotal,
        bundleDone = bundleDone,
        bundleTotal = bundleTotal,
    )
}
