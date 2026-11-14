using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using StardewModdingAPI;
using StardewModdingAPI.Events;
using StardewModdingAPI.Utilities;
using StardewValley;

namespace StardewAutoPush
{
    /// <summary>config.json（SMAPI 首次启动自动生成）。端口不暴露配置：固定扫 38217~38236，与 App 同段。</summary>
    public class ModConfig
    {
        /// <summary>App 的地址（如 192.168.1.7 或 192.168.1.7:38220）。空 = 每次自动发现（UDP 优先）。</summary>
        public string AppIp { get; set; } = "";

        /// <summary>每次游戏保存后自动推送。false 时仍可用 sdh_push 手动推。</summary>
        public bool AutoUpload { get; set; } = true;

        /// <summary>HUD 横幅提示开关（控制台日志不受影响）。</summary>
        public bool ShowHudNotifications { get; set; } = true;

        /// <summary>
        /// 「立即推送存档」的快捷键（mod-a3 起）。空串 = 禁用。
        ///
        /// ⚠ 格式是 SMAPI 的 [StardewModdingAPI.Utilities.KeybindList] 解析格式：
        ///   单键写 "F5"；多键写 "F5, LeftShift"；多个绑定用 "|" 分隔。
        ///   空串 = 没绑（不监听）。
        /// </summary>
        public string PushKeybind { get; set; } = "F5";

        /// <summary>
        /// 「重新配对」的快捷键（清 Token + 立即推送，手机上会要你输一次码）。空串 = 禁用。
        /// </summary>
        public string PairKeybind { get; set; } = "F6";
    }

    /// <summary>
    /// 存档自动推送：游戏一保存，就把当前农场文件夹打包，经局域网推给「星露小助手」App。
    ///
    /// ## 协议（必须与 App 端 SaveReceiver.kt 对齐，两端是合同）
    /// - 发现：UDP 38217 广播 ASCII "SDH-FIND" → App 回 "SDH1 &lt;tcp端口&gt; &lt;ip1,ip2,...&gt;"；
    ///   失败先按上次成功地址兜底直探（UDP 广播偶发被手机省电吃掉），
    ///   再扫 config.AppIp 的 38217~38236（TCP 连上后 GET / 必须含「正在等存档包」才算数）。
    ///   ⚠ 故意不做全网段扫描：bat 可以（用户点一下才发一次），mod 是每次保存都触发，
    ///     一次 254 个 TCP 连接的扫法放在保存路径上就是骚扰。
    /// - 上传：POST http://&lt;ip&gt;:&lt;port&gt;/upload，头 X-Stardew-Proto: 2，
    ///   体 = 农场文件夹的 zip（内容在根）。mod-a2 起带 Token 状态机：
    ///   无 Token → 6 位码 + DeviceId + 电脑名（首配，"paired" 响应里领 Token 持久化）；
    ///   有 Token → X-Stardew-Token 免码直传（401 = 信任被撤销/失效，清 Token 回退配对）。
    /// - Token 存 helper.Data **全局数据**（mod 更新不丢，配对只做一次）；config.json
    ///   等用户配置照旧走 ReadConfig。
    ///
    /// ## 与 send_save.bat 的唯一差异：打包时**排除游戏备份**
    /// _SVBAK 实测能到 36MB，整包可能超 App 的 20MB 上限（413）。排除名单与 App 端
    /// SaveImport.kt 的 ZIP_EXCLUDE 同源（_old/_SVBAK/_SVEMERG/SaveGameInfo/BACKUP_SAVE）——
    /// App 本来就忽略它们，排除只是少传废字节。
    ///
    /// ## 线程纪律（SMAPI 硬规矩）
    /// - Saved 事件在主线程：只捕获字符串（存档目录/农场名），网络与文件 I/O 全在 Task.Run。
    /// - 后台线程**禁止**碰 Game1 / 游戏运行时对象；HUD 提示经 ConcurrentQueue，
    ///   UpdateTicked（主线程）里 TryDequeue 后 addHUDMessage。
    /// - 进行中标志（Interlocked）：上一次没传完，本次保存跳过——多次快速保存只产生一个上传。
    /// </summary>
    public class ModEntry : Mod
    {
        // ── 协议常量（与 SaveReceiver.kt 同值，改一处必须改另一处）──
        private const int DiscoveryPort = 38217;
        private const int PortBase = 38217;
        private const int PortScan = 20;
        private const long MaxBytes = 20L * 1024 * 1024;   // App 端上限，超了必 413
        private const int MaxCodeRetries = 3;              // 403 换码重试上限（与 bat 的 maxTry 同值）

        private const string ZipExcludeExact = "SaveGameInfo,BACKUP_SAVE";
        private static readonly string[] ZipExcludeSuffix = { "_old", "_SVBAK", "_SVEMERG" };

