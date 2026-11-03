<#
  HarnessForAndroid 回归冒烟：构建 → 装机 → 启动 → 无障碍探针 → 四页截图 → 崩溃检查。
  用法：
    pwsh -File tools/verify.ps1                 # 全流程（增量构建）
    pwsh -File tools/verify.ps1 -SkipBuild      # 用现有 APK
  退出码：0 全通过；1 有步骤失败（原因打印在最后）。
#>
param(
  [string]$Out = "baseline/verify",
  [switch]$SkipBuild,
  [string]$Pkg = "dev.dsh.mobile.debug",
  [string]$Activity = "dev.dsh.mobile.app.MainActivity"
)

$ErrorActionPreference = "Stop"
$env:JAVA_HOME = "C:\Users\ljk06\scoop\apps\temurin17-jdk\current"
$env:ANDROID_HOME = "D:\Android"
$env:ANDROID_SDK_ROOT = "D:\Android"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
New-Item -ItemType Directory -Force -Path $Out | Out-Null

$script:fail = @()
function Step($name, $block) {
  Write-Host "== $name"
  try { & $block } catch { $script:fail += ($name + " : " + $_.Exception.Message); Write-Host ("   FAIL " + $_.Exception.Message) }
}
function Sh($a) { $o = & adb @a 2>&1 | Out-String; if ($LASTEXITCODE -ne 0) { throw ("adb " + ($a -join " ") + " -> " + $o) }; return $o }

Step "构建 debug" {
  if (-not $SkipBuild) {
    & .\gradlew.bat --console=plain --no-daemon :app:assembleDebug | Tee-Object -FilePath "$Out\build.log" | Select-Object -Last 3 | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug 退出码 $LASTEXITCODE，见 $Out\build.log" }
  }
  if (-not (Test-Path $apk)) { throw "找不到 APK: $apk" }
  Write-Host ("   APK " + (Get-Item $apk).Length + " B")
}

Step "装机与启动" {
  Sh @("install", "-r", $apk) | Select-Object -Last 1 | Out-Host
  Sh @("shell", "am", "force-stop", $Pkg) | Out-Null
  $t = Sh @("shell", "am", "start", "-W", "-n", ($Pkg + "/" + $Activity))
  if ($t -notmatch "Status: ok") { throw ("启动未返回 Status: ok: " + $t) }
  Start-Sleep -Seconds 8
}

Step "无障碍服务" {
  Sh @("shell", "settings", "put", "secure", "enabled_accessibility_services", ($Pkg + "/dev.dsh.mobile.toolbridge.phone.PhoneAccessibilityService")) | Out-Null
  Sh @("shell", "settings", "put", "secure", "accessibility_enabled", "1") | Out-Null
  Start-Sleep -Seconds 3
  $d = Sh @("shell", "dumpsys", "accessibility")
  if ($d -notmatch "Enabled services") { throw "无障碍未启用" }
}

Step "手机工具探针（读屏树）" {
  # JSON 必须整体用单引号包住再交给 adb，否则设备端 shell 会把内层双引号剥掉，探针收到残缺 JSON
  $json = "'" + '{"action":"tree","maxDepth":2,"maxNodes":12}' + "'"
  Sh @("shell", "am", "broadcast", "-n", ($Pkg + "/dev.dsh.mobile.toolbridge.phone.PhoneTestProbe"), "--es", "json", $json, "--es", "out", "verify.json") | Out-Null
  Start-Sleep -Seconds 2
  $raw = Sh @("shell", "run-as", $Pkg, "cat", "files/verify.json")
  $r = $raw | ConvertFrom-Json
  if (-not $r.ok) { throw ("探针返回失败: " + $r.code + " " + $r.message) }
  Write-Host ("   snapshotId=" + $r.snapshotId + " generation=" + $r.generation + " foreground=" + $r.foreground + " nodes=" + $r.nodeCount)
}

Step "截图四页" {
  $shots = @(
    @{ n = "01-sessions"; x = 135 }, @{ n = "02-agents"; x = 406 },
    @{ n = "03-plugins"; x = 675 },  @{ n = "04-settings"; x = 945 }
  )
  foreach ($s in $shots) {
    Sh @("shell", "input", "tap", [string]$s.x, "2301") | Out-Null
    Start-Sleep -Seconds 2
    $p = Join-Path $Out ($s.n + ".png")
    & adb exec-out screencap -p > $p
    $len = (Get-Item $p).Length
    if ($len -lt 20000) { throw ($s.n + ".png 只有 " + $len + " B，可能截到黑屏") }
    Write-Host ("   " + $s.n + ".png " + $len + " B")
  }
}

Step "崩溃检查" {
  $log = Sh @("logcat", "-d", "-b", "crash", "-t", "400")
  if ($log -match "FATAL EXCEPTION") { throw ("crash 缓冲区有 FATAL EXCEPTION: " + $log) }
  Write-Host "   无 FATAL EXCEPTION"
}

if ($script:fail.Count -gt 0) { Write-Host ("失败 " + $script:fail.Count + " 项:") ; $script:fail | ForEach-Object { Write-Host (" - " + $_) }; exit 1 }
Write-Host "全部通过"
exit 0
