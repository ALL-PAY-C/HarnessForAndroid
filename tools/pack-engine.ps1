# 把 engine/ 打包成 APK 资产（aapt2 会跳过 . 开头的目录条目，所以必须打成 zip）
# 注意：必须手工建 entry —— .NET Framework 的 ZipFile.CreateFromDirectory 会把路径分隔符写成反斜杠，
# Android 侧解出来就是扁平文件名（host\cordis-lite.js），ESM import 全断。
# 用法: powershell -File tools/pack-engine.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$engine = Join-Path $root 'engine'
$out = Join-Path $root 'app\src\main\assets\runtime\engine.zip'
$skipTop = @('.check.mjs', '.probe.mjs', '.work', 'test')

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (Test-Path $out) { Remove-Item $out -Force }
$zip = [System.IO.Compression.ZipFile]::Open($out, [System.IO.Compression.ZipArchiveMode]::Create)
$count = 0
try {
  Get-ChildItem $engine -Recurse -File -Force | ForEach-Object {
    $rel = $_.FullName.Substring($engine.Length + 1).Replace('\', '/')
    $top = $rel.Split('/')[0]
    if ($skipTop -contains $top) { return }
    $entry = $zip.CreateEntry($rel, [System.IO.Compression.CompressionLevel]::Optimal)
    $entry.LastWriteTime = [DateTimeOffset]::new(1981, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
    $stream = $entry.Open()
    try { [System.IO.File]::OpenRead($_.FullName).CopyTo($stream) } finally { $stream.Dispose() }
    $count++
  }
} finally { $zip.Dispose() }

$check = [System.IO.Compression.ZipFile]::OpenRead($out)
$entries = $check.Entries.Count
$sample = $check.Entries | Select-Object -First 3 | ForEach-Object { $_.FullName }
$check.Dispose()
Write-Output ("engine.zip entries=" + $entries + " bytes=" + (Get-Item $out).Length)
Write-Output ("sample: " + ($sample -join ', '))
