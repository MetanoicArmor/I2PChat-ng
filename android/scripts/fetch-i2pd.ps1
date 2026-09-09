# Refresh bundled Android i2pd JNI libs + certificates from PurpleI2P APKs.
# Usage: powershell -File android/scripts/fetch-i2pd.ps1

param(
    [string]$Version = "2.61.0"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$tmp = Join-Path $env:TEMP "i2pd-android-bin"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

gh release download $Version --repo PurpleI2P/i2pd-android --pattern "i2pd-$Version-x86_64-release.apk" --dir $tmp --clobber
gh release download $Version --repo PurpleI2P/i2pd-android --pattern "i2pd-$Version-arm64-v8a-release.apk" --dir $tmp --clobber
gh release download $Version --repo PurpleI2P/i2pd-android --pattern "i2pd_${Version}_android_binary.zip" --dir $tmp --clobber

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Extract-Lib([string]$apk, [string]$abi, [string]$dest) {
    $z = [System.IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $e = $z.Entries | Where-Object { $_.FullName -eq "lib/$abi/libi2pd.so" }
        if (-not $e) { throw "missing lib/$abi/libi2pd.so" }
        New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
        if (Test-Path $dest) { Remove-Item $dest -Force }
        [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
    } finally {
        $z.Dispose()
    }
}

Extract-Lib (Join-Path $tmp "i2pd-$Version-x86_64-release.apk") "x86_64" (Join-Path $repoRoot "android/app/src/main/jniLibs/x86_64/libi2pd.so")
Extract-Lib (Join-Path $tmp "i2pd-$Version-arm64-v8a-release.apk") "arm64-v8a" (Join-Path $repoRoot "android/app/src/main/jniLibs/arm64-v8a/libi2pd.so")

python -c @"
import zipfile, shutil
from pathlib import Path
root = Path(r'$repoRoot')
zpath = Path(r'$tmp') / 'i2pd_${Version}_android_binary.zip'
certs = root/'android/app/src/main/assets/i2pd'
if certs.exists():
    shutil.rmtree(certs)
certs.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(zpath) as z:
    for info in z.infolist():
        if info.is_dir() or not info.filename.startswith('certificates/'):
            continue
        dest = certs/info.filename
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(z.read(info.filename))
print('updated i2pd JNI', '$Version')
"@
