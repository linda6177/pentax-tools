# Build the modern Image Sync UI into one auxiliary DEX (classes3.dex).
#
# Two-stage compile:
#   1. tools/gen_stubs.py writes compile-time stubs of ORIGINAL classes
#      (Ricoh camera SDK API + SvApplication). Stubs are compiled to
#      build/stubclasses and are NEVER packaged.
#   2. src/**/*.java (the new UI) is compiled against android.jar + the stubs,
#      then converted with d8. At runtime the references resolve to the real
#      classes in classes.dex / classes2.dex of the owner-supplied APK.
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $MyInvocation.MyCommand.Path
$toolchain = Get-ChildItem 'D:\Desktop' -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'tools\.android-toolchain') } |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $toolchain) { throw 'Android toolchain not found on Desktop' }
$toolchain = Join-Path $toolchain 'tools\.android-toolchain'
$jdkRoot = (Get-ChildItem (Join-Path $toolchain 'jdk') -Directory | Select-Object -First 1).FullName
$android = Join-Path $toolchain 'sdk\platforms\android-35\android.jar'
$d8 = Join-Path $toolchain 'sdk\build-tools\35.0.0\lib\d8.jar'
$dexdump = Join-Path $toolchain 'sdk\build-tools\35.0.0\dexdump.exe'
$out = Join-Path $workspace 'build'
$dist = Join-Path $workspace 'dist'
New-Item -Force -ItemType Directory $out, $dist | Out-Null
$classes = Join-Path $out 'classes'
$stubClasses = Join-Path $out 'stubclasses'
foreach ($dir in @($classes, $stubClasses)) {
    if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
    New-Item -Force -ItemType Directory $dir | Out-Null
}

$javac = Join-Path $jdkRoot 'bin\javac.exe'
$jar = Join-Path $jdkRoot 'bin\jar.exe'
$java = Join-Path $jdkRoot 'bin\java.exe'

Write-Host '[1/5] generating + compiling compile-time stubs'
& python (Join-Path $workspace 'tools\gen_stubs.py')
if ($LASTEXITCODE -ne 0) { throw 'stub generation failed' }
$stubFiles = Get-ChildItem -Recurse -Filter '*.java' (Join-Path $workspace 'stubs') | ForEach-Object { $_.FullName }
& $javac -encoding UTF-8 -source 8 -target 8 -nowarn -proc:none -classpath $android -d $stubClasses @stubFiles
if ($LASTEXITCODE -ne 0) { throw 'stub compile failed' }

Write-Host '[2/5] compiling modern UI'
$sourceFiles = Get-ChildItem -Recurse -Filter '*.java' (Join-Path $workspace 'src') | ForEach-Object { $_.FullName }
if (-not $sourceFiles) { throw 'no UI sources found' }
& $javac -encoding UTF-8 -source 8 -target 8 -nowarn -proc:none `
    -classpath "$android;$stubClasses" -d $classes @sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'UI compile failed' }

Write-Host '[3/5] packaging UI classes'
$shellJar = Join-Path $out 'shell.jar'
if (Test-Path $shellJar) { Remove-Item -Force $shellJar }
Push-Location $classes
try {
    & $jar cf $shellJar 'com\ricohimaging\imagesync'
    if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
} finally { Pop-Location }

Write-Host '[4/5] dexing'
$dex = Join-Path $dist 'modern-shell.dex'
if (Test-Path $dex) { Remove-Item -Force $dex }
& $java -cp $d8 com.android.tools.r8.D8 --lib $android --min-api 23 --output $dist $shellJar
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
Move-Item -Force (Join-Path $dist 'classes.dex') $dex

Write-Host '[5/5] verifying the DEX contains only our classes'
& $dexdump -f $dex | Out-Null
$dump = & $dexdump $dex 2>&1 | Out-String
$defined = [regex]::Matches($dump, "Class descriptor\s+:\s+'([^']+)'") | ForEach-Object { $_.Groups[1].Value }
$forbidden = @('Lcom/ricoh/camera/sdk/', 'Landroidx/', 'Lcom/google/', 'Lkotlin/')
foreach ($name in $defined) {
    foreach ($pattern in $forbidden) {
        if ($name.StartsWith($pattern)) { throw "stub/original class leaked into DEX: $name" }
    }
    if ($name -eq 'Lcom/ricohimaging/imagesync/SvApplication;') {
        throw 'stub SvApplication leaked into DEX'
    }
}
$classesFound = $defined.Count
Write-Host "  classes in dex: $classesFound"
Get-FileHash $dex -Algorithm SHA256 | Format-List
Get-Item $dex | Select-Object FullName, Length
