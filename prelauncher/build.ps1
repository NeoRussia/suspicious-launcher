# Build the command-line front end: the pre-launch tool some launchers run before the game starts.
#
#   powershell -File prelauncher\build.ps1
#
# Output: prelauncher\suspicious-speculars-pre.jar (committed; the scenario tests run it as the CLI).
# It shares `core\` with the window and the mod, so it must be rebuilt whenever the patcher changes,
# otherwise an old jar would apply patches with old rules - silently skipping operations it does not
# understand.

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path $here -Parent
$jdkHome = $env:JAVA8_HOME
if (-not $jdkHome) { $jdkHome = 'C:\Program Files\Zulu\zulu-8' }
$jdk = Join-Path $jdkHome 'bin'
$javac = Join-Path $jdk 'javac.exe'
$jar = Join-Path $jdk 'jar.exe'
$out = Join-Path $here 'out'
$classes = Join-Path $out 'classes'
$target = Join-Path $here 'suspicious-speculars-pre.jar'

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

Remove-Item $target -Force -ErrorAction SilentlyContinue
& $jar cfe $target suspiciousspeculars.cli.PreLauncher -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

"prelauncher jar: {0:N0} B  {1}" -f (Get-Item $target).Length,
    (Get-FileHash $target -Algorithm SHA256).Hash.Substring(0, 8)
