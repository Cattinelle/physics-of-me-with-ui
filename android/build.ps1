# Builds Physics of Me for Android with the plain SDK tools (no Gradle):
#   powershell -NoProfile -ExecutionPolicy Bypass -File android\build.ps1
# Output: physics-of-me.apk at the repository root, signed with a stable local key
# (kept outside the repository, in %USERPROFILE%\.android\physicsofme.jks) so updates install over each other.
$ErrorActionPreference = 'Continue'  # the tools print notes on stderr; failures are caught by exit codes
$Root = Split-Path $PSScriptRoot -Parent
$A = $PSScriptRoot
$Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$BT = Join-Path $Sdk 'build-tools\34.0.0'
$Jar = Join-Path $Sdk 'platforms\android-34\android.jar'
$Out = Join-Path $A 'build'
$Key = Join-Path $env:USERPROFILE '.android\physicsofme.jks'

if (Test-Path $Out) { Remove-Item -Recurse -Force $Out }
New-Item -ItemType Directory -Force "$Out\gen", "$Out\classes", "$Out\dex", "$Out\assets\www" | Out-Null
Copy-Item (Join-Path $Root 'index.html') "$Out\assets\www\index.html"

& "$BT\aapt2.exe" compile --dir "$A\res" -o "$Out\res.zip"
if ($LASTEXITCODE) { throw 'aapt2 compile failed' }
& "$BT\aapt2.exe" link -I $Jar --manifest "$A\AndroidManifest.xml" -o "$Out\base.apk" "$Out\res.zip" `
    -A "$Out\assets" --java "$Out\gen" --min-sdk-version 26 --target-sdk-version 34
if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$src = @(Get-ChildItem -Recurse "$A\src", "$Out\gen" -Filter *.java | ForEach-Object FullName)
& javac -nowarn -Xlint:-options -source 1.8 -target 1.8 -encoding UTF-8 -classpath $Jar -d "$Out\classes" @src
if ($LASTEXITCODE) { throw 'javac failed' }

& jar cf "$Out\classes.jar" -C "$Out\classes" .
if ($LASTEXITCODE) { throw 'jar failed' }
& "$BT\d8.bat" --release --min-api 26 --lib $Jar --output "$Out\dex" "$Out\classes.jar"
if ($LASTEXITCODE) { throw 'd8 failed' }

# put classes.dex at the root of the apk
python -I -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],'a'); z.write(sys.argv[2],'classes.dex'); z.close()" "$Out\base.apk" "$Out\dex\classes.dex"
& "$BT\zipalign.exe" -p -f 4 "$Out\base.apk" "$Out\aligned.apk"
if ($LASTEXITCODE) { throw 'zipalign failed' }

if (-not (Test-Path $Key)) {
    New-Item -ItemType Directory -Force (Split-Path $Key) | Out-Null
    & keytool -genkeypair -keystore $Key -alias pom -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass physicsofme -keypass physicsofme -dname "CN=The Visionaries of Physics, O=JUNIA ISEN, C=FR"
    if ($LASTEXITCODE) { throw 'keytool failed' }
}
$apk = Join-Path $Root 'physics-of-me.apk'
& "$BT\apksigner.bat" sign --ks $Key --ks-key-alias pom --ks-pass pass:physicsofme --key-pass pass:physicsofme --out $apk "$Out\aligned.apk"
if ($LASTEXITCODE) { throw 'apksigner failed' }
& "$BT\apksigner.bat" verify $apk
if ($LASTEXITCODE) { throw 'verify failed' }
# version.json lets installed apps see that a newer APK is online (read from the manifest, so it never drifts)
$man = [xml](Get-Content "$A\AndroidManifest.xml" -Raw)
$ns = 'http://schemas.android.com/apk/res/android'; $vc = $man.manifest.GetAttribute('versionCode', $ns); $vn = $man.manifest.GetAttribute('versionName', $ns)
('{{"versionCode": {0}, "versionName": "{1}", "apk": "https://clleb.github.io/physics-of-me/physics-of-me.apk"}}' -f $vc, $vn) | Set-Content -Encoding ascii (Join-Path $Root 'version.json')
Write-Host ("Built {0} ({1:N0} KB), version {2} ({3})" -f $apk, ((Get-Item $apk).Length / 1KB), $vn, $vc)
