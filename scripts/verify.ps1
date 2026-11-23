# One documented development-environment command: compile + test suites + format check +
# coverage report. Uses the pinned local JDK when it is installed, otherwise whatever JDK
# is on PATH (JDK 25 required). Maven is taken from PATH, falling back to the pinned local
# install.
$ErrorActionPreference = "Stop"

$scoopApps = Join-Path $env:USERPROFILE "scoop\apps"
$pinnedJdk = Join-Path $scoopApps "temurin25-jdk\current"
if (Test-Path $pinnedJdk) {
    $env:JAVA_HOME = $pinnedJdk
}

$mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mvn) { $mvn = Get-Command mvn -ErrorAction SilentlyContinue }
if (-not $mvn) {
    $pinnedMvn = Join-Path $scoopApps "maven\current\bin\mvn.cmd"
    if (Test-Path $pinnedMvn) { $mvn = $pinnedMvn }
}
if (-not $mvn) {
    Write-Output "mvn not found - put Maven 3.10 on PATH and re-run"
    exit 1
}

& $mvn -B clean verify @args
exit $LASTEXITCODE
