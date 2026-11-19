# Build the launcher jar: Java 8 classes plus the GUI art, both carried inside the jar so the
# window can be dropped anywhere and still find its textures on the classpath.
#
#   powershell -File launcher\build.ps1            build, then run the pixel gate
#   powershell -File launcher\build.ps1 -NoDiff    build only
#
# Output: dist\suspicious-speculars-launcher.jar and a copy beside the sources, matching the
# location the patcher stages into the instance.

param([switch]$NoDiff)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path $here -Parent
# The launcher is Java 8 bytecode. Point JAVA8_HOME at a JDK 8 (Zulu 8 is the usual choice on
# Windows); the literal is only the default when the variable is unset.
$jdkHome = $env:JAVA8_HOME
if (-not $jdkHome) { $jdkHome = 'C:\Program Files\Zulu\zulu-8' }
$jdk = Join-Path $jdkHome 'bin'
$javac = Join-Path $jdk 'javac.exe'
$jar = Join-Path $jdk 'jar.exe'
$out = Join-Path $here 'out'
$classes = Join-Path $out 'classes'
$jarpath = Join-Path $root 'dist\suspicious-speculars-launcher.jar'

if (-not (Test-Path $javac)) {
    throw "javac not found at $javac - set JAVA8_HOME to a JDK 8 installation"
}

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = @()
$sources += Get-ChildItem (Join-Path $here 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$sources += Get-ChildItem (Join-Path $root 'core') -Recurse -Filter *.java | ForEach-Object { $_.FullName }

& $javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

# The window loads /ui/*.png from the classpath.
$uiOut = Join-Path $classes 'ui'
New-Item -ItemType Directory -Force -Path $uiOut | Out-Null
# the split UI's art/, font/ and sound/ trees; the old window's flat art is no longer drawn
$artOut = Join-Path $uiOut 'art'
$fontOut = Join-Path $uiOut 'font'
$soundOut = Join-Path $uiOut 'sound'
New-Item -ItemType Directory -Force -Path $artOut | Out-Null
New-Item -ItemType Directory -Force -Path $fontOut | Out-Null
New-Item -ItemType Directory -Force -Path $soundOut | Out-Null
if (Test-Path (Join-Path $here 'ui\art')) { Copy-Item (Join-Path $here 'ui\art\*.png') $artOut -Force }
if (Test-Path (Join-Path $here 'ui\font')) { Copy-Item (Join-Path $here 'ui\font\*.png') $fontOut -Force }
# Duck mode's cue: PCM, because Java 8's javax.sound cannot decode the web build's mp3
if (Test-Path (Join-Path $here 'ui\sound')) { Copy-Item (Join-Path $here 'ui\sound\*.wav') $soundOut -Force }
"art: " + ((Get-ChildItem $uiOut -Recurse -Filter *.png | Measure-Object).Count) + " files"
"sound: " + ((Get-ChildItem $uiOut -Recurse -Filter *.wav | Measure-Object).Count) + " file(s)"

New-Item -ItemType Directory -Force -Path (Split-Path $jarpath -Parent) | Out-Null
Remove-Item $jarpath -Force -ErrorAction SilentlyContinue
& $jar cfe $jarpath suspiciousspeculars.launcher.Launcher -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Copy-Item $jarpath (Join-Path $here 'suspicious-speculars-launcher.jar') -Force
$size = (Get-Item $jarpath).Length
$hash = (Get-FileHash $jarpath -Algorithm SHA256).Hash.Substring(0, 8)
"launcher jar: {0:N0} B  {1}" -f $size, $hash

# --- pixel gate -------------------------------------------------------------------------------
# The checks live with the harness (Work\ui-harness\gate.ps1): it renders the 27 states from this
# jar, diffs them against the reference frames, and checks the tooltips, the error band, the tile
# links and the sound cue. -NoDiff builds only; the harness can be run on its own afterwards.
$gate = Join-Path $root '..\ui-harness\gate.ps1'
if ($NoDiff) {
    "diff: skipped (-NoDiff)"
} elseif (Test-Path $gate) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $gate -SkipBuild
    if ($LASTEXITCODE -ne 0) { throw "pixel gate: the window is worse than the baseline" }
} else {
    "diff: no harness beside this repo (expected ..\ui-harness\gate.ps1)"
}
