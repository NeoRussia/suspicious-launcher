# Scenario tests for the Suspicious Speculars patcher, run against throwaway instances.
#
# Exercises everything that does not need Minecraft: the shader patch and its refusals, base
# selection, the pack install/update/404/hash paths, the dependency states and the dormancy rule.
# The CLI and the mod call the same Patcher, so this covers the mod's logic; only the in-game
# screen, chat lines and the Cleanroom-session guard need the game itself.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\test\run-scenarios.ps1
#
# Each scenario builds its own instance under .scratch\ss-scenarios (inside this project) and prints
# PASS/FAIL.
#
# This is the development suite: it expects the shader project at Work\shader (the stock and repacked
# Eclipse zips in workspace\, the pack host in host\), a Prism instance carrying Demonica and the
# Cleanroom relauncher, and a JDK 8 on PATH. Missing pieces are reported as SKIP, never as a pass.
param(
    [string]$Root = (Split-Path $PSScriptRoot -Parent),
    [string]$ShaderRoot = (Join-Path (Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent) "shader"),
    [string]$Repo = "",
    [string]$Work = (Join-Path (Split-Path $PSScriptRoot -Parent) ".scratch\ss-scenarios"),
    [string]$GameInstance = "I:\Games\PrismLauncher-Windows-MinGW-w64-Portable-10.0.2\instances\supersymmetry-0.2.0.2\minecraft"
)

$ErrorActionPreference = "Stop"
if (-not $Repo) { $Repo = Join-Path $ShaderRoot "host" }
$java = "C:\Program Files\Zulu\zulu-8\bin\java.exe"
$javac = "C:\Program Files\Zulu\zulu-8\bin\javac.exe"
$cli = Join-Path $Root "prelauncher\suspicious-speculars-pre.jar"
$data = Join-Path $Root "patcher\data"
$baseZip = Join-Path $ShaderRoot "workspace\research\eclipse-base-e89e6c6.zip"
$decoyZip = Join-Path $ShaderRoot "workspace\dist\Eclipse-skytest.zip"
$mc = $GameInstance
$forgeBin = Join-Path $env:USERPROFILE ".gradle\caches\minecraft\net\minecraftforge\forge\1.12.2-14.23.5.2838\stable\39\forgeBin-1.12.2-14.23.5.2838.jar"
$log4j = Join-Path $env:USERPROFILE ".gradle\caches\cleanroom_gradle\maven_downloader\org\apache\logging\log4j\log4j-api\2.8.1\log4j-api-2.8.1.jar"
$toolchain = (Test-Path $forgeBin) -and (Test-Path $log4j)

$script:pass = 0
$script:fail = 0

function Check($name, $condition, $detail) {
    if ($condition) {
        $script:pass++
        "  PASS  $name"
    } else {
        $script:fail++
        "  FAIL  $name" + $(if ($detail) { "  ($detail)" } else { "" })
    }
}

# A repository stand-in: packs plus a manifest, addressed by file: URLs so no network is involved.
#   -UpdateBytes  the base pack's entry is a different file, so its hash really changes
#   -MissingFile  the manifest lists the addon but the file is not in the repository
#   -CorruptHash  the manifest records a wrong hash for the base pack
function New-Repo($dir, $manifestVersion, [switch]$MissingFile, [switch]$CorruptHash, [switch]$UpdateBytes) {
    Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path "$dir\packs" | Out-Null
    $rows = @("MANIFEST`t1", "VERSION`t$manifestVersion")
    $all = Get-ChildItem (Join-Path $Repo "packs") -File
    foreach ($f in $all) {
        $isAddon = $f.Name -like "*Addon*"
        $source = $f
        if ($UpdateBytes -and -not $isAddon) {
            $source = $all | Where-Object { $_.Name -like "*Addon*" } | Select-Object -First 1
        }
        $sha = (Get-FileHash $source.FullName -Algorithm SHA256).Hash.ToLower()
        if ($CorruptHash -and -not $isAddon) { $sha = ("0" * 64) }
        if (-not ($MissingFile -and $isAddon)) { Copy-Item $source.FullName "$dir\packs\$($f.Name)" -Force }
        $rows += "PACK`t$($f.Name)`t$sha`tpacks/$([uri]::EscapeDataString($f.Name))"
    }
    $rows | Set-Content -Path "$dir\packs.txt" -Encoding utf8
    return (New-Object System.Uri((Join-Path $dir "packs.txt"))).AbsoluteUri
}

