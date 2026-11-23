# The packaged smoke walk: install per-user, launch the packaged app
# in smoke mode, assert the artifacts it lands in open formats, uninstall, and assert no
# surviving process. Requires the installer built first: mvn -B -Pinstaller package.
# NOTE: the MSI is UNSIGNED - SmartScreen will warn on first install; the smoke installs
# silently with /qn which SmartScreen does not block (it guards the interactive UI prompt).
# Code signing is deferred, not skipped.
param(
    [string]$Msi = "target/installer/AgenticResearchBuddy-0.1.0.msi",
    [string]$AppImage = "target/installer/AgenticResearchBuddy/AgenticResearchBuddy.exe",
    [switch]$UseAppImage,
    [string]$SmokeDir = "$env:TEMP\rmls-smoke-$(Get-Date -Format yyyyMMddHHmmss)"
)
$ErrorActionPreference = "Stop"

if ($UseAppImage) {
    if (-not (Test-Path $AppImage)) {
        Write-Output "SMOKE FAIL: app image launcher not found at $AppImage - build with -Dinstaller.cfg=scripts/installer-appimage.cfg"
        exit 2
    }
    $exe = $AppImage
    Write-Output "[smoke] app-image launcher: $exe (no install step)"
} else {
    if (-not (Test-Path $Msi)) {
        Write-Output "SMOKE FAIL: installer not found at $Msi - run mvn -B -Pinstaller package first"
        exit 2
    }

    Write-Output "[smoke] installing per-user: $Msi"
    $proc = Start-Process msiexec.exe -ArgumentList "/i","`"$Msi`"","/qn","/norestart" -Wait -PassThru
    if ($proc.ExitCode -ne 0) {
        Write-Output "SMOKE FAIL: msiexec install exited $($proc.ExitCode)"
        exit 3
    }

    $exe = "$env:LOCALAPPDATA\AgenticResearchBuddy\AgenticResearchBuddy.exe"
    if (-not (Test-Path $exe)) {
        # jpackage names the install folder after --name; check the menu layout fallback
        $exe = "$env:LOCALAPPDATA\AgenticResearchBuddy\agenticresearchbuddy.exe"
    }
    if (-not (Test-Path $exe)) {
        Write-Output "SMOKE FAIL: installed launcher not found under $env:LOCALAPPDATA\AgenticResearchBuddy"
        exit 4
    }
    Write-Output "[smoke] launcher: $exe"
}

Write-Output "[smoke] running packaged smoke pass into $SmokeDir"
$run = Start-Process $exe -ArgumentList "smoke","`"$SmokeDir`"" -Wait -PassThru -NoNewWindow
if ($run.ExitCode -ne 0) {
    Write-Output "SMOKE FAIL: packaged app exited $($run.ExitCode)"
    exit 5
}

$artifacts = @(
    "$SmokeDir\library\projects\smoke",
    "$SmokeDir\runs"
)
foreach ($artifact in $artifacts) {
    if (-not (Test-Path $artifact)) {
        Write-Output "SMOKE FAIL: expected artifact missing: $artifact"
        exit 6
    }
}
$zip = Get-ChildItem "$SmokeDir\library\projects\smoke" -Filter *.flow.zip -ErrorAction SilentlyContinue
$log = Get-ChildItem "$SmokeDir\runs" -Filter *.jsonl -ErrorAction SilentlyContinue
if (-not $zip -or -not $log) {
    Write-Output "SMOKE FAIL: no version zip or no run log under the workspace root"
    exit 7
}
Write-Output "[smoke] artifacts in open formats: $($zip.Name) + $($log.Name)"

if (-not $UseAppImage) {
    Write-Output "[smoke] uninstalling"
    $un = Start-Process msiexec.exe -ArgumentList "/x","`"$Msi`"","/qn","/norestart" -Wait -PassThru
    if ($un.ExitCode -ne 0) {
        Write-Output "SMOKE FAIL: msiexec uninstall exited $($un.ExitCode)"
        exit 8
    }
}

$survivor = Get-Process -Name "AgenticResearchBuddy" -ErrorAction SilentlyContinue
if ($survivor) {
    Write-Output "SMOKE FAIL: surviving process after exit: $($survivor.Id)"
    exit 9
}

Write-Output "SMOKE PASS: install -> launch -> project -> demo run -> artifacts -> uninstall -> no survivor"
