using System;
using System.Linq;
using System.Reflection;
using StardewModdingAPI;
using StardewModdingAPI.Utilities;

namespace StardewAutoPush
{
    /// <summary>
    /// mod-a3：把 mod 的设置接到 **Generic Mod Config Menu（GMCM）** 上，
    /// 让用户在游戏设置界面里就能改快捷键、开关自动推送，**不用手改 config.json**。
    ///
    /// ## 为什么用反射而不是直接引用 GMCM 的 dll
    /// 直接引用会跟 GMCM 的版本**硬耦合**：GMCM 一升级、换了 API 签名，
    /// 我们的 mod 就会在加载时直接崩（MissingMethodException）——
    /// 而一个「顺手加的快捷键功能」不该有能力搞崩整个游戏。
    /// 反射的做法：拿不到 API 就安静地不显示这个页签，
    /// 功能降级成「手改 config.json」，但游戏照常玩。
    ///
    /// ## GMCM 没装怎么办
    /// 一样安静降级。用户可以去装 GMCM，装完重启游戏就有了。
    ///
    /// ## 依赖
    /// 只用 SMAPI 自带的 [StardewModdingAPI.IModRegistry.GetApi] 拿 GMCM 的 API，
    /// 不引用任何 GMCM 的程序集。
    /// </summary>
    internal static class GmcmIntegration
    {
        private const string GmcmId = "spacechase0.GenericModConfigMenu";

        /// <summary>GMCM 的 API 对象（null = 没装或没拿到）。</summary>
        private static object Api;

        /// <summary>拿到的 API 方法（反射出来的委托缓存，省得每帧反射）。</summary>
        private static MethodInfo AddKeybindListMethod;
        private static MethodInfo AddBoolMethod;
        private static MethodInfo AddTextMethod;
        private static MethodInfo RegisterMethod;

        /// <summary>已经注册过就别重复注册。</summary>
        private static bool Registered;

        /// <summary>
        /// 尝试接上 GMCM。**任何一步失败都安静返回** —— 接不上只是少个设置页签，
        /// 不该在游戏加载阶段抛异常。
        /// </summary>
        public static void TryRegister(IModHelper helper, ModEntry mod, IManifest manifest, IMonitor monitor)
        {
            try
            {
                // ⚠ GMCM 装了吗？
                if (!helper.ModRegistry.IsLoaded(GmcmId)) return;
                if (Registered) return;

                // 拿 GMCM 的 API 实例。
                // ⚠ **必须用非泛型的 GetApi(string)**：泛型版 GetApi<T>() 要求 T 是
                //   **公开接口**，传 object 会报
                //   「Tried to map a mod-provided API to class 'System.Object';
                //     must be a public interface」。
                //   非泛型版返回 object、不做接口检查 —— 正好配合我们的反射方案
                //   （GMCM 的接口类型在不同版本里挪过位置，编译期引用不得）。
                var api = helper.ModRegistry.GetApi(GmcmId);
                if (api == null) return;                      // GMCM 装了但还没初始化完

                // GMCM 的 API 类型在不同版本里挪过位置（GenericModConfigMenu.IGenericModConfigMenuApi
                // / GenericModConfigMenu.Framework.Api），所以按**方法名**找，不按类型找。
                AddKeybindListMethod = FindMethod(api, "AddKeybindList");
                AddBoolMethod = FindMethod(api, "AddBoolOption");
                AddTextMethod = FindMethod(api, "AddTextOption");
                RegisterMethod = FindMethod(api, "Register");
                if (RegisterMethod == null) return;

                Api = api;
                RegisterOptions(mod, manifest, monitor);
            }
            catch (Exception ex)
            {
                // ⚠ 这里绝不能冒泡：GMCM 升级换了签名是**它**的事，
                //   不该让用户的游戏因为我们的快捷键功能起不来。
                monitor.Log($"接 GMCM 失败（快捷键仍可用，但要手改 config.json）：{ex.Message}",
                    LogLevel.Warn);
            }
        }

        /// <summary>按名字找方法（带 5 个参数的才算 Add*Option 那批）。</summary>
        private static MethodInfo FindMethod(object api, string name)
        {
            var t = api.GetType();
            return t.GetMethods(BindingFlags.Public | BindingFlags.Instance)
                .FirstOrDefault(m => m.Name == name && m.GetParameters().Length >= 5);
        }

        /// <summary>
        /// config.json 里的字符串 → [StardewModdingAPI.Utilities.KeybindList]。
        ///
        /// ⚠ 格式（SMAPI 的 KeybindList 解析格式）：
        ///   "F5"            = 单键
        ///   "F5, LeftShift" = 组合键（同一组用逗号）
        ///   "F5|F6"         = 多组（用竖线分隔，任一组按下都算）
        /// 空串 = 没绑。
        /// </summary>
        public static KeybindList ToKeybindList(string raw)
        {
            // ⚠ 认不出来时返回**空列表**而不是 null：GMCM 拿到 null 的 KeybindList
            //   可能在渲染时 NRE（整个 GMCM 菜单打不开）。空列表 = 「没绑」，
            //   GMCM 能正常显示「未设置」，用户还能自己按一个键设上。
            if (string.IsNullOrWhiteSpace(raw)) return EmptyKeybinds();
            var errors = new string[0];
            if (!KeybindList.TryParse(raw, out var kb, out errors)) return EmptyKeybinds();
            return kb;
        }

        /// <summary>空绑定列表（GMCM 认这个表示「未设置」）。</summary>
        private static KeybindList EmptyKeybinds() => new KeybindList(new Keybind[0]);

