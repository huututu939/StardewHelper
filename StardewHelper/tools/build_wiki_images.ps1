# 裁剪 v9 图片到 48x48 并重编码（PowerShell + System.Drawing，本机没装 Pillow）
# 用法：powershell -File tools\build_wiki_images.ps1 <v9 images 目录> [输出目录]
# 路径全部走命令行参数：脚本里的中文注释在 PS 5.1 下按 GBK 解码会乱码，
# 放进注释块又会让 param() 解析失败，所以这里改用 $Args。
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$Src = $Args[0]
if (-not $Src) { throw "用法: build_wiki_images.ps1 <v9 images 目录> [输出目录]" }
$Out = if ($Args.Count -gt 1 -and $Args[1]) { $Args[1] } else { Join-Path $PSScriptRoot "..\app\src\main\assets\img\wiki" }
$SIZE = 48

New-Item -ItemType Directory -Path $Out -Force | Out-Null

function Resize-Png($src, $dst) {
    $img = [System.Drawing.Image]::FromFile($src)
    try {
        # 小于目标尺寸的原样复制（不放大，避免糊）
        if ($img.Width -le $SIZE -and $img.Height -le $SIZE) {
            Copy-Item $src $dst -Force
            return
        }
        # 等比缩放到 48x48 的正方形，居中透明（wiki 原图部分是 48x96 的工具长条）
        $bmp = New-Object System.Drawing.Bitmap($SIZE, $SIZE, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $g.Clear([System.Drawing.Color]::Transparent)
        $scale = [Math]::Min($SIZE / $img.Width, $SIZE / $img.Height)
        $w = [int][Math]::Round($img.Width * $scale)
        $h = [int][Math]::Round($img.Height * $scale)
        $dx = [int](($SIZE - $w) / 2)
        $dy = [int](($SIZE - $h) / 2)
        $g.DrawImage($img, (New-Object System.Drawing.Rectangle($dx, $dy, $w, $h)))
        $g.Dispose()
        $bmp.Save($dst, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose()
    } finally {
        $img.Dispose()
    }
}

$total = 0; $bytes = 0
foreach ($d in @("items", "fish", "npcs", "monsters")) {
    $od = Join-Path $Out $d
    New-Item -ItemType Directory -Path $od -Force | Out-Null
    Get-ChildItem "$Src\$d" -Filter *.png -File | ForEach-Object {
        Resize-Png $_.FullName (Join-Path $od $_.Name)
        $total++
        $bytes += (Get-Item (Join-Path $od $_.Name)).Length
    }
}
"处理 $total 张，共 {0:N0} KB" -f ($bytes / 1KB)