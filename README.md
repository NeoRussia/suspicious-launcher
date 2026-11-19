# SUSY Shader Option

Installer for the **Suspicious Speculars** shader pack: a Forge 1.12.2 mod with a coremod hand-over,
the patch data it applies, and the Swing launcher window it ships.

The repository and artifact names keep their historic `suspicious-launcher` / `suspicious-speculars`
also known as "SUSY Shader Option".

It is not the resource packs and not the shader. The packs are fetched at install time from
[NeoRussia/suspicious-speculars](https://github.com/NeoRussia/suspicious-speculars), and the shader
base is fetched from its own author. Nothing of theirs is redistributed here.

## What it does

First launch of a Cleanroom instance:

1. the coremod runs before FML constructs any mod (~5–8 s into the launch), unpacks the launcher and
   the patch data into `config\suspicious-speculars\`, and hands over;
2. the window opens at 528×498 and states what is missing — Cleanroom, Demonica, or a mod that cannot
   work next to Demonica (OptiFine, Nothirium: named, never removed);
3. **Install** fetches the Eclipse `e89e6c6` archive from the author when `shaderpacks\` has none,
   applies 22 edits into `Eclipse-SUSY.zip`, installs the two resource packs (SHA-256 checked),
   fetches the pinned Demonica `0.9.1` (fixed asset, SHA-256 verified) into `mods\`, writes the SUSY
   option set and points Demonica at the result;
4. **Continue** relaunches the instance through the launcher's own CLI. On launchers with no external
   launch path (CurseForge and friends) it asks for a press of Play instead;
5. every later launch is dormant: when the recorded state still matches what is on disk, no window
   opens and nothing is downloaded.

An error anywhere ends in the band with the reason, `status=error` in the state file, and an ERROR
line in the client log on the next launch.

The installer itself applies no Cleanroom version gate: `Patcher.detectPrerequisites` only asks
whether Cleanroom is there (the relauncher jar, the loader itself, or the instance's
`patches\net.minecraftforge.json`). The 0.7.4 floor below belongs to Demonica, which is what the
shader actually runs on.

## Requirements

| | |
|---|---|
| Cleanroom | 0.7.4 or newer — **Demonica's** build pin (`cleanroom_version = 0.7.4-alpha` in its `gradle.properties`), because its GL-state tracking follows the `lwjgly` window path Cleanroom 0.7.4 introduced. Tested here on 0.7.14-alpha only |
| Launcher | any; MultiMC-family launchers (Prism, PolyMC) get the automatic relaunch |
| Excluded | OptiFine and Nothirium cannot work alongside Demonica. They are detected and named only |
| Java | the launcher is Java 8 bytecode and runs on the instance's own JVM |

## Build

The launcher jar is embedded in the mod jar, so the order matters and `dist\` must be populated first:

```powershell
# 1. the window (Java 8; set JAVA8_HOME, or Zulu 8 is found at C:\Program Files\Zulu\zulu-8)
powershell -NoProfile -ExecutionPolicy Bypass -File launcher\build.ps1 -NoDiff

# 2. the mod (ForgeGradle 2.3 needs Gradle 4.10.3 and JAVA_HOME=Zulu 8, not 25)
$env:JAVA_HOME = "C:\Program Files\Zulu\zulu-8"
gradle build --offline
# -> mod\build\libs\suspicious-speculars-0.1.3pre-<n>.jar
```

Install by dropping that jar into `mods\`. The build number in `mod\build.number` advances on every
successful jar build; the `@Mod` version stays `0.1.3pre` until a release is cut.

`launcher\build.ps1` without `-NoDiff` also runs the window's pixel gate, which lives with the design
harness (`Work\ui-harness` in the development workspace, not in this repository) and prints
`no harness beside this repo` when it is absent.

`patcher\data\` — the patch definition, the replacement files and the SUSY option seed — is generated
from the shader workspace and committed here as a build product. Regenerating it needs that workspace,
not this repository.

## Layout

| Path | What it is |
|---|---|
| `core\suspiciousspeculars\patch\` | the patcher: no Forge, no Minecraft, shared by every front end |
| `mod\src\main\java\suspiciousspeculars\` | the Forge mod and the `FMLCorePlugin` hand-over |
| `launcher\src\` | the Swing window: drawing, font, state machine |
| `launcher\ui\` | the window's art, font sheet and sound cue, packaged into the jar |
| `patcher\data\` | `patch.txt`, replacement files, `packs.txt` fallback, `susy-options.txt` |
| `patcher\build_edits.py`, `bundle.py` | regenerate and pack that data; `data-bundle.zip` is what the mod carries |
| `prelauncher\` | command-line front end, for a launcher pre-launch command |
| `test\run-scenarios.ps1` | 43 checks of the patcher against throwaway instances, no game needed |
| `test-instance\` | a throwaway instance the checks and previews run against (not committed) |
| `dist\`, `mod\build\` | build output (not committed) |

`installer-preview.gif`, `installer-*.png` and `render_preview.py` are the window's pre-port mockups,
kept for history; the shipped window is `launcher\src`.

## What it writes into an instance

| Path | |
|---|---|
| `config\suspicious-speculars.properties` | state: status, hashes, versions, the last message |
| `config\suspicious-speculars\` | unpacked launcher jar and patch data |
| `config\shaders.properties` | the shader selection |
| `shaderpacks\Eclipse-*.zip`, `Eclipse-SUSY.zip.txt` | fetched base, patched shader, saved options |
| `resourcepacks\Suspicious Speculars*.zip` | the two packs |
| `mods\Demonica-0.9.1.jar` | the pinned release, fetched only when the instance has no Demonica; existing ones are never replaced |
| `options.txt` | both packs enabled in `resourcePacks` |
| `logs\suspicious-speculars.log`, `suspicious-speculars-launcher.log` | transcript and window log |

To reset an instance to a first install, delete those and leave the mod jar in `mods\`.

## Testing

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File test\run-scenarios.ps1
```

It builds nothing: it exercises the patcher's CLI (already committed as
`prelauncher\suspicious-speculars-pre.jar`) against fresh instances - install, dormancy, pack update,
missing and corrupt packs, base selection, dependency states, a failed write, incompatible mods, the
hand-over decision, version reporting - and prints PASS/FAIL per check.

It is the development suite, not a self-contained one: it expects the shader workspace beside this
folder (the stock and repacked Eclipse zips under `..\chromapatch`), the pack host repository, a
Prism instance carrying Demonica and the Cleanroom relauncher, and a JDK 8. Anything missing is
reported as SKIP rather than passing silently; `-GameInstance`, `-Root` and `-Repo` override the
defaults. What needs a game: the window itself, the chat notices, and the coremod's hand-over.

## Licence

GPL-3.0-or-later — see [`LICENSE`](LICENSE). In short: use, modify and redistribute freely, but a
distributed fork must stay under the same licence and carry its source.

Contributions are expected to follow the pack's `AI_POLICY.md`: disclose AI assistance, and have a
human owner who can explain the change.

## AI disclosure

Built with AI assistance: the implementation — patcher, mod, coremod, window, tests and docs — was
written largely with the assistance of an AI coding agent (OpenCode, running a DeepSeek model) working from the
maintainer's design and decisions. The visual design, art, shader work, releases and in-game testing
are human, and no AI-generated textures, models or audio are included. The maintainer owns the code
and can explain it.
