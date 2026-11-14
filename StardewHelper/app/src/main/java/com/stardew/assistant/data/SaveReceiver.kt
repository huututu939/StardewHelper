package com.stardew.assistant.data

import android.content.Context
import android.util.Log
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * v2.67.0：局域网接收存档——把「手动传 zip」变成「电脑点一下」。
 *
 * ## 为什么是局域网而不是「登录 Steam 拉云存档」
 * Steam 确实有 `ICloudService/EnumerateUserFiles` 这套云存档 Web API，但官方文档
 * 写明它 "is mostly intended to enable cross-platform saves for **your game**"，
 * 且参数里的 `appid` 一律要求是 "Your App ID"。也就是说这套口子是给**游戏自己的
 * 开发者**用的（比如星露谷哪天出移动版），第三方 App 拿星露谷的 AppID 没有权限模型，
 * 拿 `read_cloud` token 也要以 Steamworks 注册应用的身份走授权。我们没有那个身份。
 *
 * 而云存档**不是独立数据源**：它只在游戏于那台 PC 启动时同步到本地文件夹。
 * 所以「拿到云存档」≡「把 PC 本地那个文件夹弄到手机上」——既然如此，
 * 省掉手动这一步就够了，不需要碰任何账号。
 *
 * ## v2.75.0：加了**验证码**（这是安全边界的真正所在）
 * 之前只要手机开着这个窗口，**同一 WiFi 下任何设备**都能 POST 一个 zip 进来。
 * 这个 App 会发给认识的人用，而宿舍/公司/咖啡厅的公共 WiFi 上，别人可能顺手
 * 往你的接收窗口塞东西。
 *
 * 现在的流程是「**电脑生成码 → 人在手机上核对 → 放行**」：
 * - 电脑端（send_save.ps1）生成一个 6 位随机码，显示在控制台，并放进请求头
 *   [HEADER_CODE]；
 * - 手机收到请求后**先不答复**，弹框让用户把电脑上看到的码敲进去；
 * - 手机比对「用户敲的」与「请求头里带的」是否一致（[MessageDigest.isEqual]，
 *   常量时间），一致才继续收 body、才落盘；
 * - 不一致 → 记一次错误，**留在同一个弹框里让用户重输**（不立刻断链），
 *   错到 [MAX_CODE_ATTEMPTS] 次上限才回 403 并关闭整个窗口。
 *
 * ### 信任边界（必须说清，别把它说得比实际强）
 * 真正的防线是「**攻击者碰不到你的手机**」——他没法替你敲码。
 * 但这不是万能的：
 * - 局域网是**明文 HTTP**，同网段能嗅探到请求头里的码；
 * - 配合社工（有人发消息让你「在手机上输入 482731」，同时他自己 POST 同一个码），
 *   仍可能骗过你。
 * 缓解手段：一次性作废（用过的码不再接受）、错误次数上限、输入超时、
 * 协议版本校验，以及手机 UI 上那句「这个码只应该出现在你电脑的窗口里」。
 *
 * ## 安全边界（这是功能的核心约束，不是补丁）
 * - **只监听、只收，不对外提供任何文件**：`GET /` 只回一句状态文本，
 *   没有任何路径能被读出去。App 的存档数据一个字节都不会被读到。
 * - **体积上限**：[MAX_BYTES] 与 [SaveImport] 的压缩包上限一致，
 *   超了直接断连，不落盘。**先看声明的 Content-Length 再收**，不给
 *   「先收下来再判断」的机会（否则一个恶意大包能把 cacheDir 撑爆）。
 * - **验证码通过之后才收 body**：收不到码的连接连一个字节的存档都落不了盘。
 * - **认证模型（c231 起升级为「信任设备」）**：旧设计只有验证码，这里原文写着
 *   「不写任何认证 token」。自动同步需要免人工确认，**必然**引入 DeviceId+Token——
 *   这是一次有意的边界变更，不是遗忘：Token = 「可静默推送」的能力。缓解：
 *   App 只存 Token 的 SHA-256（见 [TrustedDevices]）、配对仍需人工敲码
 *   （那一次敲码就是授权动作）、撤销立即生效、Token 失效统一 401 回退配对。
 *   收到的东西**照样只当不可信输入**——它只是候选存档字节，
 *   真正的校验（是不是 zip、能不能解析出 `<SaveGame>`）由 [SaveImport] 负责。
 *
 * ## 端口
 * 端口 **0（系统分配随机端口）**——固定端口在公共 WiFi 上等于挂了个靶子。
 * 代价是电脑端要知道端口，所以手机 UI 上显示的是 `IP:端口`（一起抄），
 * 电脑脚本从用户输入里解析端口。
 *
 * ## 为什么自己写最小 HTTP 而不上依赖
 * 只用 `ServerSocket` 读一个 POST body 而已，为此引三方库不划算；
 * 也避免第三方库带来的默认行为（重定向、日志上传等）超出「只收一个包」的边界。
 *
 * ## 线程
 * - accept 线程只负责 accept；**每个连接的处理丢给独立的工作线程**，
 *   否则「等用户敲码」会把 accept 堵住，新连接只能排队（而排队 = 并发猜码的机会）。
 * - UI 侧用 [onChanged] / [onCodeRequest] / [onReceived] 三个回调，
 *   都会从后台线程发起，**调用方负责切主线程**。
 */
object SaveReceiver {

    private const val TAG = "SaveReceiver"