        /// <summary>当前配置（mod-a3：GMCM 设置页要读写它）。</summary>
        public ModConfig Config { get; private set; } = new ModConfig();

        private string DeviceId = "";
        private HttpClient Http;

        /** App 颁发的信任 Token（"" = 未配对）。helper.Data 全局数据持久化，mod 更新不丢。 */
        private string Token = "";

        /** 上次成功连上的 App 地址（"ip:port"）。UDP 广播被手机省电吃掉时的兜底。 */
        private string LastApp = "";

        /** 后台攒 HUD 文案，主线程（UpdateTicked）取出来弹。 */
        private readonly ConcurrentQueue<string> HudQueue = new();

        /** 0=空闲，1=正在推送（防抖：一次保存没传完，下次保存不再叠发）。 */
        private int InFlight;

        /**
         * 快捷键监听（mod-a3）。
         *
         * ⚠ 用 SMAPI 的 [StardewModdingAPI.Utilities.KeybindList]（1.6 官方 mod 按键机制），
         *   不是自己挂 Win32 键盘钩子——那样会跟游戏的键盘分发器打架。
         *   绑定字符串存在 config.json（PushKeybind / PairKeybind），可在 GMCM 里改。
         */
        private KeybindList PushKeys;
        private KeybindList PairKeys;

        /** 按键绑定解析失败时记过一次（免得每帧刷日志）。 */
        private bool KeybindWarned;

        /** 最近一次推送结果（sdh_status 用）。 */
        private string LastPush = "尚无";

        public override void Entry(IModHelper helper)
        {
            Config = helper.ReadConfig<ModConfig>();

            // DeviceId：全局数据持久化（跨 mod 更新保留）。A-1 只作为头信息随包带上；
            // 自动信任（Token）协议在 App 端就绪后启用。
            var id = helper.Data.ReadGlobalData<string>("device-id");
            if (string.IsNullOrWhiteSpace(id))
            {
                id = Guid.NewGuid().ToString("D");
                helper.Data.WriteGlobalData("device-id", id);
            }
            DeviceId = id;

            // mod-a2：Token 与上次 App 地址也从全局数据恢复（配对只做一次，更新 mod 不重配）
            Token = helper.Data.ReadGlobalData<string>("app-token") ?? "";
            LastApp = helper.Data.ReadGlobalData<string>("last-app") ?? "";

            Http = new HttpClient { Timeout = TimeSpan.FromSeconds(120) };   // 与 bat 的 -TimeoutSec 120 同值

            helper.Events.GameLoop.Saved += OnSaved;
            helper.Events.GameLoop.UpdateTicked += OnUpdateTicked;

            // mod-a3：快捷键。默认 F5 = 立即推送，F6 = 重新配对。
            // 绑定字符串在 config.json 里（GMCM 可改），空 = 禁用。
            LoadKeybinds();
            helper.Events.GameLoop.UpdateTicked += OnKeybindTicked;

            // mod-a3：接 GMCM（用户已装 spacechase0.GenericModConfigMenu）。
            // ⚠ **必须等到 SaveLoaded 再接**：在 Entry() 里接会报
            //   「Tried to access a mod-provided API before all mods were initialized」
            //   —— 那一刻别的 mod 还没初始化完，GMCM 的 API 拿不到。
            //   SaveLoaded 是 SMAPI 保证「所有 mod 都初始化完了」的时点。
            helper.Events.GameLoop.SaveLoaded += OnSaveLoaded;

            helper.ConsoleCommands.Add("sdh_status", "查看存档推送的状态（设备号/配对/配置/最近一次结果）",
                (cmd, args) => OnStatus());
            helper.ConsoleCommands.Add("sdh_push", "立即把当前存档推送到手机",
                (cmd, args) => PushSave("手动"));
            helper.ConsoleCommands.Add("sdh_pair", "清除信任并立即重新配对（手机上会要你输一次码）",
                (cmd, args) =>
                {
                    ClearToken();
                    Monitor.Log("Token 已清除，开始重新配对。", LogLevel.Info);
                    PushSave("配对");
                });
            helper.ConsoleCommands.Add("sdh_untrust", "清除本机保存的 Token（下次推送时重新配对）",
                (cmd, args) =>
                {
                    ClearToken();
                    Monitor.Log("Token 已清除。下次推送会重新配对。", LogLevel.Info);
                });

            Monitor.Log(
                $"SaveAutoPush 已启动。DeviceId={DeviceId}，{(Token == "" ? "未配对" : "已配对（自动同步）")}，" +
                $"AutoUpload={Config.AutoUpload}，AppIp={(Config.AppIp == "" ? "自动发现" : Config.AppIp)}" +
                (LastApp == "" ? "" : $"，上次 App={LastApp}"),
                LogLevel.Info);
        }

        // ── 快捷键（mod-a3）────────────────────────────────────────────