        /// <summary>
        /// [StardewModdingAPI.Utilities.KeybindList] → config.json 里的字符串。
        ///
        /// ⚠ GMCM 给回来的 KeybindList 可能有多个绑定（用户按了多次不同键），
        ///   全部都要留住——用户配「F5 或 F6 都能推」是合法诉求，
        ///   只留第一个就把他另一半配置吞了。
        /// </summary>
        public static string FromKeybindList(KeybindList kb)
        {
            if (kb == null) return "";
            var groups = kb.Keybinds
                .Where(b => b != null && b.Buttons != null && b.Buttons.Length > 0)
                .Select(b => string.Join(", ", b.Buttons.Select(x => x.ToString())))
                .Where(s => !string.IsNullOrWhiteSpace(s))
                .ToList();
            return string.Join("|", groups);
        }

        private static void RegisterOptions(ModEntry mod, IManifest manifest, IMonitor monitor)
        {
            if (Registered) return;
            Registered = true;
            _logWarn = msg => monitor.Log(msg, LogLevel.Warn);

            // ── 立即推送的快捷键 ──
            // ⚠ GMCM 的 AddKeybindList 的 get/set 用 **KeybindList** 类型，
            //   而我们 config.json 里存的是**字符串**（"F5" / "F5,LeftShift" / "F5|F6"）——
            //   字符串人可读、能手改，GMCM 那边要做类型转换。
            //   转换在 ToKeybindList / FromKeybindList 两个函数里。
            Invoke(AddKeybindListMethod,
                manifest,
                new Func<KeybindList>(() => ToKeybindList(mod.Config.PushKeybind)),
                new Action<KeybindList>(v => { mod.Config.PushKeybind = FromKeybindList(v); mod.SaveConfigAndReload(); }),
                new Func<string>(() => "立即推送存档"),
                new Func<string>(() => "在游戏里按下这个键，就把当前农场存档推送到手机。" +
                      "菜单或对话框打开时不响应（避免打字时误触）。"),
                "PushKeybind");

            // ── 重新配对的快捷键 ──
            Invoke(AddKeybindListMethod,
                manifest,
                new Func<KeybindList>(() => ToKeybindList(mod.Config.PairKeybind)),
                new Action<KeybindList>(v => { mod.Config.PairKeybind = FromKeybindList(v); mod.SaveConfigAndReload(); }),
                new Func<string>(() => "重新配对"),
                new Func<string>(() => "按下这个键会清除本机保存的信任并重新推送——手机上会要你输一次 6 位配对码。" +
                      "平时不用按：配对过一次之后保存就自动同步了。"),
                "PairKeybind");

            // ── 自动推送开关 ──
            Invoke(AddBoolMethod,
                manifest,
                new Func<bool>(() => mod.Config.AutoUpload),
                new Action<bool>(v => { mod.Config.AutoUpload = v; mod.SaveConfigAndReload(); }),
                new Func<string>(() => "每次保存后自动推送"),
                new Func<string>(() => "关掉后只有按快捷键才会推送。"),
                "AutoUpload");

            // ── HUD 提示开关 ──
            Invoke(AddBoolMethod,
                manifest,
                new Func<bool>(() => mod.Config.ShowHudNotifications),
                new Action<bool>(v => { mod.Config.ShowHudNotifications = v; mod.SaveConfigAndReload(); }),
                new Func<string>(() => "显示游戏内提示"),
                new Func<string>(() => "推送结果用游戏画面上的横幅提示（关掉就只在 SMAPI 控制台里看）。"),
                "ShowHudNotifications");

            // ── App 地址 ──
            // ⚠ allowedValues 传空数组（不限制取值），但 formatAllowedValue 仍要给一个
            //   **真的委托**：GMCM 在某些版本里会无条件调用它，传 null 会 NRE。
            //   这里返回原值即可（allowedValues 为空时本来也不会被显示）。
            Invoke(AddTextMethod,
                manifest,
                new Func<string>(() => mod.Config.AppIp ?? ""),
                new Action<string>(v => { mod.Config.AppIp = (v ?? "").Trim(); mod.SaveConfigAndReload(); }),
                new Func<string>(() => "手机地址（一般不用填）"),
                new Func<string>(() => "留空 = 自动发现手机。填了就在这个地址上找：" +
                      "例如 192.168.1.7 或 192.168.1.7:38220。"),
                new string[0],
                new Func<string, string, string>((value, allowed) => value),
                "AppIp");

            // 注册（GMCM 的 Register(mod, reset, save, titleScreenOnly)）
            Invoke(RegisterMethod,
                manifest,
                new Action(() => { }),
                new Action(() => { }),
                false);
        }

        /// <summary>
        /// 调用 GMCM 的 API 方法。
        ///
        /// ⚠ 全部参数按 GMCM 的**声明顺序**传。若某个版本改了签名，
        ///   Invoke 会抛 —— 外面 catch 住，最坏结果就是少几个设置项。
        /// </summary>
        private static void Invoke(MethodInfo method, params object[] args)
        {
            if (method == null) return;
            try
            {
                method.Invoke(Api, args);
            }
            catch (Exception ex)
            {
                var inner = ex is TargetInvocationException tie ? tie.InnerException : ex;
                // 单项失败不该影响其它项 —— 只记一条。
                MonitorLog(inner?.Message);
            }
        }

        /// <summary>注册失败时的记日志口（Monitor 在 TryRegister 时才拿到）。</summary>
        private static Action<string> _logWarn;

        private static void MonitorLog(string message)
        {
            _logWarn?.Invoke(message);
        }
    }
}
