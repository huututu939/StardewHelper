# 星露小助手 —— 把电脑上的星露谷存档发到手机（双击 send_save.bat 即可运行）
#
# 前提：
#   ① 手机与电脑连**同一个 WiFi**；
#   ② 手机上打开 星露小助手 → 设置 → 导入游戏存档 → 从电脑接收 → 开始接收。
#      （脚本会广播一次自己找这台手机，**通常不用输任何地址**；
#        万一广播被防火墙/网络挡了，才会问你 IP —— 端口不用填，它自己扫。）
#   ③ 找到手机后会弹一次验证码输入框——把下面显示的 6 位数字敲进去。
# 用的是 Windows 自带的 PowerShell（Win10/11 都自带），不需要装 Python 或任何软件。
#
# ⚠ 为什么不用 Compress-Archive：它在 PowerShell 5.1 上会把**农场文件夹名塞进压缩包
#   路径**（`Farm/Mods/x/a.txt`）而不是把内容放根，还带反斜杠的历史问题。
#   实测对比过：.NET 的 ZipFile 顶层直接是 `SaveGameInfo` / `Mods/x/a.txt`，
#   正是 App 端挑 entry 想要的形态，所以用 [System.IO.Compression.ZipFile]。

param(
    [string]$HostArg = '',
    [string]$FarmArg = ''
)
# ⚠ 参数名不能叫 $Host——那是 PowerShell 的内置只读变量（当前主机对象），
#   赋参会报错。
# ⚠ 也**不能**叫 $Farm：PowerShell 变量名大小写不敏感，`$Farm` 会与农场列表
#   `$farmList` 撞成同一个变量（首版那个列表就叫 `$farms`），`+= $d` 实际在往
#   字符串里追加，最后 `Where-Object { $_.Name -eq ... }` 拿到的不是 DirectoryInfo，
#   CreateFromDirectory 就报「路径语法不正确」。首版就是栽在这里。

$ErrorActionPreference = 'Stop'
$tmp = $null

# -File 方式下未声明的参数会落进 $args，两边都收一遍，
# 这样 `--host=1.2.3.4` 与 `--host 1.2.3.4` 两种写法都认。
$HostIP = $HostArg
$FarmName = $FarmArg
foreach ($a in $args) {
    if ($a -like '--host=*') { $HostIP = $a.Substring(7) }
    elseif ($a -like '--farm=*') { $FarmName = $a.Substring(7) }
    elseif ($a -like '--host') { continue }
    elseif ($a -like '--farm') { continue }
}

function Say($m) { Write-Host $m }