        /// <summary>
        /// 存档加载完成 → 接 GMCM。
        ///
        /// ⚠ **不能在 Entry() 里接**：SMAPI 的 mod-provided API 在所有 mod 初始化
        ///   完成之前是拿不到的，提前访问会报红字
        ///   「Tried to access a mod-provided API before all mods were initialized」。
        ///   SaveLoaded 是 SMAPI 保证初始化全部完成的时点。
        ///
        /// ⚠ 也要防「用户根本没进游戏」的情况：GMCM 的设置页在标题界面也能开，
        ///   所以这里挂在 SaveLoaded 上没问题（进了游戏才有存档），
        ///   但 Register 是幂等的——重复调用不会重复注册。
        /// </summary>
        private void OnSaveLoaded(object sender, SaveLoadedEventArgs e)
        {
            // 接不上就安静降级——快捷键照常能用，只是改键要手改 config.json。
            GmcmIntegration.TryRegister(Helper, this, ModManifest, Monitor);

            // HUD 消息前面的空图标框 → 换成我们的 App 图标。
            // ⚠ 也在 SaveLoaded 之后接：AssetRequested 属于 mod-provided API，
            //   提前接会报「before all mods were initialized」。
            HudIcon.Hook(Helper, Monitor);
        }

        /// <summary>
        /// mod-a3：改完配置后落盘 + 立刻重载**按键绑定**（改键要马上生效，
        /// 不然用户得重启游戏才看到变化——那体验跟没做一样）。
        /// </summary>
        public void SaveConfigAndReload()
        {
            try
            {
                Helper.WriteConfig(Config);
            }
            catch (Exception ex)
            {
                Monitor.Log($"写 config.json 失败：{ex.Message}", LogLevel.Warn);
            }
            LoadKeybinds();
        }

        /// <summary>
        /// 从 config.json 读按键绑定。解析失败不抛——记一条日志就完事，
        /// 免得一个手改坏的 config.json 把整个 mod 卡在加载阶段。
        /// </summary>
        private void LoadKeybinds()
        {
            PushKeys = ParseKeybind(Config.PushKeybind, "PushKeybind");
            PairKeys = ParseKeybind(Config.PairKeybind, "PairKeybind");
        }

        private KeybindList ParseKeybind(string raw, string fieldName)
        {
            if (string.IsNullOrWhiteSpace(raw)) return null;
            // ⚠ 解析口径**只有一处**（GmcmIntegration.ToKeybindList）——
            //   GMCM 里显示的绑定和实际生效的绑定必须是同一个解析结果，
            //   两处各写一遍迟早会漂移（改了一处另一处没改 = 用户改了没反应）。
            var kb = GmcmIntegration.ToKeybindList(raw);
            // ⚠ 转换器认不出来时返回**空列表**（GMCM 需要非 null），不是 null。
            //   这里把「空列表」翻译回「没绑定」——真正生效的绑定为空 = 不监听。
            if (kb == null || kb.Keybinds == null || kb.Keybinds.Length == 0)
            {
                if (!KeybindWarned)
                {
                    KeybindWarned = true;
                    Monitor.Log(
                        $"{fieldName} 的绑定写法「{raw}」认不出来，已禁用这个快捷键。" +
                        $"可用写法：F5 或 F5,LeftShift（多个绑定用 | 分隔）。",
                        LogLevel.Warn);
                }
                return null;
            }
            return kb;
        }

        /// <summary>
        /// 每帧检查快捷键（主线程）。
        ///
        /// ## 什么时候**不**响应
        /// - 任何菜单/对话框开着的时候：玩家可能在给 NPC 打字、给马取名，
        ///   这时候按 F5 应该是打字而不是推送。误触的代价（打错字）远大于
        ///   「菜单里按一次快捷键没反应」。
        /// - 标题界面：还没有存档，推了也没意义。
        ///
        /// ## 为什么要 Suppress（吃掉这个键）
        /// 我们的键（F5/F6）游戏默认没绑，但**别的 mod 可能绑了同一个键**。
        /// 不吃掉的话，按一次会同时触发两边。吃掉之后这个键在**我们判定的那一帧**
        /// 对游戏不可见——这是 SMAPI 给 mod 的标准做法（helper.Input.Suppress）。
        /// </summary>
        private void OnKeybindTicked(object sender, UpdateTickedEventArgs e)
        {
            // 菜单/对话框开着 → 不响应（玩家可能在打字/给 NPC 取名，
            // 这时按 F5 应该是打字而不是推送）。
            if (Game1.activeClickableMenu != null) return;
            // 标题界面 / 玩家对象还没建好 → 没有活动存档，推了也没意义。
            if (Game1.player == null) return;

            if (PushKeys != null && PushKeys.JustPressed())
            {
                // 吃掉这个键：这一帧游戏自己看不到它（别和别的 mod 抢同一个键）
                SuppressKeys(PushKeys);
                Monitor.Log("快捷键：立即推送存档。", LogLevel.Info);
                PushSave("手动");
                return;
            }

            if (PairKeys != null && PairKeys.JustPressed())
            {
                SuppressKeys(PairKeys);
                Monitor.Log("快捷键：重新配对（已清除本机 Token）。", LogLevel.Info);
                ClearToken();
                Hud("已清除本机信任，正在重新配对…");
                PushSave("配对");
            }
        }

