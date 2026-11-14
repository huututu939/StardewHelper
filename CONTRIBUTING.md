# 参与贡献

欢迎 Issue 与 PR。开改之前先读一下这篇，能省双方时间。

## 提 Issue

- 用仓库的 Issue 模板（问题反馈 / 功能建议），空白 Issue 已关闭。
- 写清：机型、系统版本、App 版本（设置 → 关于）、复现步骤。
- **不要贴存档文件本身**——里面有你的玩家名与农场名；
  可以贴 App 的解析结果截图或导出的进度 JSON（里面没有个人信息）。

## 提 PR

**Android 主工程**（`StardewHelper/`）

- 环境：JDK 17 + Android SDK 35（`local.properties` 指向你的 SDK，不要提交）。
- 构建通过即可提交：`./gradlew assembleDebug`（Windows 用 `gradlew.bat`）。
- UI 改动请附截图或录屏。

**电脑端模组**（`StardewAutoPush/`）

- .NET 6 + [SMAPI](https://smapi.io/)：`dotnet build -c Release`。

**游戏数据**（`app/src/main/assets/data/`）

- 数据整理自 Stardew Valley Wiki，改动请在 PR 描述里给出 wiki 依据；
- 带「未核」标记的字段表示尚未核对，修正它们特别受欢迎。

## 协议要点

- 代码以 **MIT** 发布；`assets/data/` 的整理数据以 **CC BY-NC-SA 3.0** 发布——
  你的数据改动也会按同一协议发布；
- `assets/img/` 下的游戏素材归 ConcernedApe，**不要**往里加新素材；
  边界详情见 [`LICENSING.md`](LICENSING.md)。

## 一句实话

本项目**更新频率已放缓**，Issue 和 PR 的响应可能不快，见谅；
但都会看。fork 后自己改自己用，完全不需要征得同意（MIT）。