    // ── 协议 ──
    /**
     * 协议版本。电脑脚本必须在请求头带 [HEADER_PROTO]: [PROTO_VERSION]。
     * 不带 = 旧版脚本（没有验证码）→ 直接 403「电脑脚本版本过旧，请重新下载」。
     * ⚠ 这条会让「App 升级了但电脑上还是旧 bat」变成一次硬失败——
     *   那是**故意的**（旧 bat 会被当成没有验证码的裸请求放进来），
     *   代价是必须给一句人话提示，App 里那个「下载 send_save.bat」按钮就是解药。
     */
    const val PROTO_VERSION = 2

    /** 验证码请求头字段名。放头里，**不进 URL query**（query 会进日志）。 */
    const val HEADER_CODE = "X-Stardew-Code"

    /** 协议版本请求头字段名。 */
    const val HEADER_PROTO = "X-Stardew-Proto"

    /** 信任设备 ID 请求头字段名（首配与自动上传都带）。 */
    const val HEADER_DEVICE_ID = "X-Stardew-Device-Id"

    /** 信任 Token 请求头字段名（只有自动上传带；有它 = 免验证码直收）。 */
    const val HEADER_TOKEN = "X-Stardew-Token"

    /** 电脑名请求头字段名（配对时展示/存储用，可缺省）。 */
    const val HEADER_DEVICE_NAME = "X-Stardew-Device-Name"

    /** 验证码位数。 */
    const val CODE_LEN = 6

    /**
     * 长度不对时用来做恒定时间比较的**固定长度探针**。
     *
     * ⚠ 用户敲的位数不对时也要走一次 isEqual（否则「长度不同就早返回」会泄露长度），
     *   但结果仍以「长度对不对」为准——否则期望码恰好是 000000 时，
     *   任何长度不对的输入都会被误判成正确。
     */
    private const val DUMMY_CODE = "000000"

    /** 单个接收窗口内允许的错误尝试总次数。**按窗口全局计数**，不是每连接各算。 */
    const val MAX_CODE_ATTEMPTS = 5

    /** 等用户敲码的总超时（毫秒）。超时按一次错误尝试处理并关掉该连接。 */
    const val CODE_TIMEOUT_MS = 60_000L

    /**
     * 同时处理的连接数上限。
     *
     * ⚠ 电脑端在「广播没回应」时会**扫同网段的 38217 端口**（一次 254 个），
     *   那些连接**连上但一个字节都不发**。不加这个上限：
     *   ① 会同时开出几百个线程（每个栈 ~1MB）；
     *   ② 每个连接在 readLine 上白等 30 秒。
     *   超额的一律 503 + 立刻关掉——它们本来也不是来传包的。
     */
    private const val MAX_ACTIVE_CONN = 20

    // ── 响应文案（不泄露验证码，不回显任何差异信息）──
    private const val MSG_BAD_CODE = "验证码不正确"

    /**
     * 401（Token 无效/已撤销）的响应文案。⚠ 与 403「验证码」严格区分：
     * ① 语义不同——401 是「曾经信任过、现在没了」，mod 收到要清 Token 重新配对；
     * ② 文案**不许出现「验证码」三个字**——旧 send_save.bat 靠响应体含不含
     *   「验证码」决定要不要换码重试，混了字会让 bat 走错分支。
     */
    private const val MSG_BAD_TOKEN = "信任已失效或被撤销，请在电脑上重新配对"
    private const val MSG_BUSY = "接收窗口忙，请稍后再试"
    private const val MSG_TOO_MANY = "错误次数过多，接收已停止，请重新打开"
    private const val MSG_OLD_PROTO = "电脑脚本版本过旧，请重新下载"
    private const val MSG_NEW_PROTO = "电脑脚本版本过新，请更新 App"

    /** 压缩包体积上限。与 [SaveImport] 同值，超了不落盘。 */
    private const val MAX_BYTES = 20L * 1024 * 1024

    /**
     * HTTP 监听端口段：优先 [PORT_BASE]，被占往后试 [PORT_SCAN] 个。
     *
     * ## 为什么不是完全随机端口
     * 端口随机的唯一好处是"别人不知道往哪打"，但**真正的防线是验证码**——
     * 端口可预测也过不了那一关。与其让用户每次手抄 `IP:端口`，
     * 不如把端口收在一个**固定小段**里：电脑端广播找不到时，
     * 扫这 20 个端口也就一两秒，用户**只需输 IP**。
     */
    private const val PORT_BASE = 38217
    private const val PORT_SCAN = 20

    /**
     * UDP 发现端口（固定）。
     *
     * 电脑端向这个端口广播 `SDH-FIND`，手机回一条 `SDH1 <tcp端口> <ip1,ip2...>`，
     * 电脑就自己知道往哪连——**用户不用输任何东西**。
     *
     * ⚠ 与 TCP 监听**同号不冲突**（不同协议栈），所以两边都能用 38217。
     * ⚠ 这会泄露一个事实：「这台手机开着接收窗口」——同网段的人能知道。
     *   但他们**拿不到任何数据**（没有正确验证码一律 403）。这是自觉的取舍：
     *   易用换一点点信息泄露，防线仍然握在用户手上。
     */
    const val DISCOVERY_PORT = 38217

    /**
     * 空闲多久没人连就自动关闭（毫秒）。v2.67.3：2 分钟不够——用户要拿手机开窗、
     *  走到电脑、下载 bat、选农场、抄 IP，实测一轮就超时了；5 分钟兜住正常流程，
     *  仍是有限终态（断言钉 IDLE_TIMEOUT_MS 在位）。
     */
    private const val IDLE_TIMEOUT_MS = 300_000L

