星露小助手 · 许可范围说明（分层）
======================================================================

根目录的 `LICENSE` 是**标准 MIT 全文**，适用于本仓库的大部分内容。
本文件说明它的**边界**：仓库里的东西分三类，各有各的协议，
**不适用**根 MIT 的只有两类，请务必看下面的分层说明：

  · 应用源码 / 构建脚本 / 电脑端模组 / 电脑端推送脚本
             —— 随根 MIT License 发布（Copyright (c) 2026 huututu939）。

  · app/src/main/assets/data/   整理后的游戏数据（wiki 整理结果）
             —— 适用 **CC BY-NC-SA 3.0 Unported**（署名 + 非商业 + 同协议共享）。
             协议全文见 app/src/main/res/raw/cc_by_nc_sa_3_0.txt（随 APK 内置），
             说明见 app/src/main/assets/data/README.md。
             ⚠ CC BY-NC-SA 与 MIT **不兼容**：MIT 允许无限制地再许可与附加
             自由，CC 则要求 copyleft、非商业与同协议共享。故 data/
             明确排除在根 MIT 之外 —— 二者并存不构成「双授权」。
             「非商业」按 CC 官方的 NonCommercial interpretation 判定：
             **使用的主要目的**是不是商业。据此：
               - 你自己 fork、修改、自己用 —— 免费非商业，符合；
               - 你拿它收费、或拿功能/内容换钱 —— 越界，不符合。

  · 游戏素材（app/src/main/assets/img/ 下的游戏图标、头像等）
             —— 《星露谷物语》(Stardew Valley) 的游戏素材、名称与商标
             **归 ConcernedApe 所有**。本项目是非官方粉丝工具，与 ConcernedApe
             无关联、未获其背书；这些素材仅作识别与展示用途。
             ⇒ **素材本身不随本 LICENSE 授权**，请自行评估使用范围。

另外随包附带的两份第三方归属（协议全文都在 App 内「设置 → 关于 → 数据来源与协议」）：
  · 字体子集 TutuPixel —— SIL OFL 1.1（原文 app/src/main/res/raw/ofl_fusion_pixel.txt）
    上游为 Fusion Pixel Font（TakWolf，https://github.com/TakWolf/fusion-pixel-font）
  · 底栏玻璃算法移植件 AndroidLiquidGlass —— Apache-2.0
    （原文 app/src/main/assets/licenses/Apache-2.0-AndroidLiquidGlass.txt）