function New-Instance($dir) {
    Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path "$dir\shaderpacks", "$dir\mods", "$dir\config",
        "$dir\resourcepacks", "$dir\logs" | Out-Null
    Copy-Item $baseZip "$dir\shaderpacks\Eclipse-e89e6c6.zip"
    Copy-Item "$mc\mods\Demonica-0.9.1.jar" "$dir\mods\" -Force
    Copy-Item "$mc\mods\-cleanroom-relauncher-1.3.0.jar" "$dir\mods\" -Force
    Set-Content -Path "$dir\options.txt" -Value @('version:1343', 'resourcePacks:["kura-lave-susy.zip"]')
}

function Run($instance, $manifest, [string]$extra) {
    # -D goes before -jar; the value is a file: URL to the local repository stand-in
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        if ($extra) {
            $out = & $java "-Dsuspiciousspeculars.manifestUrl=$manifest" -jar $cli `
                --game-dir $instance --data $data $extra 2>&1
        } else {
            $out = & $java "-Dsuspiciousspeculars.manifestUrl=$manifest" -jar $cli `
                --game-dir $instance --data $data 2>&1
        }
        return ($out | Out-String)
    } finally {
        $ErrorActionPreference = $previous
    }
}

function StateOf($instance) {
    $file = "$instance\config\suspicious-speculars.properties"
    $props = @{}
    if (Test-Path $file) {
        foreach ($line in Get-Content $file) {
            if ($line -match '^\s*([^#=]+)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() }
        }
    }
    return $props
}

$repoDir = Join-Path $Work "repo"
$inst = Join-Path $Work "instance"

"== Suspicious Speculars scenario tests =="
New-Item -ItemType Directory -Force -Path $Work | Out-Null

# 1. fresh install
$manifest = New-Repo (Join-Path $Work "repo-1") "1.0"
New-Instance $inst
$out = Run $inst $manifest
$st = StateOf $inst
Check "fresh install reports installed" ($st['status'] -eq 'installed') $st['status']
# the count is whatever the patch data says: EDIT and COPY entries both write a file
$patchCount = (Select-String -Path (Join-Path $data "patch.txt") -Pattern '^(EDIT|COPY) ' | Measure-Object).Count
Check "fresh install patched $patchCount files" ($out -match "patched $patchCount files")
Check "shader written" (Test-Path "$inst\shaderpacks\Eclipse-SUSY.zip")
Check "both packs installed" ((Get-ChildItem "$inst\resourcepacks" -File | Measure-Object).Count -eq 2)
Check "both packs enabled in options.txt" ((Get-Content "$inst\options.txt" -Raw) -match 'Suspicious Speculars - Eclipse Addon.zip' -and (Get-Content "$inst\options.txt" -Raw) -match '"Suspicious Speculars.zip"')
Check "no restart needed at startup" ($st['pendingRestart'] -eq 'false') $st['pendingRestart']
Check "state records the manifest version" ($st['packVersion'] -eq '1.0') $st['packVersion']

# 2. dormant
$out = Run $inst $manifest
$st = StateOf $inst
Check "second run is dormant" ($st['status'] -eq 'current' -and $out -match 'nothing to do') $st['status']

# 3. update offered, nothing replaced (this is what the game does at startup)
$oldHash = (Get-FileHash "$inst\resourcepacks\Suspicious Speculars.zip" -Algorithm SHA256).Hash
$manifest2 = New-Repo (Join-Path $Work "repo-2") "2.0" -UpdateBytes
$out = Run $inst $manifest2 "--check"
$st = StateOf $inst
Check "check mode reports packs-update" ($st['status'] -eq 'packs-update') $st['status']
Check "check mode records the new version" ($st['availableVersion'] -eq '2.0') $st['availableVersion']
Check "check mode records which pack differs" ($st['updatePacks'] -match 'Suspicious Speculars.zip')
Check "check mode leaves the pack alone" ((Get-FileHash "$inst\resourcepacks\Suspicious Speculars.zip" -Algorithm SHA256).Hash -eq $oldHash)

# 4. install applies it
$out = Run $inst $manifest2
$st = StateOf $inst
Check "install after check reports installed" ($st['status'] -eq 'installed') $st['status']
Check "install clears updateAvailable" ($st['updateAvailable'] -eq 'false') $st['updateAvailable']

# 5. missing pack in the repository (404 / absent)
$manifestMissing = New-Repo (Join-Path $Work "repo-3") "3.0" -MissingFile
Remove-Item "$inst\resourcepacks\*" -Force
$out = Run $inst $manifestMissing
$st = StateOf $inst
Check "missing pack reports packs-missing" ($st['status'] -eq 'packs-missing') $st['status']
Check "missing pack names the file" ($st['message'] -match 'Eclipse Addon')

# 6. wrong bytes under the right name
$manifestBad = New-Repo (Join-Path $Work "repo-4") "4.0" -CorruptHash
Remove-Item "$inst\resourcepacks\*" -Force
$out = Run $inst $manifestBad
$st = StateOf $inst
Check "hash mismatch reports packs-missing" ($st['status'] -eq 'packs-missing') $st['status']
Check "hash mismatch deletes the bad file" (-not (Test-Path "$inst\resourcepacks\Suspicious Speculars.zip"))

# 7. no Eclipse base at all (dry run: a real run would fetch the pinned archive now, so this is the
#    only deterministic way to see the base-missing verdict without the network)
New-Instance $inst
Remove-Item "$inst\shaderpacks\*" -Force
$out = Run $inst $manifest "--dry-run"
$st = StateOf $inst
Check "no base reports base-missing" ($out -match 'status: base-missing')
Check "no base would fetch the archive" ($out -match 'would fetch Eclipse-e89e6c6.zip')
Check "no base writes no shader" (-not (Test-Path "$inst\shaderpacks\Eclipse-SUSY.zip"))

# 8. a decoy Eclipse build next to the right one
New-Instance $inst
Copy-Item $decoyZip "$inst\shaderpacks\A-decoy.zip"
$out = Run $inst $manifest
$st = StateOf $inst
Check "decoy is skipped, real base used" ($st['status'] -eq 'installed' -and $out -match 'skip A-decoy.zip') $st['status']
Remove-Item "$inst\shaderpacks\Eclipse-e89e6c6.zip" -Force
$out = Run $inst $manifest --force
$st = StateOf $inst
Check "decoy alone reports base-mismatch" ($st['status'] -eq 'base-mismatch') $st['status']

# 9. dependency states. Demonica is fetched when Cleanroom is present, so a run with the network
#    either installs it or reports the failed fetch - both are honest; what must not happen is a
#    silent "ok" with no jar.
New-Instance $inst
Remove-Item "$inst\mods\Demonica-0.9.1.jar" -Force
$out = Run $inst $manifest
$st = StateOf $inst
$fetched = Test-Path "$inst\mods\Demonica-0.9.1.jar"
Check "missing Demonica is fetched or reported" `
    (($fetched -and $st['prereq'] -eq 'ok') -or
     (-not $fetched -and $st['prereq'] -eq 'demonica' -and $st['message'] -match 'Demonica/releases/latest')) `
    "prereq=$($st['prereq']) fetched=$fetched"
Check "missing Demonica still installs the shader" ($st['status'] -eq 'installed') $st['status']
New-Instance $inst
Remove-Item "$inst\mods\-cleanroom-relauncher-1.3.0.jar" -Force
$out = Run $inst $manifest
$st = StateOf $inst
Check "missing Cleanroom is recorded" ($st['prereq'] -eq 'cleanroom') $st['prereq']
Check "missing Cleanroom mentions the installer" ($st['message'] -match 'Cleanroom/releases/latest')

# 10. mods in Cleanroom's versioned subfolder
New-Instance $inst
New-Item -ItemType Directory -Force -Path "$inst\mods\1.12.2" | Out-Null
Move-Item "$inst\mods\Demonica-0.9.1.jar" "$inst\mods\1.12.2\"
Move-Item "$inst\mods\-cleanroom-relauncher-1.3.0.jar" "$inst\mods\1.12.2\"
$out = Run $inst $manifest
$st = StateOf $inst
Check "versioned mods/1.12.2 is scanned" ($st['demonica'] -eq '0.9.1' -and $st['cleanroom'] -eq '1.3.0')

# 11. a write that fails must keep the working shader, report error, and say so in the transcript
New-Instance $inst
$out = Run $inst $manifest
$transcript = "$inst\logs\suspicious-speculars.log"
$before = (Get-FileHash "$inst\shaderpacks\Eclipse-SUSY.zip" -Algorithm SHA256).Hash
# a read-only file where the patcher writes beside the target: the new zip cannot be created
$tmp = "$inst\shaderpacks\Eclipse-SUSY.zip.tmp"
Set-Content -Path $tmp -Value "locked"
(Get-Item $tmp).IsReadOnly = $true
Set-Content -Path $transcript -Value ""
$out = Run $inst $manifest "--force"
$st = StateOf $inst
Check "a failed write reports error" ($st['status'] -eq 'error') $st['status']
Check "a failed write records the reason" (-not [string]::IsNullOrWhiteSpace($st['message'])) $st['message']
Check "a failed write leaves the shader intact" `
    ((Get-FileHash "$inst\shaderpacks\Eclipse-SUSY.zip" -Algorithm SHA256).Hash -eq $before)
Check "a failed write is in the transcript" ((Get-Content $transcript -Raw) -match 'status: error')
Check "a failed write records the run once" `
    (((Get-Content $transcript) | Where-Object { $_ -like '--- *' } | Measure-Object).Count -eq 1)
(Get-Item $tmp).IsReadOnly = $false
Remove-Item $tmp -Force

# 12. a clean run after the error settles, and still records exactly one transcript block
Set-Content -Path $transcript -Value ""
$out = Run $inst $manifest
$st = StateOf $inst
Check "the run after a failed write settles" ($st['status'] -eq 'current') $st['status']
Check "a normal run records one transcript block" `
    (((Get-Content $transcript) | Where-Object { $_ -like '--- *' } | Measure-Object).Count -eq 1)

# 13. the mod names its own build, with another mod's mcmod.info ahead of it on the classpath
#     (the classpath-wide "/mcmod.info" lookup used to return Demonica's 0.9.1)
$modJar = Get-ChildItem (Join-Path $Root "mod\build\libs") -Filter "suspicious-speculars-*.jar" |
    Where-Object { $_.Name -notlike "*-sources.jar" } | Sort-Object LastWriteTime | Select-Object -Last 1
if ($modJar -and $toolchain) {
    $probe = Join-Path $Work "probe"
    New-Item -ItemType Directory -Force -Path $probe | Out-Null
    & $javac -d $probe (Join-Path $Root "test\VersionProbe.java") 2>&1 | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($modJar.FullName)
    $reader = New-Object System.IO.StreamReader($zip.GetEntry("mcmod.info").Open())
    $info = $reader.ReadToEnd()
    $reader.Close()
    $zip.Dispose()
    $own = ([regex]'"version"\s*:\s*"([^"]+)"').Match($info).Groups[1].Value
    $reported = (& $java -cp "$mc\mods\Demonica-0.9.1.jar;$($modJar.FullName);$forgeBin;$log4j;$probe" `
        VersionProbe | Out-String).Trim()
    Check "mod reports its own build" ($reported -eq $own) "reported=$reported own=$own"
    Remove-Item $probe -Recurse -Force
} else {
    "  SKIP  mod reports its own build (no built jar)"
}

