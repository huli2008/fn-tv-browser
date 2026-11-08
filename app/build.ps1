# 无 Gradle 构建脚本：aapt2 + javac + d8 + zipalign + apksigner
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$tc   = Join-Path (Split-Path -Parent (Split-Path -Parent $root)) "tools\android-toolchain"
$bt   = Join-Path $tc "buildtools\android-14"
$jdk  = (Get-ChildItem (Join-Path $tc "jdk17-ms") -Directory | Select-Object -First 1).FullName
$jar  = Join-Path $tc "platform33\android-13\android.jar"
$build = Join-Path $root "build"

$env:JAVA_HOME = $jdk
$javac = Join-Path $jdk "bin\javac.exe"
$jar_t = Join-Path $jdk "bin\jar.exe"
$keytool = Join-Path $jdk "bin\keytool.exe"

Write-Host "JDK : $jdk"
Write-Host "BT  : $bt"

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory "$build\gen","$build\obj","$build\dex" | Out-Null

Write-Host "==> aapt2 compile"
& "$bt\aapt2.exe" compile --dir "$root\res" -o "$build\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "==> aapt2 link"
& "$bt\aapt2.exe" link -o "$build\app-unsigned.apk" -I $jar --manifest "$root\AndroidManifest.xml" `
    -R "$build\res.zip" --java "$build\gen" --min-sdk-version 24 --target-sdk-version 33 `
    --version-code 1 --version-name 1.0 --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "==> javac"
$srcs = @(
    "$root\src\com\fntv\browser\MainActivity.java",
    (Get-ChildItem "$build\gen" -Recurse -Filter R.java | Select-Object -First 1).FullName
)
& $javac --release 8 -encoding UTF-8 -nowarn -classpath $jar -d "$build\obj" $srcs
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "==> d8"
$classes = (Get-ChildItem "$build\obj" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
& "$bt\d8.bat" --min-api 24 --lib $jar --output "$build\dex" @classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "==> package dex"
& $jar_t uf "$build\app-unsigned.apk" -C "$build\dex" classes.dex
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "==> zipalign"
& "$bt\zipalign.exe" -p 4 "$build\app-unsigned.apk" "$build\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

$ks = Join-Path $root "debug.keystore"
if (-not (Test-Path $ks)) {
    Write-Host "==> generate keystore"
    & $keytool -genkeypair -keystore $ks -storepass android -keypass android -alias fnb `
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=FN TV Browser, O=FN, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "==> sign"
$out = Join-Path $root "fn-tv-browser-v1.0.apk"
if (Test-Path $out) { Remove-Item $out -Force }
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $out "$build\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

Write-Host "==> verify"
& "$bt\apksigner.bat" verify --print-certs $out

$size = (Get-Item $out).Length / 1KB
Write-Host ""
Write-Host "OK  ->  $out  ($([math]::Round($size,1)) KB)"
