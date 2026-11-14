using System;
using Microsoft.Xna.Framework;
using Microsoft.Xna.Framework.Graphics;
using StardewValley;

namespace StardewAutoPush
{
    /// <summary>
    /// mod-a3.1：HUD 消息前面的图标。
    ///
    /// ## 原版是怎么画的（反编译 StardewValley.HUDMessage.draw 确认，1.6.15）
    /// 消息框是**三段拼**出来的：
    ///   左端帽  (293, 360, 26, 24)   ← 4 倍缩放
    ///   拉伸中段 (319, 360,  1, 24)  ← 横向拉长到文字宽度
    ///   右端帽  (323, 360,  6, 24)   ← 4 倍缩放
    /// 也就是说 **(293,360,26,24) 不是"空图标位"，而是所有 HUD 消息共用的气泡左端帽**。
    ///
    /// ⚠ 之前 1.3.1 想往那一格里贴图标，那是错的：
    ///   一旦贴上，**游戏里每一条消息**（成就、错误提示、收获……）的框子都会变成我们的图标，
    ///   属于污染原版 UI。而且那一格在原版里根本不是空的（是圆角气泡的边），
    ///   贴上去也会把气泡的边框弄花。
    ///
    /// ## 正确的做法：走 messageSubject
    /// 原版代码里，消息框**中间那个图标**是这么画的：
    /// ```csharp
    /// if (messageSubject == null) { 按 whatType 画成就/错误/体力等内置图标 }
    /// else { messageSubject.drawInMenu(b, vector, ...); }   ← 走这里
    /// ```
    /// 所以只要把 HUDMessage.messageSubject 设成一个**我们自己画的 Item**，
    /// 游戏就会在框子中间把我们的图标画出来 —— 坐标、缩放、层级全由游戏负责，
    /// 而且**只影响我们自己的这条消息**，不碰任何原版 UI。
    ///
    /// ## 为什么继承 Item 而不是直接画
    /// 直接在 RenderedHud 里手动画，等于把游戏那段坐标公式抄一遍
    /// （titleSafe / heightUsed / uiViewport 宽度 / 缩放），抄错就是"图标盖住文字"或"错位"。
    /// 走 messageSubject = 用游戏自己的绘制入口，一行都不用抄。
    ///
    /// ## Item 基类的坑
    /// StardewValley.Item 是抽象类，有 4 个抽象成员要实现：
    ///   TypeDefinitionId / DisplayName / maximumStackSize() / getDescription() / isPlaceable()
    ///   （还有 drawInMenu）。另外 Item 有 NetFields（联机同步用），
    ///   但我们这个 Item 只活在内存里、从不进背包，所以那些同步字段不用管。
    /// </summary>
    internal sealed class SdhIconItem : Item
    {
        /// <summary>我们要画的图标纹理（64×64 母版，由 mod 自己加载）。</summary>
        private readonly Texture2D _texture;

        public SdhIconItem(Texture2D texture)
        {
            _texture = texture;

            // Item 基类的构造函数会把 netName 设成 "Error Item"，
            // 这里改成我们自己的名字（消息里显示的是 messageSubject 之外的东西，
            // 但有些代码会读 Name，留个正经名字更安全）。
            netName.Value = "星露小助手";
            itemId.Value = "huututu939.SaveAutoPush.Icon";
            parentSheetIndex.Value = -1;
        }

        /// <summary>唯一 ID 前缀必须是 (X) 这种形式，这里用一个自定义类型。</summary>
        public override string TypeDefinitionId => "(P)";

        public override string DisplayName => "星露小助手";

        public override int maximumStackSize() => 1;

        public override string getDescription() => "星露小助手";

        public override bool isPlaceable() => false;

        protected override Item GetOneNew() => new SdhIconItem(_texture);

        /// <summary>
        /// 在菜单/HUD 里画我们的图标。
        ///
        /// ⚠ 原版 Object.drawInMenu 的画法是：
        ///   spriteBatch.Draw(texture, location + (32,32), sourceRect, color, 0, origin, 4f*scale, ...)
        /// 也就是**以 (32,32) 为原点、按 4 倍缩放**。
        /// HUD 那边传进来的 location 是气泡左上角（已 +16 偏移），
        /// 所以我们的图标会以那个点为中心、4 倍缩放画出来 —— 和原版内置图标一样的观感。
        ///
        /// ⚠ 源矩形：我们的母版是 64×64，直接整张用（sourceRect = 整张）。
        /// </summary>
        public override void drawInMenu(SpriteBatch spriteBatch, Vector2 location, float scaleSize, float transparency, float layerDepth, StackDrawType drawStackNumber, Color color, bool drawShadow)
        {
            if (_texture == null) return;

            // 母版 64×64 → 4 倍 = 256×256，太大了；缩到 1/4（16×16）再 4 倍 = 64×64，
            // 正好是 HUD 那个 26×24 气泡内框的大小级别。
            var sourceRect = new Rectangle(0, 0, _texture.Width, _texture.Height);
            spriteBatch.Draw(
                _texture,
                location + new Vector2(32f, 32f),
                sourceRect,
                color * transparency,
                0f,
                new Vector2(sourceRect.Width / 2, sourceRect.Height / 2),
                4f * scaleSize * 0.25f,   // 4 倍缩放（跟原版一致）× 0.25（母版是 4 倍尺寸）
                SpriteEffects.None,
                layerDepth);
        }
    }
}
