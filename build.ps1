# APK build without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Requires JDK 17 and Android SDK (platform 34 + build-tools 34.0.0).
#
# Example:
#   $env:JAVA_HOME = "C:\path\to\jdk-17"
#   $env:ANDROID_HOME = "C:\path\to\android-sdk"
#   powershell -ExecutionPolicy Bypass -File .\build.ps1

param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$rootOriginal = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = $rootOriginal

# Native tools (aapt2, zipalign) do not accept non-ASCII paths on Windows.
# Use the short (8.3) form of the project path when available.
try {
    $fso = New-Object -ComObject Scripting.FileSystemObject
    $root = $fso.GetFolder($rootOriginal).ShortPath
} catch {
    $root = $rootOriginal
}

$src = Join-Path $root 'app\src\main'
$build = Join-Path $root 'build'
$androidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
$tools = Join-Path $Sdk 'build-tools\34.0.0'

if (-not $Sdk -or -not (Test-Path $androidJar)) {
    throw 'Android SDK not found. Set ANDROID_HOME or pass -Sdk.'
}
if (-not $Jdk -or -not (Test-Path (Join-Path $Jdk 'bin\javac.exe'))) {
    throw 'JDK 17 not found. Set JAVA_HOME or pass -Jdk.'
}

$env:JAVA_HOME = $Jdk
$env:Path = (Join-Path $Jdk 'bin') + ';' + $env:Path

foreach ($dir in @($build, (Join-Path $build 'gen'), (Join-Path $build 'classes'), (Join-Path $build 'dex'))) {
    if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
    New-Item -ItemType Directory -Path $dir | Out-Null
}

Write-Host '1/6 aapt2 compile...'
& (Join-Path $tools 'aapt2.exe') compile --dir (Join-Path $src 'res') -o (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Write-Host '2/6 aapt2 link...'
& (Join-Path $tools 'aapt2.exe') link `
    -o (Join-Path $build 'base.apk') `
    -I $androidJar `
    --manifest (Join-Path $src 'AndroidManifest.xml') `
    -A (Join-Path $src 'assets') `
    --java (Join-Path $build 'gen') `
    --min-sdk-version 23 `
    --target-sdk-version 34 `
    --version-code 2 `
    --version-name '2.0' `
    (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '3/6 javac...'
$sources = @()
$sources += Get-ChildItem (Join-Path $src 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem (Join-Path $build 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& (Join-Path $Jdk 'bin\javac.exe') `
    -encoding UTF-8 `
    -source 8 -target 8 `
    -Xlint:-options `
    -bootclasspath $androidJar `
    -d (Join-Path $build 'classes') `
    $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '4/6 d8...'
& (Join-Path $Jdk 'bin\jar.exe') cf (Join-Path $build 'classes.jar') -C (Join-Path $build 'classes') .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
& (Join-Path $tools 'd8.bat') --lib $androidJar --min-api 23 --output (Join-Path $build 'dex') (Join-Path $build 'classes.jar')
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Copy-Item (Join-Path $build 'base.apk') (Join-Path $build 'unsigned.apk') -Force
& (Join-Path $Jdk 'bin\jar.exe') uf (Join-Path $build 'unsigned.apk') -C (Join-Path $build 'dex') classes.dex
if ($LASTEXITCODE -ne 0) { throw 'jar update failed' }

Write-Host '5/6 zipalign...'
& (Join-Path $tools 'zipalign.exe') -f 4 (Join-Path $build 'unsigned.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '6/6 apksigner...'
$keystore = Join-Path $root 'release.keystore'
if (-not (Test-Path $keystore)) {
    & (Join-Path $Jdk 'bin\keytool.exe') -genkeypair `
        -keystore $keystore `
        -storepass alarmclock `
        -keypass alarmclock `
        -alias alarmclock `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Clean Alarm, O=Clean Alarm, C=RU'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

$apk = Join-Path $root 'AlarmClock.apk'
& (Join-Path $tools 'apksigner.bat') sign `
    --ks $keystore `
    --ks-pass pass:alarmclock `
    --key-pass pass:alarmclock `
    --out $apk `
    (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& (Join-Path $tools 'apksigner.bat') verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

Write-Host ''
Write-Host ('Done: ' + (Join-Path $rootOriginal 'AlarmClock.apk'))