try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    # ── 工具：找手机 / 探端口 ─────────────────────────────────────────
    function Test-Port([string]$ip, [int]$port) {
        # ⚠ 只探「连不连得上」，不读也不写任何内容——
        #   手机端收到空请求会直接关掉这个连接，不算错误尝试。
        try {
            $c = New-Object System.Net.Sockets.TcpClient
            $iar = $c.BeginConnect($ip, $port, $null, $null)
            if (-not $iar.AsyncWaitHandle.WaitOne(400, $false)) { $c.Close(); return $false }
            $c.EndConnect($iar)
            $c.Close()
            return $true
        } catch { return $false }
    }

    function Find-Port([string]$ip) {
        # 手机端在 38217~38236 这段里监听（被占往后试）→ 挨个探一遍即可
        for ($p = 38217; $p -lt 38237; $p++) {
            if (Test-Port $ip $p) { return $p }
        }
        return 0
    }

    function Broadcast-Addresses {
        # 各网卡的子网广播地址（家里 192.168.x.255、公司 172.16.x.255、热点 10.x.x.255）
        $out = @()
        try {
            foreach ($ni in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
                if ($ni.OperationalStatus -ne 'Up') { continue }
                if ($ni.NetworkInterfaceType -eq [Net.NetworkInformation.NetworkInterfaceType]::Loopback) { continue }
                foreach ($ua in $ni.GetIPProperties().UnicastAddresses) {
                    if ($ua.Address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { continue }
                    if (-not $ua.IPv4Mask) { continue }
                    $b = $ua.Address.GetAddressBytes()
                    $m = $ua.IPv4Mask.GetAddressBytes()
                    $bc = New-Object byte[] 4
                    for ($i = 0; $i -lt 4; $i++) { $bc[$i] = ($b[$i] -bor (-bnot $m[$i])) }
                    $out += ([Net.IPAddress]::new($bc)).ToString()
                }
            }
        } catch { }
        return $out
    }

    function Find-Phone {
        # 广播一句「谁在接收存档？」；手机回一条 "SDH1 <tcp端口> <ip1,ip2,...>"
        # → 电脑挨个试这些 IP，第一个连得上的就是它。**用户什么都不用输。**
        try {
            $sock = New-Object System.Net.Sockets.UdpClient
            $sock.EnableBroadcast = $true
            $sock.Client.ReceiveTimeout = 1200
            $req = [Text.Encoding]::ASCII.GetBytes('SDH-FIND')
            $targets = @('255.255.255.255') + (Broadcast-Addresses)
            foreach ($t in $targets) {
                try {
                    $ep = New-Object System.Net.IPEndPoint([Net.IPAddress]::Parse($t), 38217)
                    [void]$sock.Send($req, $req.Length, $ep)
                } catch { }
            }
            $deadline = (Get-Date).AddSeconds(2)
            while ((Get-Date) -lt $deadline) {
                $ep = New-Object System.Net.IPEndPoint([Net.IPAddress]::Any, 0)
                $data = $null
                try { $data = $sock.Receive([ref]$ep) } catch { continue }
                if (-not $data) { continue }
                $txt = [Text.Encoding]::ASCII.GetString($data)
                if ($txt -notlike 'SDH1 *') { continue }
                $parts = $txt.Split(' ')
                if ($parts.Length -lt 3) { continue }
                $tp = [int]$parts[1]
                for ($i = 2; $i -lt $parts.Length; $i++) {
                    if (Test-Port $parts[$i] $tp) {
                        $sock.Close()
                        return @{ ip = $parts[$i]; port = $tp }
                    }
                }
            }
            $sock.Close()
        } catch { }
        return $null
    }

    function Find-Phone-ByScan {
        # 广播没回应的**第二段路**：直接扫同网段的 38217 端口。
        #
        # ⚠ 为什么需要它：手机回的那包 UDP 是**纯入站**，Windows 防火墙默认拦；
        #   而 TCP 那条路是「出站连接 + 对该连接的响应」，默认放行 → 所以
        #   **扫端口不需要任何防火墙规则**（实测：广播没回应，手输 IP 的那条路却通了）。
        #
        # 只扫**同一个 /24**（掩码前 24 位全 1）：/16 的公司网有六万多个 IP，
        #   扫不动，交给手输兜底。
        $nets = @()
        try {
            foreach ($ni in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
                if ($ni.OperationalStatus -ne 'Up') { continue }
                foreach ($ua in $ni.GetIPProperties().UnicastAddresses) {
                    if ($ua.Address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { continue }
                    if (-not $ua.IPv4Mask) { continue }
                    $m = $ua.IPv4Mask.GetAddressBytes()
                    if ($m[0] -ne 255 -or $m[1] -ne 255 -or $m[2] -ne 255) { continue }
                    $b = $ua.Address.GetAddressBytes()
                    $nets += ($b[0..2] -join '.')
                }
            }
        } catch { }
        foreach ($n in ($nets | Select-Object -Unique)) {
            $socks = @()
            for ($i = 1; $i -le 254; $i++) {
                try {
                    $c = New-Object System.Net.Sockets.TcpClient
                    $c.BeginConnect("$n.$i", 38217, $null, $null) | Out-Null
                    $socks += ,@("$n.$i", $c)
                } catch { }
            }
            $hit = $null
            foreach ($s in $socks) {
                try {
                    if ($s[1].AsyncWaitHandle.WaitOne(300, $false)) {
                        $s[1].EndConnect($s[1].AsyncWaitHandle)
                        $hit = $s[0]
                    }
                } catch { }
                try { $s[1].Close() } catch { }
            }
            if ($hit) {
                # 确认它确实是「星露小助手在等存档包」，别认错成路由器/别的设备
                try {
                    $r = Invoke-WebRequest -Uri ("http://" + $hit + ":38217/") -TimeoutSec 3 -UseBasicParsing
                    if ($r.Content -like '*正在等存档包*') { return @{ ip = $hit; port = 38217 } }
                } catch { }
            }
        }
        return $null
    }

    # ── 1. 找存档目录：官方是 Saves，个别 MOD 装在别的名字下，挨个试 ────────
    $cands = @(
        "$env:APPDATA\StardewValley\Saves",
        "$env:APPDATA\StardewValley\Save_Game",
        "$env:APPDATA\StardewValley\SaveGame"
    )
    $root = $null
    foreach ($c in $cands) { if (Test-Path $c) { $root = $c; break } }
    if (-not $root) {
        Say '✗ 找不到存档目录。请确认这台电脑玩过星露谷物语。'
        Say ('  试过：' + ($cands -join '、'))
        exit 1
    }
    Say ('存档目录：' + $root)

    # ── 2. 排除游戏自己写的备份/合并档：它们常常比主存档还大 ────────────────
    $skipExact = @('SaveGameInfo', 'BACKUP_SAVE')
    function IsSkipped([string]$name) {
        $b = Split-Path $name -Leaf
        if ($skipExact -contains $b) { return $true }
        return ($b -match '(_old|_SVBAK|_SVEMERG)$')
    }

    # ── 3. 列出农场文件夹（内有非备份的大文件才算一个农场）────────────────
    $farmList = @()
    foreach ($d in (Get-ChildItem $root -Directory -ErrorAction SilentlyContinue)) {
        $real = @(Get-ChildItem $d.FullName -File -Recurse -ErrorAction SilentlyContinue |
                  Where-Object { -not (IsSkipped $_.Name) })
        if ($real.Count -gt 0) {
            $big = @($real | Where-Object { $_.Length -gt 100KB })
            if ($big.Count -gt 0) { $farmList += $d }
        }
    }
    if ($farmList.Count -eq 0) { Say '✗ 没找到任何农场存档。'; exit 1 }

    Say ''
    Say ('找到 ' + $farmList.Count + ' 个农场：')
    for ($i = 0; $i -lt $farmList.Count; $i++) {
        Say ('  ' + ($i + 1) + ') ' + $farmList[$i].Name)
    }
    Say ''

    # ── 4. 选农场（支持命令行 --farm 免交互）──────────────────────────
    if ($FarmName) {
        $farm = $farmList | Where-Object { $_.Name -eq $FarmName } | Select-Object -First 1
        if (-not $farm) { Say ('✗ 没找到农场：' + $FarmName); exit 1 }
    } else {
        $pick = Read-Host '选哪个农场（序号，回车=第一个）'
        if ([string]::IsNullOrWhiteSpace($pick)) { $pick = '1' }
        $i = [int]$pick - 1
        if ($i -lt 0 -or $i -ge $farmList.Count) { Say '✗ 序号不对。'; exit 1 }
        $farm = $farmList[$i]
    }
    Say ('已选：' + $farm.Name)

    # ── 5. 打包：把农场文件夹的**内容**放到压缩包根 ──────────────────────
    $tmp = Join-Path $env:TEMP ('sdh_' + [guid]::NewGuid().ToString('N') + '.zip')
    [System.IO.Compression.ZipFile]::CreateFromDirectory(
        $farm.FullName, $tmp, [System.IO.Compression.CompressionLevel]::Optimal, $false)
    $kb = [math]::Round((Get-Item $tmp).Length / 1KB, 1)
    Say ('已打包 ' + $kb + ' KB')

    # ── 6. 找手机 ────────────────────────────────────────────────────
    # 三条路，按省事程度排：
    #   ① 广播自动发现（最省事）：电脑喊一声，手机应一声，电脑自己知道往哪连。
    #   ② --host 免交互：命令行直接给了地址。
    #   ③ 手输 IP：广播不通（防火墙拦了 / 网络禁广播）时的兜底——
    #      **只需要 IP**，端口由电脑自己扫 38217~38236 找出来。
    $Port = 0
    if ($HostIP) {
        $HostIP = $HostIP.Trim() -replace '^https?://', '' -replace '/.*$', ''
        if ($HostIP -match '^(.*):(\d+)$') {
            $HostIP = $Matches[1]
            $Port = [int]$Matches[2]
        }
    } else {
        Say '正在找手机上的「星露小助手」…'
        $found = Find-Phone
        if (-not $found) {
            # 广播那包 UDP 是**纯入站**，Windows 防火墙默认拦；扫端口走的是
            # 「出站连接 + 响应」，默认放行 → 所以第二段路不需要用户动防火墙。
            Say '广播没回应（多半是防火墙拦了入站 UDP），正在扫描同一个网络…'
            $found = Find-Phone-ByScan
        }
        if ($found) {
            $HostIP = $found.ip
            $Port = $found.port
            Say ('找到了：' + $HostIP + ':' + $Port)
        } else {
            Say '没找到（接收窗口没开？或者防火墙/网络把广播挡了）。'
            Say '请先在手机上打开：设置 → 导入游戏存档 → 从电脑接收 → 开始接收'
            $HostIP = Read-Host '把手机上显示的 IP 输进来（端口不用填，会自己找）'
            if ([string]::IsNullOrWhiteSpace($HostIP)) { Say '已取消。'; exit 1 }
            $HostIP = $HostIP.Trim() -replace '^https?://', '' -replace '/.*$', ''
            if ($HostIP -match '^(.*):(\d+)$') { $HostIP = $Matches[1]; $Port = [int]$Matches[2] }
        }
    }
    if ([string]::IsNullOrWhiteSpace($HostIP)) { Say '已取消。'; exit 1 }
    if ($Port -eq 0) {
        Say '正在找端口…'
        $Port = Find-Port $HostIP
        if ($Port -eq 0) {
            Say ('✗ 连不上 ' + $HostIP + ' 的 38217~38236 端口。')
            Say '  ① 手机上的接收窗口开了吗？'
            Say '  ② 手机和电脑在同一个 WiFi 吗？'
            exit 1
        }
    }
    $url = 'http://' + $HostIP + ':' + $Port + '/upload'

    # ── 7. 生成 6 位随机验证码 ─────────────────────────────────────────
    # ⚠ 必须**均匀随机**且每次不同：
    #   不用 Get-Random（种子可预测，攻击者能推算出下一个码），
    #   不用自己写 LCG。这里用系统自带的 RandomNumberGenerator 取 8 字节再取模 10^6，
    #   偏置 < 1e-13（实质均匀）；若只取 4 字节，偏置约 7%，不均匀。
    function New-Code {
        $bytes = New-Object byte[] 8
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $n = [BitConverter]::ToUInt64($bytes, 0) % 1000000
        # ⚠ 必须用 InvariantCulture：默认 culture 下 100000 会格式化成 "100,000"
        #   （带千分位），验证码就变成 7 个字符了。
        return $n.ToString('D6', [System.Globalization.CultureInfo]::InvariantCulture)
    }

    # ── 8. 上传 ────────────────────────────────────────────────────────
    # 失败要能重试，而**每次重试必须换新码**（手机端一个码只能用一次）。
    $maxTry = 3
    $try = 0
    while ($try -lt $maxTry) {
        $try++
        $code = New-Code
        Say ''
        Say ('本次验证码：' + $code)
        Say '  在手机上输入这 6 位数字。这个码只应该出现在你自己的电脑上。'
        Say ''
        Say ('正在发送到 ' + $url + ' …')
        $headers = @{
            'X-Stardew-Code' = $code
            'X-Stardew-Proto' = '2'
        }
        try {
            # ⚠ 超时给到 120 秒：手机要等**人**输验证码（最长 60 秒），再传文件。
            #   60 秒不够用，人还没输完就断了。
            $resp = Invoke-WebRequest -Uri $url -Method Post -InFile $tmp `
                                      -Headers $headers -ContentType 'application/zip' `
                                      -TimeoutSec 120 -UseBasicParsing
            Say ('✓ 手机端已收到（' + $kb + ' KB）' + [string]$resp.Content)
            Say '  现在看手机：它会弹出确认抽屉，核对数字后点「确定导入」。'
            exit 0
        } catch {
            # 读手机端的回话（PS 5.1 非 2xx 会抛异常，body 在 Response 里）
            $status = 0
            $body = ''
            $respObj = $_.Exception.Response
            if ($respObj) {
                try { $status = [int]$respObj.StatusCode } catch { $status = 0 }
                try {
                    $reader = New-Object System.IO.StreamReader($respObj.GetResponseStream(), [System.Text.Encoding]::UTF8)
                    $body = $reader.ReadToEnd()
                    $reader.Close()
                } catch { $body = '' }
            }
            # 验证码不对 → 换个新码重来（手机会再弹一次输入框）
            if ($status -eq 403 -and $body -like '*验证码*' -and $try -lt $maxTry) {
                Say ('✗ ' + $body.Trim() + '——再来一次，会生成新的验证码。')
                continue
            }
            Say ('✗ 发送失败' + $(if ($status) { "（HTTP $status）" } else { '' }) + '：' + $_.Exception.Message)
            if ($body) { Say ('  手机端说：' + $body.Trim()) }
            Say ''
            Say '  常见原因：'
            if ($body -like '*版本*') {
                Say '   ① 电脑脚本版本过旧 —— 在手机的「从电脑接收」里重新下载 send_save.bat 再双击；'
            } elseif ($body -like '*验证码*') {
                Say '   ① 验证码输错了 —— 看电脑上显示的码，重新在手机上敲一次；'
            }
            Say '   ② 手机不在同一个 WiFi；'
            Say '   ③ 手机上的接收窗口已经关掉（5 分钟没人连会自动关）；'
            Say '   ④ Windows 防火墙拦了 —— 第一次运行通常会弹窗询问，点「允许访问」。'
            Say '   ⑤ 地址填错了 —— 就在手机上那个窗口里，IP 和端口要一起抄。'
            exit 1
        }
    }
    exit 1
} catch {
    Write-Host ('✗ 出错：' + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally {
    if ($tmp -and (Test-Path $tmp)) { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
}
