"""Bundle the patcher data into one archive for the mod jar, and publish the pack manifest.

Three outputs:

  data-bundle.zip          patch.txt + files/ (the shader edits), carried inside the mod jar
  data/packs.txt           the same manifest as a fallback, bundled so a fresh install works offline
  <repo>/packs.txt         the manifest the patcher prefers: publish this so pack updates ship
                           without rebuilding the mod

The manifest is line based and tab separated:

  MANIFEST 1
  VERSION <version>
  PACK <tab> name <tab> sha256 <tab> relative-url

Relative URLs resolve against the manifest's own location, so the same file works from the repo,
from a fork, or from a local folder.

Run after build_edits.py, split-pack.ps1, or whenever packs/ changes:
    py bundle.py [--version 2026-11-16.1] [--repo ..\\..\\suspicious-speculars] [--url-base <url>]
"""
import argparse
import datetime
import hashlib
import os
import shutil
import urllib.parse
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
PACKS = os.path.join(HERE, "..", "..", "suspicious-speculars", "packs")  # the repo's packs/
OUT = os.path.join(HERE, "data-bundle.zip")
VERSION = os.path.join(HERE, "data-bundle.version")
DEFAULT_REPO = os.path.join(HERE, "..", "..", "suspicious-speculars")
DEFAULT_URL_BASE = "https://raw.githubusercontent.com/NeoRussia/suspicious-speculars/main/packs/"

# Bundled: everything the patcher needs except the shipping packs themselves.
SKIP_DIRS = {"packs"}
# edits.json is the same patch in a JSON form, kept for patcher\apply.py (a dev tool). The Java
# patcher reads patch.txt only, so shipping the JSON costs 116 KB - 39% of this bundle - for nothing.
SKIP_FILES = {"edits.json"}


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default=datetime.date.today().strftime("%Y-%m-%d.1"))
    ap.add_argument("--repo", default=DEFAULT_REPO,
                    help="repository root that holds packs/; the manifest is written there")
    ap.add_argument("--url-base", default=DEFAULT_URL_BASE,
                    help="base URL for the pack entries; absolute, so the bundled copy works too")
    args = ap.parse_args()

    if not os.path.isdir(DATA):
        raise SystemExit("no data folder: run build_edits.py first")
    if not os.path.isdir(PACKS):
        raise SystemExit("no packs folder at %s - run split-pack.ps1" % PACKS)

    lines = ["MANIFEST\t1", "VERSION\t" + args.version]
    total = 0
    for name in sorted(os.listdir(PACKS)):
        full = os.path.join(PACKS, name)
        if not name.lower().endswith(".zip"):
            continue
        base = args.url_base if args.url_base.endswith("/") else args.url_base + "/"
        path = base + urllib.parse.quote(name)
        lines.append("PACK\t%s\t%s\t%s" % (name, sha256(full), path))
        total += os.path.getsize(full)
        print("pack:    %-45s %s  %.2f MB" % (name, sha256(full)[:16], os.path.getsize(full) / 1e6))
    manifest = "\n".join(lines) + "\n"

    with open(os.path.join(DATA, "packs.txt"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(manifest)
    print("manifest: %d packs, %.2f MB, version %s" % (len(lines) - 2, total / 1e6, args.version))

    if args.repo and os.path.isdir(args.repo):
        target = os.path.join(args.repo, "packs.txt")
        old = open(target, encoding="utf-8").read() if os.path.exists(target) else ""
        with open(target, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(manifest)
        print("published: %s%s" % (target, "" if old == manifest else " (changed)"))
    elif args.repo:
        print("published: skipped (%s does not exist; use --repo to point at the repository)" % args.repo)

    names = []
    for root, dirs, files in os.walk(DATA):
        dirs[:] = [d for d in dirs if os.path.relpath(os.path.join(root, d), DATA) not in SKIP_DIRS]
        for f in sorted(files):
            if f in SKIP_FILES:
                continue
            full = os.path.join(root, f)
            names.append((os.path.relpath(full, DATA).replace(os.sep, "/"), full))
    names.sort()

    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for rel, full in names:
            info = zipfile.ZipInfo(rel, date_time=(1980, 1, 1, 0, 0, 0))  # reproducible bytes
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(full, "rb") as fh:
                z.writestr(info, fh.read())

    digest = hashlib.sha256(open(OUT, "rb").read()).hexdigest()
    with open(VERSION, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("%s %d\n" % (digest, len(names)))
    print("bundle:   %s  %d files  %s  %.2f MB"
          % (os.path.basename(OUT), len(names), digest[:16], os.path.getsize(OUT) / 1e6))


if __name__ == "__main__":
    main()
