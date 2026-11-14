package com.stardew.assistant.ui.nav

import com.stardew.assistant.Screen
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v2.31 全局导航总线。
 *
 * ## 为什么需要它
 * 批次 4 要让**攻略页**（深埋在 `stack` 里的二级页）也能把用户送回图鉴，但
 * 「清栈 + 切页签」这两个动作只有 `AppRoot` 有权限（`stack` / `tab` 是它的局部状态）。
 *
 * 既有 `codexJump` 只解决了「图鉴怎么跳」，导航动作仍在发射端（今日页的
 * `openCodex` 在 MainActivity 里做 `stack.clear()`），攻略页拿不到那个闭包。
 * 与其把闭包一层层往下传（要改 11 个 composable 签名），不如加一条总线：
 * **谁都能发，`AppRoot` 单点消费**。
 *
 * 这是 v2.27「判定口径散落多处必然漂移」的同型预防：跳转逻辑全项目只有一处。
 * verify 用「`navBus.collect` 全项目恰好出现一次」钉死。
 *
 * 用 `MutableStateFlow` 而不是 `SharedFlow`：旧 SharedFlow(replay=0) 在
 * 「发射方与订阅方不在同一帧」时会静默丢事件（用户点了没反应）。
 * StateFlow 保留最新值，消费后置 null（与既有 `codexJump` 同一范式）。
 */
sealed interface NavRequest {

    /**
     * 跳图鉴。[groupId] 为空串且 [openGuide] 为 true 时表示「只切到攻略段，
     * 不动分类」——这是「跳到某本册子」的配套动作。
     *
     * [openSearch] 非空时落到该词的**跨分类搜索结果**而不是某个条目：
     * 392 个物品名跨分类重名（「木材」重复 22 次），重名硬跳会点开一个错的条目。
     */
    data class Codex(
        val groupId: String = "",
        val targetIndex: Int? = null,
        val openSearch: String = "",
        val openGuide: Boolean = false,
        val openMore: Boolean = false,
    ) : NavRequest

    /** 压栈到攻略二级页（[com.stardew.assistant.ui.guide.GUIDE_BOOKS] 全部 9 本）。消费方会先清栈再压入。 */
    data class Guide(val screen: Screen) : NavRequest

    /** 跳村民页并直接打开某人详情。找不到该名字时只切页签。 */
    data class Villager(val name: String) : NavRequest

    /**
     * v2.99.0：切到某个**主页签**（使用指引页里的「去看今日页」这类入口）。
     *
     * 与 [Codex] / [Guide] / [Villager] 的区别：那三个都是「去别处看一眼」，
     * 要么清栈要么记锚点；[Tab] 语义等同**用户自己手点底栏 tab** ⇒ 清栈、锚点作废。
     */
    data class Tab(val screen: Screen) : NavRequest

    /**
     * v2.99.0：压栈到某个**二级页**（设置页 / 使用指引页）。
     *
     * 与 [Guide] 的区别：[Guide] 会清栈并把页签切到图鉴（攻略册住在图鉴里）；
     * [Stack] **只压栈、页签不动** ⇒ 从「跳到…」面板进来时，退出后仍回到原页签。
     */
    data class Stack(val screen: Screen) : NavRequest
}

val navBus = MutableStateFlow<NavRequest?>(null)

/**
 * 村民页内部的「打开某人详情」通道。
 *
 * 与 [navBus] 分开是因为职责不同：`navBus` 由 `AppRoot` 消费（栈 / 页签），
 * 这条由 `VillagersPage` 自己消费（页内抽屉状态）。两边各管各的，
 * 不会出现两个订阅方抢同一条指令。
 */
val villagerTarget = MutableStateFlow<String?>(null)