        /// <summary>
        /// 吃掉这次按键，让游戏自己看不到它。
        ///
        /// ⚠ KeybindList 里可能有**多个绑定**（"F5|F6" 那种），每个绑定又可能是
        ///   组合键（"F5,LeftShift"），所以要**摊平**成 SButton 列表逐个 Suppress。
        ///   只 Suppress 第一个会漏——那样游戏仍会响应同一个键。
        /// </summary>
        private void SuppressKeys(KeybindList keys)
        {
            foreach (var kb in keys.Keybinds)
            {
                foreach (var b in kb.Buttons)
                {
                    try { Helper.Input.Suppress(b); }
                    catch { /* 个别按钮可能不被支持，忽略即可 */ }
                }
            }
        }

        private void OnUpdateTicked(object sender, UpdateTickedEventArgs e)
        {
            while (HudQueue.TryDequeue(out var msg))
            {
                // ⚠ 走 HudIcon 造消息：带我们自己的图标（原版那个框子中间是空的）。
                //   图标加载失败时它退回普通消息（原来的空框样子），不会把消息搞没。
                Game1.addHUDMessage(HudIcon.CreateMessage(msg));
            }
        }

        private void OnSaved(object sender, SavedEventArgs e)
        {
            if (!Config.AutoUpload) return;
            PushSave("自动");
        }

        /// <summary>主线程入口：只取字符串，然后把所有活儿丢给后台线程。</summary>
        private void PushSave(string why)
        {
            // 存档文件夹 = Saves/&lt;农场名&gt;；bat 打包的就是这个文件夹（内容在 zip 根）
            var folder = Constants.SaveFolderName;
            var savesPath = Constants.SavesPath;
            if (string.IsNullOrWhiteSpace(folder) || string.IsNullOrWhiteSpace(savesPath))
            {
                Hud("现在没有活动存档，没法推送。");
                return;
            }
            var farmDir = Path.Combine(savesPath, folder);
            if (!Directory.Exists(farmDir))
            {
                Hud($"找不到存档文件夹：{farmDir}");
                return;
            }

            // 防抖：上一次还没传完就跳过（连续快速保存只产生一次上传）
            if (Interlocked.CompareExchange(ref InFlight, 1, 0) != 0)
            {
                Monitor.Log("上一次推送还没结束，本次跳过。", LogLevel.Info);
                Hud("上一次推送还没结束，这次先跳过。");
                return;
            }

            if (Config.ShowHudNotifications)
                Hud($"保存完成，正在{(why == "自动" ? "后台" : "")}同步存档到手机…");

            Task.Run(() =>
            {
                try
                {
                    DoPush(farmDir, folder, why);
                }
                catch (Exception ex)
                {
                    // 兜底红线：任何异常都不许冒到 SMAPI。只记录 + 提示。
                    Monitor.Log($"推送失败：{ex}", LogLevel.Error);
                    Hud($"推送失败：{FirstLine(ex.Message)}");
                }
                finally
                {
                    Interlocked.Exchange(ref InFlight, 0);
                }
            });
        }

        // ── 后台流水线：打包 → 发现 → 上传 ─────────────────────────────

        private void DoPush(string farmDir, string folder, string why)
        {
            var zipPath = Path.Combine(Path.GetTempPath(), $"sdh_{Guid.NewGuid():N}.zip");
            try
            {
                var kb = PackZip(farmDir, zipPath);
                Monitor.Log($"打包完成：{kb} KB（{folder}）", LogLevel.Info);

                var phone = FindPhone();
                if (phone == null)
                {
                    LastPush = "未找到 App";
                    var msg = "没找到手机上的星露小助手。请确认：手机和电脑在同一个 WiFi，" +
                              "App 已打开。也可以在 config.json 填 AppIp 指定地址。";
                    Monitor.Log(msg, LogLevel.Warn);
                    Hud(msg);
                    return;
                }
                Monitor.Log($"找到 App：{phone.Value.ip}:{phone.Value.port}", LogLevel.Info);

                var ok = Upload(phone.Value.ip, phone.Value.port, zipPath);
                LastPush = ok
                    ? $"成功 → {phone.Value.ip}:{phone.Value.port}（{kb} KB，{why}）"
                    : $"失败 → {phone.Value.ip}:{phone.Value.port}";
            }
            finally
            {
                TryDelete(zipPath);
            }
        }