    /** 用户在手机上的回答。 */
    sealed interface CodeReply {
        /** 用户敲了一串字符（可能对可能错，由 App 侧比对——**UI 不知道正确答案**）。 */
        data class Typed(val input: String) : CodeReply
        /** 用户主动取消本次接收。 */
        data object Cancel : CodeReply
    }

    sealed interface State {
        data object Idle : State
        /**
         * 正在接收。
         *
         * @param autoFind UDP 自动发现服务是否可用。
         *   **不可用时电脑端就找不到手机**，用户得手动输 IP —— 所以这个状态要如实报给用户，
         *   不能让"自动发现根本没起来"变成一句没头没尾的"没找到"。
         */
        data class Listening(
            val port: Int,
            val addresses: List<String>,
            val autoFind: Boolean = true,
        ) : State
        /** 收到一个带验证码的请求，正在等用户核对。 */
        data class AwaitingCode(val attemptsLeft: Int) : State
        /** 收到了候选包（还没解析）。[file] 在 parse 之后由调用方删除。 */
        data class Received(val file: File) : State
        data class Failed(val msg: String) : State
        /**
         * v2.67.3：服务**自己结束**时必报此态。首版超时/收包后线程退出，
         * 抽屉却停在「正在等电脑推送…」——用户照着这个假状态去电脑上发，
         * 永远「连接被意外关闭」，两端都看不出问题。UI 必须与端口同生死。
         */
        data class Stopped(val reason: String) : State
    }

