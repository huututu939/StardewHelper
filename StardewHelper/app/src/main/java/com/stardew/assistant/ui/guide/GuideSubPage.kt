package com.stardew.assistant.ui.guide

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.stardew.assistant.Screen
import com.stardew.assistant.data.PrefsRepo
import com.stardew.assistant.ui.LocalGuideLinkify
import com.stardew.assistant.ui.BackBar
import com.stardew.assistant.ui.calendar.CalendarPage

/**
 * v2.31：攻略二级页的**统一入口**，也是正文词典链接的开关作用域。
 *
 * ## 为什么要搬家
 * 原来这 11 个 `when` 分支写在 `MainActivity` 里，正文链接只能靠「给 11 个分支
 * 各包一层 CompositionLocalProvider」来实现——11 处，改一本册子就要多改一处，
 * 漏一处就有一册的文字不可点（而且没有任何报错，只是少个功能）。
 *
 * 搬过来之后：分支清单在一处，开关也在一处，新增册子改这一个文件即可。
 * verify 钉住「`GUIDE_BOOKS` 里每个 screen 都有对应分支」。
 *
 * ## 链接开关
 * 整个作用域包 [LocalGuideLinkify] provides true。设置页、图鉴、今日、计算、
 * 进度、村民都不经过这里，保持关闭——那些页面的列表行本身可点，开链接会打架。
 *
 * ## 顶栏一律 BackBar（v2.35）
 * 这里的每个分支都是**压栈页**：以前它们画的是 [PageBar]（没有返回入口），
 * 页面内没有任何可点的退路，只能靠用户知道要按系统返回键。现在全部换成
 * [BackBar]（左上角「← 返回」），按的是同一个 [onBack] 闭包 = 同一个栈，
 * 与 `AppRoot` 的 `BackHandler` 永远退到同一层。
 *
 * [guideHome] 由 `MainActivity` 作为 lambda 传进来（攻略首页正文共享同一个实例，
 * 与批次 1 定的「十册跳转回调只有一处」是同一条纪律）；它内部自己已包开关，
 * 这里再包一层是无害的嵌套。
 */
@Composable
fun GuideSubPage(
    screen: Screen,
    snap: PrefsRepo.Snapshot,
    repo: PrefsRepo,
    onBack: () -> Unit,
    guideHome: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGuideLinkify provides true) {
        // v2.72.2：底部玻璃占位**不在这里加**。
        // 初稿曾在外面包一层 `Modifier.padding(bottom = LocalGlassInset.current)`，
        // 那是**压缩视口**——视口外那块是死区，内容被拦腰截断且滑不过去（用户截图实证）。
        // 正确做法是各页列表自己的 contentPadding.bottom（能滚过去、下面留空白），
        // 收在 [guideContentPadding] 一处；这里保持纯路由。
        when (screen) {
            // v2.28 兜底路由：图鉴改用「数据 / 攻略」分段后已无 push 入口，
            // 保留是因为它零成本（Screen 枚举里本来就有）。
            // 它同样是压栈页，所以也画 BackBar——万一哪天有人 push 它，
            // 顶上也有返回入口，不会又造一个「只能按系统键」的页面。
            Screen.Guide -> Column {
                BackBar("📖 攻略", onBack)
                guideHome()
            }
            Screen.GuideIsland -> GuideIslandPage(onBack)
            Screen.GuideMarriage -> GuideMarriagePage(onBack)
            Screen.GuideWeapons -> GuideWeaponsPage(onBack)
            Screen.GuideSkills -> GuideSkillsPage(onBack)
            Screen.GuideForaging -> GuideForagingPage(onBack)
            Screen.GuidePets -> GuidePetsPage(onBack)
            Screen.GuideWeather -> GuideWeatherPage(onBack)
            Screen.GuideCombat -> GuideCombatPage(onBack)
            Screen.GuideShops -> GuideShopsPage(onBack)
            // v2.25 年历：游戏内 112 天历法。正文含作物名，同样开链接。
            // v2.35 补 onBack：它也是从攻略首页压进来的，顶栏同样要有返回。
            Screen.Calendar -> CalendarPage(snap, repo, onBack)
            else -> {}
        }
    }
}