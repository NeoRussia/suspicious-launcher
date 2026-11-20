"""Bundle the patcher data, publish it to the shader repository, and publish the pack manifest.

Outputs:

  data-bundle.zip          patch.txt + files/ (the shader edits), the fallback carried in the mod jar
  data/packs.txt           the pack manifest as a fallback, so a fresh install works offline
  <repo>/packs.txt         the pack manifest the patcher prefers
  <repo>/patch/patch.txt   the patch manifest the mod prefers, naming the bundle below
  <repo>/patch/patch-<h>.zip  the patch bundle itself, content addressed

The pack manifest is line based and tab separated:

  MANIFEST 1
  VERSION <version>
  PACK <tab> name <tab> sha256 <tab> relative-url

The patch manifest uses the same shape plus a BASE line, so a build that patches another Eclipse
commit refuses the download instead of failing halfway through:

  MANIFEST 1
  VERSION <sha256> <file-count>
  BASE <eclipse-commit>
  PATCH <tab> name <tab> sha256 <tab> relative-url

Relative URLs resolve against the manifest's own location, so the same file works from the repo,
from a fork, or from a local folder. The bundle filename carries its hash because
raw.githubusercontent.com serves through a CDN that can keep an old blob at a fixed URL for
minutes; a new name is a guaranteed cache miss.

The jar copies (data-bundle.zip, data-bundle.version, data/packs.txt) are the offline fallback and
are built from the same bytes that get published, so the fallback and the repository cannot drift.

Run after build_edits.py, split-pack.ps1, or whenever packs/ changes:
    py bundle.py [--version 2026-11-16.1] [--repo ..\\..\\suspicious-speculars] [--url-base <url>]
"""
import argparse
import datetime
import hashlib
import os
import re
import shutil
import urllib.parse
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
PACKS = os.path.join(HERE, "..", "..", "..", "shader", "host", "packs")  # the host repo's packs/
OUT = os.path.join(HERE, "data-bundle.zip")
VERSION = os.path.join(HERE, "data-bundle.version")
DEFAULT_REPO = os.path.join(HERE, "..", "..", "..", "shader", "host")
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


def eclipse_commit():
    """The commit this patch was built against, read from the one place that pins it."""
    source = os.path.join(HERE, "..", "core", "suspiciousspeculars", "patch", "EclipseFetch.java")
    with open(source, encoding="utf-8") as fh:
        found = re.search(r'COMMIT\s*=\s*"([0-9a-f]+)"', fh.read())
    if not found:
        raise SystemExit("no Eclipse COMMIT constant in %s" % source)
    return found.group(1)


def publish_patch(repo, bundle, digest, files):
    """Publish the bundle and its manifest beside packs.txt, dropping the bundle it supersedes."""
    folder = os.path.join(repo, "patch")
    os.makedirs(folder, exist_ok=True)
    name = "patch-%s.zip" % digest[:12]
    shutil.copyfile(bundle, os.path.join(folder, name))

    body = "\n".join([
        "MANIFEST\t1",
        "REQUIRES\t2",  # patch format this data needs; ModData refuses anything newer
        "VERSION\t%s %d" % (digest, files),
        "BASE\t%s" % eclipse_commit(),
        "PATCH\t%s\t%s\t%s" % (name, digest, name),
    ]) + "\n"
    target = os.path.join(folder, "patch.txt")
    old_text = open(target, encoding="utf-8").read() if os.path.exists(target) else ""
    with open(target, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(body)
    # Only now drop the bundle the manifest no longer names: a failure above leaves the previous
    # manifest pointing at a file that still exists, instead of at one already deleted.
    for old in sorted(os.listdir(folder)):
        if old.startswith("patch-") and old.endswith(".zip") and old != name:
            os.remove(os.path.join(folder, old))
    print("patch:    %s  %s  %.2f MB" % (name, digest[:16], os.path.getsize(bundle) / 1e6))
    print("published: %s%s" % (folder, "" if old_text == body else " (changed)"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", default=datetime.date.today().strftime("%Y-%m-%d.1"))
    ap.add_argument("--repo", default=DEFAULT_REPO,
                    help="repository root that holds packs/ and patch/; manifests are written there")
    ap.add_argument("--packs", default=PACKS,
                    help="directory holding the shipping pack zips")
    ap.add_argument("--url-base", default=DEFAULT_URL_BASE,
                    help="base URL for the pack entries; absolute, so the bundled copy works too")
    args = ap.parse_args()

    if not os.path.isdir(DATA):
        raise SystemExit("no data folder: run build_edits.py first")
    if not os.path.isdir(args.packs):
        raise SystemExit("no packs folder at %s - pass --packs or run split-pack.ps1" % args.packs)

    lines = ["MANIFEST\t1", "VERSION\t" + args.version]
    total = 0
    for name in sorted(os.listdir(args.packs)):
        full = os.path.join(args.packs, name)
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

    if args.repo and os.path.isdir(args.repo):
        publish_patch(args.repo, OUT, digest, len(names))
    elif args.repo:
        print("published: patch skipped (%s does not exist; use --repo to point at the repository)"
              % args.repo)


if __name__ == "__main__":
    main()