        /// <summary>打包农场文件夹到临时 zip（内容在根、Optimal、排除游戏备份）。返回大小 KB。</summary>
        private long PackZip(string farmDir, string zipPath)
        {
            var files = Directory.EnumerateFiles(farmDir, "*", SearchOption.AllDirectories)
                .Where(f => !IsSkipped(Path.GetRelativePath(farmDir, f)))
                .ToList();
            if (files.Count == 0) throw new IOException("存档文件夹里没有可打包的文件");

            using (var zip = ZipFile.Open(zipPath, ZipArchiveMode.Create))
            {
                foreach (var f in files)
                {
                    var entry = Path.GetRelativePath(farmDir, f).Replace('\\', '/');
                    zip.CreateEntryFromFile(f, entry, CompressionLevel.Optimal);
                }
            }
            var len = new FileInfo(zipPath).Length;
            if (len > MaxBytes)
                throw new IOException($"打包超过 20MB（{len / 1024} KB），超过 App 上限，已放弃");
            return len / 1024;
        }

        /// <summary>与 App 端 SaveImport.kt 的 ZIP_EXCLUDE 同源：主存档之外全是游戏备份/摘要。</summary>
        private static bool IsSkipped(string relName)
        {
            var leaf = relName.Replace('\\', '/').Split('/').Last();
            if (leaf == "SaveGameInfo" || leaf == "BACKUP_SAVE") return true;
            return ZipExcludeSuffix.Any(s => leaf.EndsWith(s, StringComparison.Ordinal));
        }

        // ── 发现 ──────────────────────────────────────────────────────

        /// <summary>
        /// 发现顺序：**上次成功地址直探 → UDP 广播 → config.AppIp + 端口扫描**。
        /// 都失败返回 null（不抛异常）。成功的地址记入全局数据（下次直探用）。
        /// </summary>
        /// <remarks>
        /// ⚠ **c234 变更：直探排到了 UDP 前面**（原顺序是 UDP 优先）。
        /// 实测（2026-11-09，App 端 c233/c234）：手机退到后台或息屏时，
        /// 系统会**冻结** App —— 进程在、前台服务通知在、线程在，但线程不被调度：
        /// UDP 广播**不回应**，而 TCP 直连有时**还能通**（取决于冻结时序）。
        /// 原顺序（先 UDP 后直探）的问题有两个：
        ///   ① UDP 哑火时要**白等满 2 秒**收集窗口才轮到直探（保存路径上很显眼）；
        ///   ② 更糟的是——某些 ROM 上 UDP 广播包**根本送不进来**（连收都收不到），
        ///      此时直探根本没机会跑，直接判「没找到手机」。
        /// 直探只要一个 TCP 连接（毫秒级），命中就命中；没命中再走 UDP 兜底
        /// （UDP 能覆盖「手机 IP 变了」的场合，这正是它该待的位置）。
        /// </remarks>
        private (string ip, int port)? FindPhone()
        {
            if (Config.AppIp != "")
            {
                // 允许 "1.2.3.4" / "1.2.3.4:port" / "http://1.2.3.4:port" 几种写法
                var host = Config.AppIp.Trim().TrimEnd('/');
                if (host.StartsWith("http://", StringComparison.OrdinalIgnoreCase)) host = host["http://".Length..];
                else if (host.StartsWith("https://", StringComparison.OrdinalIgnoreCase)) host = host["https://".Length..];
                int fixedPort = 0;
                var idx = host.LastIndexOf(':');
                if (idx > 0 && int.TryParse(host[(idx + 1)..], out var p)) { fixedPort = p; host = host[..idx]; }
                var found = fixedPort > 0
                    ? (Probe(host, fixedPort) ? (host, fixedPort) : ((string, int)?)null)
                    : ScanPorts(host);
                if (found != null) { RememberApp(found.Value); return found.Value; }
                Monitor.Log("config.AppIp 指定的地址不通，改走自动发现。", LogLevel.Warn);
            }

            // ⚠ 直探排第一：上次成功连过的地址直接探活（一个 TCP 连接，毫秒级）。
            //   不做全网段扫描——那是对局域网的骚扰，保存路径上尤其不该做。
            if (LastApp != "")
            {
                var idx = LastApp.LastIndexOf(':');
                if (idx > 0 && int.TryParse(LastApp[(idx + 1)..], out var lp) &&
                    Probe(LastApp[..idx], lp))
                {
                    return (LastApp[..idx], lp);
                }
                Monitor.Log($"上次成功的地址探不通（{LastApp}），改走 UDP 广播发现。", LogLevel.Info);
            }

            // UDP 广播：覆盖「手机 IP 变了」的场合——上次记的地址已经失效时靠它。
            var byUdp = UdpDiscover();
            if (byUdp != null) { RememberApp(byUdp.Value); return byUdp.Value; }

            return null;
        }