# 14. OptiFine and Nothirium are named as incompatible with Demonica, and nothing is done about them
New-Instance $inst
$fakeJars = @("$inst\mods\OptiFine_1.12.2_HD_U_G5.jar", "$inst\mods\Nothirium-1.12.2-0.2.9.jar")
foreach ($jar in $fakeJars) {
    & py -c "import sys, zipfile; zipfile.ZipFile(sys.argv[1], 'w').writestr('x.txt', 'x')" $jar
}
$out = Run $inst $manifest
$st = StateOf $inst
Check "OptiFine is named as incompatible" ($out -match 'warning: OptiFine is installed - not compatible with Demonica')
Check "Nothirium is named as incompatible" ($out -match 'warning: Nothirium is installed - not compatible with Demonica')
Check "an incompatible mod is left in place" ((Test-Path $fakeJars[0]) -and (Test-Path $fakeJars[1]))
Check "an incompatible mod does not block the install" ($st['status'] -eq 'installed') $st['status']
Check "an incompatible mod does not change the prerequisite verdict" ($st['prereq'] -eq 'ok') $st['prereq']

# 15. the hand-over decision guards: the wrong answer opens a window on every launch, or none at all
if ($modJar) {
    $plan = Join-Path $Work "planclasses"
    New-Item -ItemType Directory -Force -Path $plan | Out-Null
    & $javac -cp $modJar.FullName -d $plan (Join-Path $Root "mod\test\CorePlanTest.java") 2>&1 | Out-Null
    $planOut = & $java -cp "$($modJar.FullName);$plan" CorePlanTest 2>&1 | Out-String
    $planExit = $LASTEXITCODE
    Check "hand-over decision guards pass" ($planExit -eq 0) `
        (($planOut -split "`r?`n" | Where-Object { $_ -match 'passed,' }) -join '')
    Remove-Item $plan -Recurse -Force
} else {
    "  SKIP  hand-over decision guards (no built jar)"
}

# 16. a failed hand-over must not disable the window forever: a completed run clears the guard
New-Instance $inst
Set-Content -Path "$inst\config\suspicious-speculars.properties" -Value "relaunchAttempted=true"
$out = Run $inst $manifest
$st = StateOf $inst
Check "a completed run clears the hand-over guard" ($st['relaunchAttempted'] -eq 'false') $st['relaunchAttempted']

# 17. the patch data changes between releases, and a shader that still matches its recorded hash is no
#     evidence that it was patched from this data: an install that only compared hashes kept its old
#     shader across a release that changed the patch, so the release's new shader edits never arrived
New-Instance $inst
$manifest17 = New-Repo (Join-Path $Work "repo-17") "1.0"
$out = Run $inst $manifest17
$stateFile = "$inst\config\suspicious-speculars.properties"
$st = StateOf $inst
Check "a fresh install records its patch data identity" ($st.ContainsKey('dataVersion')) `
    ("keys: " + (($st.Keys | Sort-Object) -join ","))
Check "the shader is patched on that first run" ($out -match 'patched \d+ files')
$shaderBefore = (Get-FileHash "$inst\shaderpacks\Eclipse-SUSY.zip" -Algorithm SHA256).Hash
(Get-Content $stateFile) | Where-Object { $_ -notmatch '^dataVersion=' } | Set-Content $stateFile
Add-Content $stateFile 'dataVersion=0.0.0-stale 0'
$out = Run $inst $manifest17
$st = StateOf $inst
$shaderAfter = (Get-FileHash "$inst\shaderpacks\Eclipse-SUSY.zip" -Algorithm SHA256).Hash
Check "a stale patch data identity repatches the shader" ($out -match 'patched \d+ files') `
    (($out -split "`r?`n" | Where-Object { $_ -match 'shader|patched' }) -join ' / ')
Check "the repatch rewrites the shader file" ($shaderBefore -ne $shaderAfter)
Check "the install records the data it patched with" ($st['dataVersion'] -eq '') ("[" + $st['dataVersion'] + "]")
$out = Run $inst $manifest17
Check "the shader is current again on the run after" ($out -match 'shader: current') `
    (($out -split "`r?`n" | Where-Object { $_ -match 'shader' }) -join ' / ')

# 18. the window's button must say what the state means: a complete instance is not a fresh one, and
#     "Update" belongs to a newer pack release, not to any window that happens to open
$launcherJar = Join-Path $Root "dist\suspicious-speculars-launcher.jar"
if (Test-Path $launcherJar) {
    $labelGame = Join-Path $Work "labelprobe-game"
    Remove-Item $labelGame -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path "$labelGame\config" | Out-Null
    $labelOut = Join-Path $Work "labelprobe"
    Remove-Item $labelOut -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $labelOut | Out-Null
    & $javac -cp $launcherJar -d $labelOut (Join-Path $Root "test\ActionLabelProbe.java") 2>&1 | Out-Null
    $labels = & $java -cp "$launcherJar;$labelOut" suspiciousspeculars.launcher.ActionLabelProbe $labelGame $data 2>&1
    Check "a fresh state says Install" ($labels[0] -eq "Install") (($labels -join " / "))
    Check "a current state says Reinstall" ($labels[1] -eq "Reinstall") (($labels -join " / "))
    Check "a newer pack release says Update" ($labels[2] -eq "Update") (($labels -join " / "))
    Check "an up-to-date release says Reinstall" ($labels[3] -eq "Reinstall") (($labels -join " / "))

    # 19. the window must give back the launch it took, even when the run changed nothing
    $handBackOut = Join-Path $Work "handbackprobe"
    Remove-Item $handBackOut -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $handBackOut | Out-Null
    & $javac -cp $launcherJar -d $handBackOut (Join-Path $Root "test\HandBackProbe.java") 2>&1 | Out-Null
    $hb = & $java -cp "$launcherJar;$handBackOut" suspiciousspeculars.launcher.HandBackProbe 2>&1
    Check "a changed run hands the launch back" ($hb[0] -eq "true") (($hb -join " / "))
    Check "a no-op run spawned by the mod hands it back" ($hb[1] -eq "true") (($hb -join " / "))
    Check "a no-op run opened by hand does not" ($hb[2] -eq "false") (($hb -join " / "))
    Check "a dry run never relaunches" ($hb[3] -eq "false") (($hb -join " / "))
    Check "a patch-only run never relaunches" ($hb[4] -eq "false") (($hb -join " / "))
    Check "no launcher, no relaunch attempt" ($hb[5] -eq "false") (($hb -join " / "))
} else {
    "  SKIP  window button labels (no launcher jar)"
}

# 20. patch data: the shader repository decides what is current, the jar is only the fallback
if ($modJar -and $toolchain) {
    $probeDir = Join-Path $Work "patchprobe"
    Remove-Item $probeDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
    & $javac -cp "$($modJar.FullName);$log4j" -d $probeDir (Join-Path $Root "test\PatchDataProbe.java") 2>&1 | Out-Null

    $jarVersion = (Get-Content (Join-Path $Root "patcher\data-bundle.version")).Trim()
    $eclipse = ([regex]'COMMIT\s*=\s*"([0-9a-f]+)"').Match(
        (Get-Content (Join-Path $Root "core\suspiciousspeculars\patch\EclipseFetch.java") -Raw)).Groups[1].Value
    $missingUrl = (New-Object System.Uri((Join-Path $Work "no-such-repo\patch.txt"))).AbsoluteUri

    function New-PatchRepo($dir, $base, [int]$requires = 1) {
        Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path "$dir\patch" | Out-Null
        & py -c "import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], 'w'); z.writestr('patch.txt', 'probe\n'); z.writestr('sentinel.txt', 'yes\n'); z.close()" "$dir\patch\bundle.zip"
        $sha = (Get-FileHash "$dir\patch\bundle.zip" -Algorithm SHA256).Hash.ToLower()
        @("MANIFEST`t1", "REQUIRES`t$requires", "VERSION`t$sha 2", "BASE`t$base",
          "PATCH`tbundle.zip`t$sha`tbundle.zip") |
            Set-Content -Path "$dir\patch\patch.txt" -Encoding ascii
        return (New-Object System.Uri("$dir\patch\patch.txt")).AbsoluteUri
    }

    function Invoke-PatchProbe($dir, $url, [switch]$Keep, [switch]$Off) {
        if (-not $Keep) { Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue }
        $probeArgs = @($dir)
        if ($Off) { $probeArgs += "off" }
        # java writes the log4j "no core" notice to stderr; with Stop that would abort the run
        $previous = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            $raw = & $java "-Dsuspiciousspeculars.patchUrl=$url" `
                -cp "$($modJar.FullName);$log4j;$probeDir" suspiciousspeculars.PatchDataProbe @probeArgs 2>&1 | Out-String
        } finally {
            $ErrorActionPreference = $previous
        }
        $fields = @{ raw = $raw }
        foreach ($line in $raw -split "`r?`n") {
            if ($line -match '^(\w+)=(.*)$') { $fields[$Matches[1]] = $Matches[2].Trim() }
        }
        return $fields
    }

    $remoteUrl = New-PatchRepo (Join-Path $Work "patchrepo") $eclipse
    $remoteVersion = ((Get-Content (Join-Path $Work "patchrepo\patch\patch.txt") |
        Where-Object { $_ -like 'VERSION*' }) -split "`t")[1]

    $probeData = Join-Path $Work "patchdata-remote"
    $got = Invoke-PatchProbe $probeData $remoteUrl
    Check "a repository patch replaces the bundled one" ($got['marker'] -eq $remoteVersion) $got['marker']
    Check "its files are the ones on disk" ($got['sentinel'] -eq 'True') "sentinel=$($got['sentinel'])"
    Check "the download is consumed, not left behind" ($got['download'] -eq 'False')
    $again = Invoke-PatchProbe $probeData $remoteUrl -Keep
    Check "a second start downloads nothing" (($again['marker'] -eq $remoteVersion) -and ($again['download'] -eq 'False'))
    Check "a second start rewrites nothing" ($again['stamp'] -eq $got['stamp']) ("$($got['stamp']) -> $($again['stamp'])")
    $offline = Invoke-PatchProbe $probeData $missingUrl -Keep
    Check "an offline start keeps the newer patch" ($offline['marker'] -eq $remoteVersion) $offline['marker']
    Check "an offline start rewrites nothing" ($offline['stamp'] -eq $got['stamp'])
    $off = Invoke-PatchProbe $probeData $remoteUrl -Keep -Off
    Check "launch check off touches neither network nor data" `
        (($off['marker'] -eq $remoteVersion) -and ($off['stamp'] -eq $got['stamp'])) $off['marker']

    $wrongUrl = New-PatchRepo (Join-Path $Work "patchrepo-wrongbase") "deadbee"
    $wrong = Invoke-PatchProbe (Join-Path $Work "patchdata-wrongbase") $wrongUrl
    Check "a patch for another Eclipse commit is refused" ($wrong['marker'] -eq $jarVersion) $wrong['marker']
    Check "the bundled data is used instead" ($wrong['sentinel'] -eq 'False')

    $futureUrl = New-PatchRepo (Join-Path $Work "patchrepo-format") $eclipse -Requires 99
    $future = Invoke-PatchProbe (Join-Path $Work "patchdata-format") $futureUrl
    Check "a patch needing a newer format is refused" ($future['marker'] -eq $jarVersion) $future['marker']
    Check "the bundled data is used for it too" ($future['sentinel'] -eq 'False')

    $missing = Invoke-PatchProbe (Join-Path $Work "patchdata-missing") $missingUrl
    Check "an unreachable repository falls back to the jar" ($missing['marker'] -eq $jarVersion) $missing['marker']
    Check "and unpacks the bundled data" ($missing['patch'] -eq 'True' -and $missing['sentinel'] -eq 'False')

    Remove-Item $probeDir -Recurse -Force -ErrorAction SilentlyContinue
} else {
    "  SKIP  patch data from the repository (no built jar or toolchain)"
}

# 21. the early half must not undo a repository patch, and must replace rather than merge
if ($modJar -and $toolchain) {
    $coreProbe = Join-Path $Work "coreprobe"
    Remove-Item $coreProbe -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $coreProbe | Out-Null
    & $javac -cp "$($modJar.FullName);$log4j;$forgeBin" -d $coreProbe (Join-Path $Root "test\CoreDataProbe.java") 2>&1 | Out-Null

    $coreData = Join-Path $Work "coredata"
    Remove-Item $coreData -Recurse -Force -ErrorAction SilentlyContinue
    $previous = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $raw = & $java -cp "$($modJar.FullName);$log4j;$forgeBin;$coreProbe" `
            suspiciousspeculars.CoreDataProbe $coreData $modJar.FullName 2>&1 | Out-String
    } finally {
        $ErrorActionPreference = $previous
    }
    $fields = @{}
    foreach ($line in $raw -split "`r?`n") {
        if ($line -match '^(\w+)=(.*)$') { $fields[$Matches[1]] = $Matches[2].Trim() }
    }
    $jarBundle = (Get-Content (Join-Path $Root "patcher\data-bundle.version")).Trim()
    Check "a repository patch survives the early unpack" ($fields['marker1'] -eq "remote-version 999") $fields['marker1']
    Check "and keeps its provenance" ($fields['source1'] -eq "repository") $fields['source1']
    Check "and its files are left alone" ($fields['keep1'] -eq 'True') "keep=$($fields['keep1'])"
    Check "bundled data is replaced by the jar" ($fields['marker2'] -eq $jarBundle) $fields['marker2']
    Check "and records itself as bundled" ($fields['source2'] -eq "bundled") $fields['source2']
    Check "and the old directory is not merged" ($fields['keep2'] -eq 'False') "keep=$($fields['keep2'])"

    Remove-Item $coreProbe -Recurse -Force -ErrorAction SilentlyContinue
} else {
    "  SKIP  early unpack rules (no built jar or toolchain)"
}

""
"== $script:pass passed, $script:fail failed =="
if ($script:fail -gt 0) { exit 1 }
