using System;
using Microsoft.Xna.Framework;
using Microsoft.Xna.Framework.Graphics;
using StardewModdingAPI;
using StardewValley;

namespace StardewAutoPush
{
    /// <summary>
    /// mod-a3.2：HUD 消息前面那个图标位。
    ///
    /// ## 原版到底怎么画的（反编译 StardewValley.HUDMessage.draw，1.6.15）
    /// 消息框是**三段拼**出来的：
    ///   左端帽  (293, 360, 26, 24)  ← 4 倍缩放
    ///   拉伸中段 (319, 360,  1, 24)  ← 横向拉到文字宽度
    ///   右端帽  (323, 360,  6, 24)  ← 4 倍缩放
    /// 也就是说 **(293,360,26,24) 不是"空图标位"，而是全游戏共用的气泡左端帽**。
    ///
    /// ⚠ 1.3.1 想往那一格贴图标，方向是错的：
    ///   贴上了，**游戏里每一条消息**（成就、报错、收获……）的框子都会变成我们的图标，
    ///   那是污染原版 UI；而且那一格本来就不是空的（是圆角气泡的边），贴上去也会把边框弄花。
    ///
    /// ## 正确做法：走 messageSubject
    /// 原版代码里，框子**中间那个图标**是这么画的：
    /// ```csharp
    /// if (messageSubject == null)  按 whatType 画成就/错误/体力等内置图标;
    /// else                          messageSubject.drawInMenu(b, vector, ...);
    /// ```
    /// 所以只要把 HUDMessage.messageSubject 设成一个**我们自己画的 Item**，
    /// 游戏就会在框子里把我们的图标画出来 —— 坐标、缩放、动效全由游戏负责，
    /// 而且**只影响我们自己的这条消息**，一个原版 UI 都不碰。
    ///
    /// ## 为什么不在 RenderedHud 里自己画
    /// 自己画 = 把游戏那段坐标公式抄一遍（titleSafe / heightUsed / uiViewport 宽度 / 缩放），
    /// 抄错就是"图标盖住文字"或"错位"。走 messageSubject 是**用游戏自己的绘制入口**，
    /// 一行公式都不用抄，也不会随分辨率/UI 缩放漂移。
    ///
    /// ## 实现
    /// 真正干活的是 [StardewAutoPush.SdhIconItem]（Item 的子类）。
    /// 这里只负责：加载图标纹理 + 造一个能用的 HUDMessage。
    /// </summary>
    internal static class HudIcon
    {
        /// <summary>我们自己的图标纹理（64×64 母版）。</summary>
        private static Texture2D _texture;

        /// <summary>
        /// 造好的图标 Item。
        ///
        /// ⚠ **全局共用同一个实例**，不要每次消息都 new 一个：
        ///   Item 基类带 NetFields（联机同步用的字段集合），每次 new 都要初始化一遍，
        ///   纯属浪费。这个 Item 只被**读**（drawInMenu），不写不存，共用没有副作用。
        /// </summary>
        private static SdhIconItem _item;

        /// <summary>图标是否可用（加载失败就退回"不带图标的普通消息"）。</summary>
        private static bool _ready;

        /// <summary>
        /// 加载图标纹理。
        ///
        /// ⚠ 必须在 **SaveLoaded 之后**才接（跟 GMCM 同一个理由）：
        ///   那一刻所有 mod 都初始化完了。
        ///
        /// ⚠ **必须用 ModContent.Load**（不是 GameContent.Load）：
        ///   GameContent 加载的是**游戏内容路径**（"LooseSprites/xxx"），
        ///   拿它加载 mod 自己的文件会报
        ///   「failed loading content asset 'assets/sdh_icon.png' from the game content」。
        ///   ModContent 的路径相对 **mod 根目录**，所以写 "assets/sdh_icon.png"。
        /// </summary>
        public static void Hook(IModHelper helper, IMonitor monitor)
        {
            try
            {
                _texture = helper.ModContent.Load<Texture2D>("assets/sdh_icon.png");
                _item = new SdhIconItem(_texture);
                _ready = true;
                monitor.Log("HUD 图标已就绪。", LogLevel.Trace);
            }
            catch (Exception ex)
            {
                _ready = false;
                monitor.Log($"HUD 图标加载失败（消息前面还是空框，不影响推送）：{ex.Message}", LogLevel.Warn);
            }
        }

        /// <summary>
        /// 造一条带我们图标的 HUD 消息。
        ///
        /// 图标没加载出来时退回普通消息（就是原来那个空框的样子），
        /// 绝不因为一个图标把整条消息搞没了。
        /// </summary>
        public static HUDMessage CreateMessage(string text)
        {
            var message = new HUDMessage(text);
            if (_ready && _item != null)
            {
                // ⚠ messageSubject 非空 → 游戏走"画这个 Item"的分支，
                //   我们就得到框子里那个自己的图标。
                message.messageSubject = _item;
            }
            return message;
        }
    }
}