        /// <summary>成功找到 App → 记住地址（写全局数据；下次 UDP 哑火时的兜底）。</summary>
        private void RememberApp((string ip, int port) found)
        {
            var addr = $"{found.ip}:{found.port}";
            if (addr == LastApp) return;
            LastApp = addr;
            Helper.Data.WriteGlobalData("last-app", addr);
        }

        private (string, int)? UdpDiscover()
        {
            try
            {
                using var udp = new UdpClient { EnableBroadcast = true };
                udp.Client.ReceiveTimeout = 500;
                var req = Encoding.ASCII.GetBytes("SDH-FIND");
                foreach (var bc in BroadcastAddresses())
                {
                    try { udp.Send(req, req.Length, new IPEndPoint(IPAddress.Parse(bc), DiscoveryPort)); }
                    catch { /* 单个广播地址失败不影响其他 */ }
                }

                var deadline = Environment.TickCount64 + 2000;   // 与 bat 的 2 秒收集窗口同值
                while (Environment.TickCount64 < deadline)
                {
                    IPEndPoint ep = new(IPAddress.Any, 0);
                    byte[] data;
                    try { data = udp.Receive(ref ep); }
                    catch (SocketException) { continue; }   // 收包超时 → 到点退出
                    var txt = Encoding.ASCII.GetString(data);
                    if (!txt.StartsWith("SDH1 ")) continue;
                    var parts = txt.Split(' ');
                    if (parts.Length < 3 || !int.TryParse(parts[1], out var port) || port <= 0) continue;
                    foreach (var group in parts.Skip(2))
                    {
                        foreach (var ip in group.Split(','))
                        {
                            if (ip.Length != 0 && Probe(ip, port)) return (ip, port);
                        }
                    }
                }
                Monitor.Log("UDP 广播没有得到 App 的回应。", LogLevel.Trace);
            }
            catch (Exception ex)
            {
                Monitor.Log($"UDP 发现失败（继续）：{ex.Message}", LogLevel.Trace);
            }
            return null;
        }

