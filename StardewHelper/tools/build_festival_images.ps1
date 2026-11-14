# v2.17 D5: downscale oversized festival PNGs to max width 512 (aspect preserved,
# 32bpp ARGB -> PNG, in place via temp file). Already <=512px wide -> skip (idempotent).
# Uses MemoryStream so the source file lock is released before saving.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File build_festival_images.ps1 <png files...>
Add-Type -AssemblyName System.Drawing

foreach ($path in $Args) {
    if (-not (Test-Path $path)) { Write-Host "SKIP missing $path"; continue }
    $before = (Get-Item $path).Length
    $bytes = [System.IO.File]::ReadAllBytes($path)
    $ms = New-Object System.IO.MemoryStream(, $bytes)
    $img = [System.Drawing.Image]::FromStream($ms)
    $bmp = $null
    $g = $null
    try {
        if ($img.Width -le 512) {
            Write-Host ("SKIP {0} (w={1})" -f $path, $img.Width)
            continue
        }
        $w = 512
        $h = [int]($img.Height * 512.0 / $img.Width)
        $bmp = New-Object System.Drawing.Bitmap($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $g = [System.Drawing.Graphics]::FromImage($bmp)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $g.DrawImage($img, 0, 0, $w, $h)
        $g.Dispose(); $g = $null
        $img.Dispose(); $img = $null
        $ms.Dispose(); $ms = $null
        $tmp = $path + ".tmp"
        if (Test-Path $tmp) { Remove-Item $tmp -Force }
        $bmp.Save($tmp, [System.Drawing.Imaging.ImageFormat]::Png)
        $bmp.Dispose(); $bmp = $null
        Move-Item -Force $tmp $path
        $after = (Get-Item $path).Length
        Write-Host ("OK   {0} -> 512x{1}  {2}KB -> {3}KB" -f $path, $h, [int]($before / 1kb), [int]($after / 1kb))
    } finally {
        if ($g -ne $null) { $g.Dispose() }
        if ($bmp -ne $null) { $bmp.Dispose() }
        if ($img -ne $null) { $img.Dispose() }
        if ($ms -ne $null) { $ms.Dispose() }
    }
}