    @Volatile
    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)

    /** 本窗口内**已用过**的验证码（一次性作废）。 */
    private val usedCodes = ConcurrentHashMap.newKeySet<String>()

    /** 本窗口内的错误尝试次数（全局，不是每连接各算）。 */
    private val wrongAttempts = AtomicInteger(0)

    /** 当前有几个连接在等用户敲码。>0 时新连接直接拒（不排队）。 */
    private val pendingVerifications = AtomicInteger(0)

    /** 当前正在处理的连接数（用来挡住端口扫描那种"连上不说话"的连接）。 */
    private val activeConns = AtomicInteger(0)

    /** 窗口结束的原因（决定 UI 报什么）。 */
    @Volatile
    private var stopReason: String = "等待超时，已自动停止接收"

    /** 实际绑定/展示给用户的地址（绑 WiFi 时是那一个，回退时是全部非回环）。 */
    @Volatile
    private var boundAddresses: List<String> = emptyList()

    /** UDP 发现服务（让电脑端能自己找到本机）。 */
    @Volatile
    private var discovery: DatagramSocket? = null

    /**
     * 开始监听。**立即返回**，服务在自己的线程上跑。
     *
     * @param onChanged 状态回调，可能来自任意线程，调用方负责切主线程。
     * @param onReceived 收到一个包后的回调（参数是 cacheDir 里的临时文件）。
     * @param onCodeRequest 需要用户核对验证码时的回调：
     *   `(请求里的码, 剩余次数, 提交) -> Unit`。**会从后台线程发起**，调用方负责切主线程。
     *   提交 [CodeReply.Typed] 带上用户敲的字符串——**比对在 App 侧做**，
     *   UI 不知道正确答案；敲错时会在**同一个连接**上再次回调（带新的剩余次数），
     *   让用户原地重输；[CodeReply.Cancel] 直接关连接。
     * @param onAutoImport c231：信任设备（Token 自动上传 / 首次配对）的
     *   **连接内同步导入**，在连接工作线程调用；口径必须与确认抽屉的提交
     *   逐行一致（SettingsPage 负责）。返回的 [SaveImport.Result] 决定
     *   JSON 应答与 Token 颁发。
     * @param onAutoImported 自动/配对导入结束后的通知（ok + 给用户看的一句话），
     *   **从连接线程发起**，调用方负责切主线程。
     * @param autoMode c232：常驻自动监听（LanReceiveController 起）。**无空闲
     *   自杀**、旧式码路径（bat/手动码）也改为连接内同步导入；手动会话
     *   （抽屉里点「开始接收」）传 false，行为与 v2.142 之前逐字一致。
     */
    @Synchronized
    fun start(
        ctx: Context,
        onChanged: (State) -> Unit,
        onReceived: (File) -> Unit,
        onCodeRequest: (code: String, attemptsLeft: Int, submit: (CodeReply) -> Unit) -> Unit,
        onAutoImport: (File) -> SaveImport.Result,
        onAutoImported: (ok: Boolean, msg: String) -> Unit,
        autoMode: Boolean = false,
    ) {
        if (running.get()) {
            onChanged(State.Failed("已经在接收中，先关掉再重新开始"))
            return
        }
        val opened = openSocket() ?: run {
            onChanged(State.Failed("端口被占用或不可用，换个时间再试"))
            return
        }
        val (sock, addrs) = opened
        server = sock
        boundAddresses = addrs
        running.set(true)
        wrongAttempts.set(0)
        usedCodes.clear()
        stopReason = "等待超时，已自动停止接收"
        // ⚠ 先起发现服务再报状态：自动发现起不来时要把这件事如实告诉用户
        //   （他才知道电脑端为什么找不到手机），不能静默失败。
        val autoFind = startDiscovery()
        onChanged(State.Listening(sock.localPort, addrs, autoFind))

        Thread({
            serve(ctx, sock, onChanged, onReceived, onCodeRequest, onAutoImport, onAutoImported, autoMode)
        }, "save-receiver").apply {
            isDaemon = true
            start()
        }
    }

    /** 停止监听并清掉残留。 */
    @Synchronized
    fun stop(reason: String = "已停止接收") {
        if (reason.isNotBlank()) stopReason = reason
        running.set(false)
        runCatching { server?.close() }
        server = null
        // 发现服务也要关：留着它就等于端口关了但广播还能问到「手机在接收」
        runCatching { discovery?.close() }
        discovery = null
    }

    /**
     * 开监听端口。
     *
     * 端口在 [PORT_BASE]..[PORT_BASE]+[PORT_SCAN] 这段里取（被占就往后试），
     * 电脑端扫这一段就能找到 —— 所以手机这边必须是**固定段**，不是随机大端口。
     *
     * ⚠ 绑定地址：**优先绑 WiFi 网卡的具体地址**（这样移动数据、VPN 上的请求根本到不了），
     *   但**拿不到 WiFi 地址就回退绑全部网卡**——绑地址失败等于功能直接不可用，
     *   而真正的安全边界是验证码，不是绑定地址。回退时 UI 会照常显示可用地址。
     */
    private fun openSocket(): Pair<ServerSocket, List<String>>? {
        for (addr in wifiAddresses()) {
            for (p in PORT_BASE until PORT_BASE + PORT_SCAN) {
                val s = runCatching { ServerSocket(p, 8, addr) }.getOrNull() ?: continue
                return s to listOf(addr.hostAddress ?: continue)
            }
        }
        for (p in PORT_BASE until PORT_BASE + PORT_SCAN) {
            val s = runCatching { ServerSocket(p) }.getOrNull() ?: continue
            return s to allNonLoopbackV4()
        }
        return null
    }

    /**
     * 起 UDP 发现服务：回应同网段电脑的广播，让它自己找到本机。
     *
     * 收到 `SDH-FIND` → 回 `SDH1 <tcp端口> <ip1,ip2,...>`。
     * 回**所有**候选 IP 是为了覆盖「手机同时有 WiFi 与移动数据」的情况，
     * 电脑端会挨个试，挑能连上的那个。
     */
    private fun startDiscovery(): Boolean {
        val s = runCatching { DatagramSocket(DISCOVERY_PORT) }.getOrNull()
        if (s == null) {
            // ⚠ 静默失败是最坏的一种失败：用户只会看到"电脑没找到手机"，
            //   完全不知道是这台手机上没有发现服务。必须记日志 + 报给 UI。
            Log.w(TAG, "discovery: UDP $DISCOVERY_PORT bind failed — 自动发现不可用")
            return false
        }
        Log.i(TAG, "discovery: listening on UDP $DISCOVERY_PORT")
        discovery = s
        Thread({
            val buf = ByteArray(256)
            while (running.get()) {
                // ⚠ DatagramSocket.receive 有两个重载：收 byte[] 只回**字节数**，
                //   拿不到来源地址 → 要用 DatagramPacket 那个重载。
                val p = DatagramPacket(buf, buf.size)
                if (runCatching { s.receive(p) }.isFailure) break
                val msg = String(buf, 0, p.length, Charsets.US_ASCII)
                if (!msg.startsWith("SDH-FIND")) continue
                val port = server?.localPort ?: 0
                val addrs = boundAddresses.ifEmpty { allNonLoopbackV4() }
                val body = "SDH1 $port ${addrs.joinToString(",")}"
                val bytes = body.toByteArray(Charsets.US_ASCII)
                Log.i(TAG, "discovery: reply to ${p.address} with $body")
                runCatching { s.send(DatagramPacket(bytes, bytes.size, p.address, p.port)) }
            }
        }, "save-discovery").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /**
     * accept 循环。
     *
     * ⚠ 每个连接的处理丢给**独立工作线程**：否则「等用户敲码」会把 accept 堵住，
     *   新连接只能在 backlog 里排队——排队就等于给了并发猜码的机会。
     *   这里改成：accept 线程立刻回来接下一个，命中「已有待验证连接」的直接 503 关掉。
     */
    private fun serve(
        ctx: Context,
        sock: ServerSocket,
        onChanged: (State) -> Unit,
        onReceived: (File) -> Unit,
        onCodeRequest: (code: String, attemptsLeft: Int, submit: (CodeReply) -> Unit) -> Unit,
        onAutoImport: (File) -> SaveImport.Result,
        onAutoImported: (ok: Boolean, msg: String) -> Unit,
        autoMode: Boolean,
    ) {
        try {
            while (running.get()) {
                // c232：自动会话**常驻**（用户配过对、App 开着就该能收），
                // 空闲自杀只属于手动窗口——睡个午觉回来端口就没了的坑，别再挖。
                sock.soTimeout = if (autoMode) 0 else IDLE_TIMEOUT_MS.toInt()
                val client = runCatching { sock.accept() }.getOrNull() ?: break
                if (pendingVerifications.get() > 0) {
                    // 同一时刻只允许一个待验证连接：新来的直接拒，不排队、不叠加提示
                    runCatching {
                        respond(
                            client.getOutputStream(), 503, "Service Unavailable",
                            MSG_BUSY.toByteArray(),
                        )
                    }
                    runCatching { client.close() }
                    continue
                }
                // ⚠ 连上但不说话的一律挡掉：电脑端扫端口时会一次来几百个这种连接。
                if (activeConns.incrementAndGet() > MAX_ACTIVE_CONN) {
                    activeConns.decrementAndGet()
                    runCatching {
                        respond(
                            client.getOutputStream(), 503, "Service Unavailable",
                            MSG_BUSY.toByteArray(),
                        )
                    }
                    runCatching { client.close() }
                    continue
                }
                Thread({
                    try {
                        handle(ctx, client, onChanged, onReceived, onCodeRequest,
                            onAutoImport, onAutoImported, autoMode)?.let { onReceived(it) }
                    } catch (e: Throwable) {
                        Log.i(TAG, "handle failed: ${e.message}")
                    } finally {
                        activeConns.decrementAndGet()
                        runCatching { client.close() }
                    }
                }, "save-conn").apply {
                    isDaemon = true
                    start()
                }
            }
        } finally {
            runCatching { sock.close() }
            server = null
            running.set(false)
            Log.i(TAG, "server closed")
            // ⚠ c240：**发现服务也要一起关**。
            //   原来这里只关了 TCP 的 sock，discovery（UDP 那个 DatagramSocket）还开着
            //   ⇒ 电脑广播 SDH-FIND 仍能收到回包，但回包里的端口号是 0
            //   （`val port = server?.localPort ?: 0`，server 已经 null）。
            //   那个状态对电脑端是**最坏的一种**：
            //     - mod 看到 "SDH1 0 <ip>" 会因 `port <= 0` 直接跳过这个应答，
            //       退到直探也连不上（端口确实没了）→ 用户看到「没扫描到手机」；
            //     - 但通知栏的常驻通知**还在**（前台服务独立活着），
            //       用户会以为 App 在收、实际什么都没在收。
            //   实测（2026-11-09，魅族 20）：设备上就是这个状态 ——
            //   UDP 回 "SDH1 0 …"、TCP 端口在 /proc/net/tcp 里整个消失。
            //   ⇒ 端口没了，发现服务也必须停，否则**广播在说谎**。
            runCatching { discovery?.close() }
            discovery = null
            // ⚠ 端口关了 UI 必须跟着变——停在 Listening 是骗人（v2.67.3 修的就是它）。
            onChanged(State.Stopped(stopReason))
        }
    }

    /**
     * 处理一个连接。
     *
     * 顺序是**两阶段**的：
     * ① 读头（拿验证码 / 协议版本 / 声明长度）→ 校验 → **等用户核对验证码**；
     * ② 核对通过才收 body、才落盘。
     *
     * ⚠ 为什么不能沿用旧顺序（旧版是先收 body、先落盘、再回应）：
     *   那样攻击者**不用拿到验证码**就能先往 cacheDir 里灌 20MB 垃圾。
     */
    private fun handle(
        ctx: Context,
        client: Socket,
        onChanged: (State) -> Unit,
        onReceived: (File) -> Unit,
        onCodeRequest: (code: String, attemptsLeft: Int, submit: (CodeReply) -> Unit) -> Unit,
        onAutoImport: (File) -> SaveImport.Result,
        onAutoImported: (ok: Boolean, msg: String) -> Unit,
        autoMode: Boolean,
    ): File? {
        // ⚠ 读请求头只给 2 秒：电脑端扫端口时会一次来几百个「连上不说话」的连接，
        //   它们占着 activeConns 名额不放 → 扫描刚结束就上传的话，那个上传连接
        //   会被判成「忙」直接 403。2 秒足够读完请求头（正常客户端连上就发），
        //   也够扫描连接尽快让位。收 body 是后面的事，每次 read 单独计时，慢速上传不受影响。
        client.soTimeout = 2_000
        val ins = client.getInputStream()
        val requestLine = readLine(ins) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        // 先把请求头读完，否则 body 会被当成 header 解析
        var contentLength = 0L
        var code: String? = null
        var proto: String? = null
        var deviceId: String? = null
        var token: String? = null
        var deviceName: String? = null
        while (true) {
            val line = readLine(ins) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                when {
                    name.equals("Content-Length", true) -> contentLength = value.toLongOrNull() ?: 0L
                    name.equals(HEADER_CODE, true) -> code = value
                    name.equals(HEADER_PROTO, true) -> proto = value
                    name.equals(HEADER_DEVICE_ID, true) -> deviceId = value
                    name.equals(HEADER_TOKEN, true) -> token = value
                    name.equals(HEADER_DEVICE_NAME, true) -> deviceName = value
                }
            }
        }

        val outs = client.getOutputStream()

        // 探活：回一句状态 + 当前端口（电脑脚本靠它知道往哪传）
        if (method == "GET" && path == "/") {
            respond(outs, 200, "OK", statusText().toByteArray())
            return null
        }
        if (method != "POST" || path != "/upload") {
            respond(outs, 404, "Not Found", "no such endpoint".toByteArray())
            return null
        }

        // 错误次数已达上限：直接拒，并把整个窗口收掉
        if (wrongAttempts.get() >= MAX_CODE_ATTEMPTS) {
            respond(outs, 503, "Service Unavailable", MSG_TOO_MANY.toByteArray())
            stop(MSG_TOO_MANY)
            return null
        }

        // 协议版本：缺失 = 旧脚本（没有验证码的裸请求）
        val p = proto?.trim()?.toIntOrNull()
        if (p == null) {
            respond(outs, 403, "Forbidden", MSG_OLD_PROTO.toByteArray())
            return null
        }
        if (p != PROTO_VERSION) {
            val m = if (p < PROTO_VERSION) MSG_OLD_PROTO else MSG_NEW_PROTO
            respond(outs, 403, "Forbidden", m.toByteArray())
            return null
        }

        // ── c231 三路分派 ──
        // 有 Token = 信任设备的自动上传：免码框、连接内同步导入、JSON 应答。
        // 其余 = 旧流程（首次配对走验证码 + bat / 手动），逐字未动。
        if (token != null) {
            return handleTrustedUpload(
                ctx, deviceId, token, contentLength, ins, outs,
                onAutoImport, onAutoImported,
            )
        }

        // 验证码：必须是 CODE_LEN 位数字
        val c = code?.filter { it.isDigit() }
        if (c == null || c.length != CODE_LEN) {
            respond(outs, 403, "Forbidden", MSG_BAD_CODE.toByteArray())
            return null
        }
        // 一次性：同一个码不能用第二次（防并发重放）
        if (!usedCodes.add(c)) {
            respond(outs, 403, "Forbidden", MSG_BAD_CODE.toByteArray())
            return null
        }

        // 体积上限：先看声明的长度，超了直接拒收，不落盘
        if (contentLength <= 0 || contentLength > MAX_BYTES) {
            respond(outs, 413, "Payload Too Large",
                "压缩包应在 20MB 以内（正常 Steam 存档压完不到 2MB）".toByteArray())
            return null
        }

        // ── 阶段 ①：等用户核对验证码 ──
        if (!awaitCode(ctx, c, outs, onChanged, onCodeRequest)) return null

        // ── 阶段 ②：核对通过，才收 body（c231：收包+验头提出来共用）──
        val dest = receiveZip(ctx, ins, outs, contentLength) ?: return null

        Log.i(TAG, "received ok -> ${dest.name}")

        // ── c232：自动会话里的旧式请求（bat / 手动码）。码已人工敲过 = 本次授权，
        //    连接内同步导入 + 一句人话应答（bat 会原样打印）；不再走确认抽屉——
        //    自动会话的用户已选定自动化，弹窗授权本身就是那道确认。文案走 Toast。
        if (autoMode && deviceId == null) {
            return finishAutoImport(
                ctx, dest, outs, onAutoImport, onAutoImported,
                pairing = null, asJson = false,
            )
        }

        // ── c231：带 DeviceId 的请求 = 首次配对（敲码即授权）。首配即导入：
        //    连接内同步导入，成功才颁发 Token——坏包不值得信任。
        if (deviceId != null) {
            return finishAutoImport(
                ctx, dest, outs, onAutoImport, onAutoImported,
                pairing = deviceId to deviceName,
            )
        }

        respond(outs, 200, "OK", "收到，正在解析…".toByteArray())
        onChanged(State.Received(dest))
        return dest
    }

    /**
     * 收 body 落盘 + zip 头校验（c231 从 [handle] 提出来共用：配对/自动两条路的
     * 「怎么收一个包」必须一模一样）。失败时已回过响应并删了临时文件。
     */
    private fun receiveZip(
        ctx: Context,
        ins: java.io.InputStream,
        outs: java.io.OutputStream,
        contentLength: Long,
    ): File? {
        val dest = File(ctx.cacheDir, "save_received_${System.currentTimeMillis()}.zip")
        var written = 0L
        val buf = ByteArray(64 * 1024)
        try {
            dest.outputStream().use { out ->
                while (written < contentLength) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), contentLength - written).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                }
            }
        } catch (t: Throwable) {
            runCatching { dest.delete() }
            throw t
        }
        if (written != contentLength) {
            Log.i(TAG, "short body written=$written expected=$contentLength")
            runCatching { dest.delete() }
            respond(outs, 400, "Bad Request", "传输中断，请重试".toByteArray())
            return null
        }
        // 头部签名不是 zip 就直接拒：省得后面白解析一次。
        dest.inputStream().use { s ->
            val head = ByteArray(2)
            val n = s.read(head)
            val isZip = n == 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            if (!isZip) {
                Log.i(TAG, "received non-zip head=${head.take(2).map { it.toInt() and 0xFF }}")
                runCatching { dest.delete() }
                respond(outs, 415, "Unsupported Media Type",
                    "这不是 zip。请把农场文件夹用 Windows 右键「压缩为 ZIP 文件」打包。".toByteArray())
                return null
            }
        }
        return dest
    }

    // ── c231：信任设备（自动上传 / 首次配对）────────────────────────

    /** 小 JSON 应答体（org.json，App 内既有依赖；只装扁平键值，不装 null）。 */
    private fun jsonBytes(vararg pairs: Pair<String, Any>): ByteArray =
        org.json.JSONObject().apply {
            for ((k, v) in pairs) put(k, v)
        }.toString().toByteArray(Charsets.UTF_8)

    /**
     * 信任设备的自动上传（免验证码）。校验失败一律 401——与 403（码错）严格
     * 区分，mod 收到 401 会清 Token 回到配对。导入在**本连接线程内同步完成**：
     * 响应体要如实回答「导没导进去」，「先回 200 再异步解析」的旧时序对它不适用。
     */
    private fun handleTrustedUpload(
        ctx: Context,
        deviceId: String?,
        token: String,
        contentLength: Long,
        ins: java.io.InputStream,
        outs: java.io.OutputStream,
        onAutoImport: (File) -> SaveImport.Result,
        onAutoImported: (Boolean, String) -> Unit,
    ): File? {
        // 与旧路同款体积闸：先看声明的长度，超了不落盘
        if (contentLength <= 0 || contentLength > MAX_BYTES) {
            respond(outs, 413, "Payload Too Large",
                "压缩包应在 20MB 以内（正常 Steam 存档压完不到 2MB）".toByteArray())
            return null
        }
        // DeviceId 缺失 = 头不完整，按无效 Token 处理（401，不往 403 里混）
        if (deviceId == null || !TrustedDevices.verifyBlocking(ctx, deviceId, token)) {
            Log.i(TAG, "trusted upload rejected: token invalid (device=${deviceId?.take(8) ?: "none"})")
            respond(outs, 401, "Unauthorized",
                jsonBytes("status" to "unauthorized", "message" to MSG_BAD_TOKEN),
                "application/json")
            return null
        }
        val dest = receiveZip(ctx, ins, outs, contentLength) ?: return null
        return finishAutoImport(ctx, dest, outs, onAutoImport, onAutoImported, pairing = null)
    }

    /**
     * 连接内同步导入 + JSON 应答（首配与自动上传共用）。
     *
     * 导入口径与确认抽屉**逐行一致**（备份 → 并集只补不删 → 报告 → 农场缓存），
     * 实现在 SettingsPage 传入的 [onAutoImport]。临时文件导入完就删——它不再走
     * UI 的 parse-after 流程。
     *
     * @param pairing 非 null = 首次配对（deviceId to 原始电脑名）：导入成功
     *   （哪怕无可勾项）就颁发 Token；导入失败不发 Token，mod 下次保存再来配对。
     * @param asJson true = JSON 应答（mod 读）；false = 人话纯文本（bat 会把响应体
     *   原样打印给用户看，c232 自动会话的 bat 路径用）。
     */
    private fun finishAutoImport(
        ctx: Context,
        dest: File,
        outs: java.io.OutputStream,
        onAutoImport: (File) -> SaveImport.Result,
        onAutoImported: (Boolean, String) -> Unit,
        pairing: Pair<String, String?>?,
        asJson: Boolean = true,
    ): File? {
        val r = try {
            onAutoImport(dest)
        } catch (t: Throwable) {
            Log.i(TAG, "auto import failed: ${t.message}")
            SaveImport.Result.Err("导入失败：${t.message ?: t.javaClass.simpleName}")
        }
        runCatching { dest.delete() }
        when (r) {
            is SaveImport.Result.Ok -> {
                if (pairing != null) {
                    val token = TrustedDevices.pairBlocking(ctx, pairing.first, pairing.second)
                    respond(outs, 200, "OK",
                        jsonBytes("status" to "paired", "token" to token, "keys" to r.keys.size),
                        "application/json")
                    Log.i(TAG, "paired device=${pairing.first.take(8)} keys=${r.keys.size}")
                    onAutoImported(
                        true,
                        "已配对电脑「${TrustedDevices.sanitizeName(pairing.second, pairing.first)}」，以后保存自动导入",
                    )
                } else if (r.keys.isEmpty()) {
                    // 与手动导入同口径：无可勾项 = 不动库（手动是 Toast 提示不弹抽屉）。
                    val tip = "这份存档里没有可自动勾选的进度"
                    if (asJson) {
                        respond(outs, 200, "OK",
                            jsonBytes("status" to "noop", "message" to tip), "application/json")
                    } else {
                        respond(outs, 200, "OK", "已收到，$tip".toByteArray())
                    }
                    onAutoImported(true, "收到存档，但没有可自动勾选的进度")
                } else {
                    if (asJson) {
                        respond(outs, 200, "OK",
                            jsonBytes("status" to "imported", "keys" to r.keys.size),
                            "application/json")
                    } else {
                        respond(outs, 200, "OK", "已自动导入，勾上 ${r.keys.size} 条".toByteArray())
                    }
                    onAutoImported(true, "已自动导入存档：勾上 ${r.keys.size} 条")
                }
            }
            is SaveImport.Result.Err -> {
                if (asJson) {
                    respond(outs, 422, "Unprocessable Entity",
                        jsonBytes("status" to "error", "message" to r.msg), "application/json")
                } else {
                    respond(outs, 422, "Unprocessable Entity", r.msg.toByteArray())
                }
                onAutoImported(false, "自动导入失败：${r.msg}")
            }
        }
        return null
    }

    /**
     * 等用户核对验证码。
     *
     * ## 比对在**这一侧**做
     * UI 只负责收集用户敲的字符串并回传——它**不知道正确答案**，
     * 也不可能知道（码是电脑生成的，手机上并没有"期望值"）。
     * 所以「用户敲的」与「请求头里带的」在这里比。
     *
     * ## 输错不立刻断链
     * 输错 → 记一次 → **在同一个连接上再问一次**（带新的剩余次数），
     * 让用户原地改。一直错到 [MAX_CODE_ATTEMPTS] 次上限才回 403 并收掉窗口。
     *
     * @return true = 核对通过，可以继续收 body；false = 没通过（已回过错误响应）。
     */
    private fun awaitCode(
        ctx: Context,
        code: String,
        outs: java.io.OutputStream,
        onChanged: (State) -> Unit,
        onCodeRequest: (code: String, attemptsLeft: Int, submit: (CodeReply) -> Unit) -> Unit,
    ): Boolean {
        pendingVerifications.incrementAndGet()
        try {
            val expected = code.toByteArray(Charsets.UTF_8)
            while (true) {
                val left = MAX_CODE_ATTEMPTS - wrongAttempts.get()
                if (left <= 0) {
                    respond(outs, 503, "Service Unavailable", MSG_TOO_MANY.toByteArray())
                    stop(MSG_TOO_MANY)
                    return false
                }
                // ⚠ 超时是**这一次询问**的预算：用户输错后还要看一眼提示、改一下再输，
                //   那个时间不该从上一轮的剩余时间里扣。
                //   （初版把 60 秒当成「整个等待期」的总预算，实测太紧——
                //   人还没想好输什么就超时了，弹窗自己消失，看着像没反应。）
                //   「窗口不会被无限拖住」由**次数上限**保证：5 次 × 60 秒 = 最多 5 分钟。
                val deadline = System.currentTimeMillis() + CODE_TIMEOUT_MS
                val answered = CountDownLatch(1)
                val reply = AtomicReference<CodeReply?>(null)
                onChanged(State.AwaitingCode(left))
                onCodeRequest(code, left) { r ->
                    reply.set(r)
                    answered.countDown()
                }
                val remain = deadline - System.currentTimeMillis()
                if (remain <= 0 || !answered.await(remain, TimeUnit.MILLISECONDS)) {
                    // 超时：按一次错误尝试处理并关掉该连接
                    wrongAttempts.incrementAndGet()
                    // ⚠ UI 必须回到**真实状态**：这次连接没了，但**服务窗口还开着**，
                    //   可以让电脑再发一次。停在「等待验证码确认…」是假状态
                    //   （和 v2.67.3 修过的「端口关了 UI 还说在等」是同款坑）。
                    backToListening(ctx, "等验证码超时了，让电脑再发一次", onChanged)
                    respond(outs, 403, "Forbidden", MSG_BAD_CODE.toByteArray())
                    return false
                }
                when (val r = reply.get()) {
                    is CodeReply.Cancel -> {
                        // 用户主动取消：不计入错误（不是猜错）。连接关掉，窗口还开着。
                        backToListening(ctx, null, onChanged)
                        respond(outs, 403, "Forbidden", MSG_BAD_CODE.toByteArray())
                        return false
                    }
                    is CodeReply.Typed -> {
                        val got = r.input.filter { it.isDigit() }
                        // ⚠ 长度不对时也要走一次 isEqual（拿固定长度的探针），
                        //   否则「长度不同就早返回」会把长度泄露成一个 oracle。
                        //   但结果仍以 lengthOk 为准——否则期望码恰好是 000000 时，
                        //   任何长度不对的输入都会被误判成正确。
                        val lengthOk = got.length == CODE_LEN
                        val probe = if (lengthOk) got else DUMMY_CODE
                        val same = MessageDigest.isEqual(expected, probe.toByteArray(Charsets.UTF_8))
                        if (same && lengthOk) return true
                    }
                    null -> Unit
                }
                // 输错：记一次，留在同一个连接上让用户原地重输
                wrongAttempts.incrementAndGet()
                if (wrongAttempts.get() >= MAX_CODE_ATTEMPTS) {
                    respond(outs, 503, "Service Unavailable", MSG_TOO_MANY.toByteArray())
                    stop(MSG_TOO_MANY)
                    return false
                }
            }
        } finally {
            pendingVerifications.decrementAndGet()
        }
    }

    /**
     * 回到「还在接收」状态。
     *
     * ⚠ 用它而不是让 UI 停在 [State.AwaitingCode]：**那条连接已经结束了**，
     *   但**服务窗口还开着**（可以让电脑再发一次）——UI 必须说实话。
     *   停在「等待验证码确认…」等于骗用户（和 v2.67.3 修过的
     *   「端口关了 UI 还说在等」是同款坑）。
     *
     * @param tip 非空时给用户一句提示（超时 / 取消）。
     */
    private fun backToListening(
        ctx: Context,
        tip: String?,
        onChanged: (State) -> Unit,
    ) {
        if (tip != null) {
            runCatching {
                android.widget.Toast.makeText(ctx, tip, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        onChanged(
            State.Listening(
                server?.localPort ?: 0,
                boundAddresses.ifEmpty { allNonLoopbackV4() },
                discovery != null,
            )
        )
    }

    private fun readLine(ins: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = ins.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().removeSuffix("\r")
            sb.append(c.toChar())
            if (sb.length > 8192) return null   // 防畸形请求撑爆内存
        }
    }

    private fun respond(
        out: java.io.OutputStream,
        code: Int,
        reason: String,
        body: ByteArray,
        contentType: String = "text/plain; charset=utf-8",
    ) {
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        runCatching {
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(body)
            out.flush()
        }
    }

    /** 探活响应：只回端口与一句人话，**不泄露验证码**。 */
    private fun statusText(): String {
        val port = server?.localPort ?: 0
        return "星露小助手正在等存档包，用 POST 发送 zip。\nPORT=$port\n"
    }

    /**
     * WiFi 网卡上的 IPv4 地址。
     *
     * ⚠ 按**网卡名**判定：移动数据（rmnet/wwan/ccmni）、VPN（tun/tap/ppp/ipsec）
     *   一律排除；WiFi 通常是 wlan/wifi/swlan/ap/p2p。
     *   某些 ROM 的命名不规矩 → 这里可能返回空 → [openSocket] 回退绑全部网卡。
     */
    private fun wifiAddresses(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                val n = ni.name.lowercase()
                if (n.startsWith("rmnet") || n.startsWith("wwan") || n.startsWith("ccmni") ||
                    n.startsWith("tun") || n.startsWith("tap") || n.startsWith("ppp") ||
                    n.startsWith("ipsec") || n.startsWith("vpn") || n.startsWith("docker")
                ) continue
                val isWifi = n.contains("wlan") || n.contains("wifi") || n.contains("swlan") ||
                    n.startsWith("ap") || n.contains("p2p")
                if (!isWifi) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        out.add(a)
                    }
                }
            }
        }
        return out
    }

    /** 所有非回环 IPv4（回退绑定时用来告诉用户能连哪个）。 */
    private fun allNonLoopbackV4(): List<String> {
        val out = linkedSetOf<String>()
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        if (ip.startsWith("127.")) continue
                        out.add(ip)
                    }
                }
            }
        }
        return out.toList()
    }

    /** 供 UI 显示「当前 WiFi 名称」，帮用户判断连的是不是同一个网。 */
    fun wifiName(ctx: Context): String? = runCatching {
        val svc = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
        @Suppress("DEPRECATION")
        svc?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
    }.getOrNull()

    /** 供诊断：确认端口确实起来了。 */
    fun isRunning(): Boolean = running.get()
}