        /// <summary>所有网段的子网广播地址 + 受限广播，与 bat 的 Broadcast-Addresses 同口径。</summary>
        private static List<string> BroadcastAddresses()
        {
            var list = new List<string> { "255.255.255.255" };
            try
            {
                foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (ua.Address.AddressFamily != AddressFamily.InterNetwork || ua.IPv4Mask == null) continue;
                        var ip = ua.Address.GetAddressBytes();
                        var m = ua.IPv4Mask.GetAddressBytes();
                        var bc = new byte[4];
                        for (var i = 0; i < 4; i++) bc[i] = (byte)(ip[i] | ~m[i]);
                        list.Add(new IPAddress(bc).ToString());
                    }
                }
            }
            catch { /* 拿不到网卡信息就用受限广播 */ }
            return list.Distinct().ToList();
        }

        /// <summary>38217~38236 端口扫描（仅 config.AppIp 指定的那台机器）。</summary>
        private (string, int)? ScanPorts(string ip)
        {
            for (var p = PortBase; p < PortBase + PortScan; p++)
            {
                if (Probe(ip, p)) return (ip, p);
            }
            return null;
        }

        /// <summary>探活 = TCP 连上 + GET / 响应含「正在等存档包」（防止连到别人的服务）。</summary>
        private bool Probe(string ip, int port)
        {
            if (IPAddress.TryParse(ip, out _) == false) return false;
            try
            {
                using var c = new TcpClient();
                var ar = c.BeginConnect(ip, port, null, null);
                if (!ar.AsyncWaitHandle.WaitOne(600, false)) return false;
                c.EndConnect(ar);
            }
            catch { return false; }
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(3));
                var body = Http.GetStringAsync($"http://{ip}:{port}/", cts.Token).GetAwaiter().GetResult();
                return body.Contains("正在等存档包");
            }
            catch { return false; }
        }

        // ── 上传 ──────────────────────────────────────────────────────

        /// <summary>
        /// 上传状态机（mod-a2）：
        /// - 有 Token：免码直传。200 "imported"/"noop" = 成功；401 = 信任被撤销/失效
        ///   → 清 Token 并**在本次推送内**回退配对（不用等下一次保存）；403 含「验证码」
        ///   = App 版本旧（不认识 Token 头）→ 本次回退码流程，Token 不动；网络异常
        ///   → 有限次退避重试后放弃。
        /// - 无 Token：配对流程——6 位码 + DeviceId + 电脑名，200 "paired" 里领 Token
        ///   持久化（下次保存起零输码）；403 含「验证码」→ 换新码重试（bat 同款，≤3 次）。
        /// </summary>
        private bool Upload(string ip, int port, string zipPath)
        {
            var url = $"http://{ip}:{port}/upload";
            var kb = new FileInfo(zipPath).Length / 1024;

            // ── 有 Token：自动上传（零人工）──
            if (Token != "")
            {
                var r = Send(url, zipPath, token: Token);
                if (r.HttpCode == 0)
                {
                    // 网络层失败：一次退避重试，仍失败就放弃（下次保存再来）
                    Monitor.Log($"上传异常：{r.Body}", LogLevel.Warn);
                    Thread.Sleep(2000);
                    r = Send(url, zipPath, token: Token);
                    if (r.HttpCode == 0)
                    {
                        Hud("推送失败：连不上手机或传输中断，下次保存会再试。");
                        return false;
                    }
                }
                if (r.HttpOk && (r.Status == "imported" || r.Status == "noop"))
                {
                    Monitor.Log($"HTTP {r.HttpCode}：{r.Body}", LogLevel.Info);
                    Hud(r.Status == "noop"
                        ? "手机已收到存档，但没有可自动勾选的进度"
                        : $"手机已自动导入存档（{kb} KB，勾上 {r.Keys} 条）");
                    return true;
                }
                if (r.HttpCode == 401)
                {
                    Monitor.Log("401：信任已失效或被撤销，清 Token 并回退配对。", LogLevel.Warn);
                    ClearToken();
                    Hud("手机上的信任已失效或被撤销，正在重新配对…");
                    // 直接落到下面的配对流程——同一推送内完成，不用再等一次保存
                }
                else if (r.HttpCode == 403 && r.Body.Contains("验证码"))
                {
                    Monitor.Log("403：App 版本较旧（不认识 Token 头），本次回退验证码流程。", LogLevel.Warn);
                    Hud("手机上的 App 版本较旧，这次先走验证码流程。");
                }
                else
                {
                    Monitor.Log($"HTTP {r.HttpCode}：{r.Body}", LogLevel.Warn);
                    Hud($"推送失败（HTTP {r.HttpCode}）：{FirstLine(r.Body)}");
                    return false;
                }
            }

            // ── 配对流程：6 位码 + DeviceId + 电脑名 ──
            for (var attempt = 1; attempt <= MaxCodeRetries; attempt++)
            {
                var code = NewCode();
                Monitor.Log($"配对码：{code}（第 {attempt}/{MaxCodeRetries} 次）", LogLevel.Info);
                Hud($"首次配对：请在手机上输入配对码 {code}");

                var r = Send(url, zipPath, code: code);
                if (r.HttpCode == 0)
                {
                    Monitor.Log($"上传异常（第 {attempt} 次）：{r.Body}", LogLevel.Warn);
                    if (attempt < MaxCodeRetries)
                    {
                        Thread.Sleep(1000 * attempt);   // 简易退避
                        continue;
                    }
                    Hud("推送失败：连不上手机或传输中断，下次保存会再试。");
                    return false;
                }
                if (r.HttpOk)
                {
                    if (r.Status == "paired" && r.Token != "")
                    {
                        SaveToken(r.Token);
                        Monitor.Log($"配对成功，Token 已持久化（keys={r.Keys}）。下次保存自动同步。", LogLevel.Info);
                        Hud("配对成功！下次保存自动同步，不用再输码。");
                    }
                    else
                    {
                        // 没有 JSON/paired 的 200：App 是旧版（c230 及以前），码已人工确认
                        Monitor.Log($"HTTP {r.HttpCode}：{r.Body}", LogLevel.Info);
                        Hud($"手机已收到（{kb} KB）。去手机上确认导入。");
                    }
                    return true;
                }
                if (r.HttpCode == 403 && r.Body.Contains("验证码") && attempt < MaxCodeRetries)
                {
                    Monitor.Log($"验证码没对上（{r.Body.Trim()}），换新码重试。", LogLevel.Info);
                    Hud($"配对码没对上（{r.Body.Trim()}），马上换新码再试。");
                    continue;
                }
                Monitor.Log($"HTTP {r.HttpCode}：{r.Body}", LogLevel.Warn);
                Hud($"推送被拒（HTTP {r.HttpCode}）：{FirstLine(r.Body)}");
                return false;
            }
            return false;
        }

        // ── HTTP 与 Token 持久化 ──────────────────────────────────────

        private sealed class SendResult
        {
            public int HttpCode;
            public bool HttpOk;
            public string Status = "";
            public string Token = "";
            public int Keys;
            public string Body = "";
        }

        /// <summary>
        /// 发一次上传请求。带 [token] = 免码自动上传；带 [code] = 配对；
        /// DeviceId / 电脑名 / Proto 头两种模式都带。响应的 JSON 状态解析不了
        /// 就当纯文本（兼容旧 App 的人话响应）。**连接线程调用**。
        /// </summary>
        private SendResult Send(string url, string zipPath, string token = null, string code = null)
        {
            var res = new SendResult();
            try
            {
                using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(120));
                using var req = new HttpRequestMessage(HttpMethod.Post, url);
                req.Headers.Add("X-Stardew-Proto", "2");
                req.Headers.Add("X-Stardew-Device-Id", DeviceId);
                req.Headers.Add("X-Stardew-Device-Name", SanitizeName(Environment.MachineName));
                if (token != null) req.Headers.Add("X-Stardew-Token", token);
                if (code != null) req.Headers.Add("X-Stardew-Code", code);
                var content = new StreamContent(File.OpenRead(zipPath));
                content.Headers.ContentType = new("application/zip");
                req.Content = content;

                using var resp = Http.SendAsync(req, cts.Token).GetAwaiter().GetResult();
                res.HttpCode = (int)resp.StatusCode;
                res.HttpOk = resp.IsSuccessStatusCode;
                res.Body = resp.Content.ReadAsStringAsync(cts.Token).GetAwaiter().GetResult();

                // App 信任协议的响应是 JSON：{"status":"...","token":"...","keys":N}
                try
                {
                    using var doc = System.Text.Json.JsonDocument.Parse(res.Body);
                    var root = doc.RootElement;
                    if (root.TryGetProperty("status", out var st)) res.Status = st.GetString() ?? "";
                    if (root.TryGetProperty("token", out var tk)) res.Token = tk.GetString() ?? "";
                    if (root.TryGetProperty("keys", out var ky) && ky.TryGetInt32(out var k)) res.Keys = k;
                }
                catch (System.Text.Json.JsonException) { /* 旧 App 回纯文本，Status 留空 */ }
            }
            catch (Exception ex)
            {
                // 网络层失败（超时/断开/拒连）：HttpCode=0，调用方决定退避重试
                Monitor.Log($"上传异常：{ex.Message}", LogLevel.Trace);
                res.Body = "网络异常：" + ex.Message;
            }
            return res;
        }

        /// <summary>Token 持久化（helper.Data 全局数据，mod 更新不丢）。</summary>
        private void SaveToken(string token)
        {
            Token = token;
            Helper.Data.WriteGlobalData("app-token", token);
        }

        /// <summary>清除信任（撤销/重新配对前）。清的是本地副本；App 侧撤销走它的管理界面。</summary>
        private void ClearToken()
        {
            Token = "";
            Helper.Data.WriteGlobalData("app-token", "");
        }

        /// <summary>电脑名清洗：与 App 端 TrustedDevices.sanitizeName 同口径（ASCII 可打印、去引号/反斜杠、40 上限、空回退 PC）。</summary>
        private static string SanitizeName(string raw)
        {
            var sb = new StringBuilder();
            foreach (var ch in (raw ?? ""))
            {
                if (ch >= ' ' && ch <= '~' && ch != '"' && ch != '\\') sb.Append(ch);
            }
            var name = sb.ToString().Trim();
            if (name.Length > 40) name = name[..40];
            return name.Length == 0 ? "PC" : name;
        }

        /// <summary>加密随机 6 位数字（D6），与 bat 的 New-Code 同口径。</summary>
        private static string NewCode()
        {
            var n = BitConverter.ToUInt64(RandomNumberGenerator.GetBytes(8), 0) % 1_000_000uL;
            return n.ToString("D6", CultureInfo.InvariantCulture);
        }

        // ── 杂项 ──────────────────────────────────────────────────────

        private void OnStatus()
        {
            Monitor.Log(
                "SaveAutoPush 状态：\n" +
                $"  DeviceId        ：{DeviceId}\n" +
                $"  配对状态        ：{(Token == "" ? "未配对（推送会要验证码）" : "已配对（自动同步，免输码）")}\n" +
                $"  自动推送        ：{(Config.AutoUpload ? "开" : "关")}（config.json）\n" +
                $"  HUD 提示        ：{(Config.ShowHudNotifications ? "开" : "关")}\n" +
                $"  推送快捷键      ：{(PushKeys == null ? "未绑定" : Config.PushKeybind)}\n" +
                $"  配对快捷键      ：{(PairKeys == null ? "未绑定" : Config.PairKeybind)}\n" +
                $"  App 地址        ：{(Config.AppIp == "" ? "自动发现（UDP 广播）" : Config.AppIp)}\n" +
                $"  上次 App        ：{(LastApp == "" ? "无" : LastApp)}\n" +
                $"  正在推送        ：{(InFlight != 0 ? "是" : "否")}\n" +
                $"  最近一次        ：{LastPush}",
                LogLevel.Info);
        }

        /// <summary>HUD 只弹第一行：长堆叠在横幅里看不清。</summary>
        private void Hud(string msg)
        {
            if (Config.ShowHudNotifications) HudQueue.Enqueue(FirstLine(msg));
            Monitor.Log(msg, LogLevel.Trace);
        }

        private static string FirstLine(string s)
        {
            var line = (s ?? "").Split('\n')[0].Trim('\r');
            return line.Length <= 120 ? line : line[..120];
        }

        private static void TryDelete(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); }
            catch { /* 临时文件删不掉就算了（在 %TEMP% 里，系统会清） */ }
        }
    }
}
