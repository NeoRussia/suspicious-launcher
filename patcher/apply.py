"""Apply edits.json to a stock Eclipse e89e6c6 zip and write a patched shaderpack.

  py apply.py --base <stock>.zip --out <patched>.zip
  py apply.py --base <stock>.zip --out <patched>.zip --verify <our-build>.zip

The applier refuses a base whose edited files do not hash to the stock content it was
built from, so it can never produce a half-patched pack on a different Eclipse version.
"""
import argparse
import hashlib
import json
import os
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")


def norm(raw):
    return raw.decode("utf-8", "replace").replace("\r\n", "\n")


def read_zip(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
        prefix = names[0].split("/")[0] + "/" if names and "/" in names[0] else ""
        for n in names:
            rel = n[len(prefix):] if n.startswith(prefix) else n
            out[rel] = z.read(n)
    return out


def apply_ops(lines, ops, where):
    for op in reversed(ops):                      # reverse keeps earlier indices valid
        at = op["line"]
        lines[at:at + op["delete"]] = op["insert"]
    return lines


def derive_files(files, rules, missing):
    made = 0
    for r in rules:
        for rel in r["only"]:
            src = r["source_prefix"] + rel
            if src not in files:
                missing.append(src)
                continue
            text = norm(files[src])
            block = "\n\n".join(r["with"])
            if r["line"] in text:
                # keep the define where the overworld stub had it
                text = text.replace(r["line"], block, 1)
            else:
                # no overworld define in this stub: put the dimension defines before the include
                at = text.find("#include")
                if at >= 0:
                    text = text[:at] + block + "\n\n" + text[at:]
            if text and not text.endswith("\n"):
                text += "\n"
            files[r["target_prefix"] + rel] = text.encode("utf-8")
            made += 1
    return made


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--verify", default=None)
    ap.add_argument("--data", default=DATA)
    args = ap.parse_args()

    doc = json.load(open(os.path.join(args.data, "edits.json"), encoding="utf-8"))
    files = read_zip(args.base)

    detect = doc["base"]["detect"]
    probe = files.get(detect["file"])
    if probe is None or detect["contains"].encode() not in probe:
        raise SystemExit("not a %s pack (no %s containing %r)"
                         % (doc["base"]["name"], detect["file"], detect["contains"]))

    failed = []
    for e in doc["edits"]:
        raw = files.get(e["file"])
        if raw is None:
            failed.append((e["file"], "missing"))
            continue
        lines = norm(raw).split("\n")
        digest = hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()
        if digest != e["sha256"]:
            failed.append((e["file"], "base hash differs"))
            continue
        files[e["file"]] = "\n".join(apply_ops(lines, e["ops"], e["file"])).encode("utf-8")

    if failed:
        for name, why in failed:
            print("refused:", name, "-", why)
        raise SystemExit("base does not match %s; nothing written" % doc["base"]["name"])

    for r in doc["replace_files"]:
        files[r["file"]] = open(os.path.join(args.data, r["source"]), "rb").read()
    for r in doc.get("add_files", []):
        files[r["file"]] = open(os.path.join(args.data, r["source"]), "rb").read()

    missing = []
    derived = len(doc.get("add_files", []))
    if missing:
        print("warning: %d derive sources missing (skipped)" % len(missing))

    with zipfile.ZipFile(args.out, "w", zipfile.ZIP_DEFLATED) as z:
        for name in sorted(files):
            z.writestr(name, files[name])
    print("patched: %d edited, %d replaced, %d derived -> %s"
          % (len(doc["edits"]), len(doc["replace_files"]), derived, args.out))

    if args.verify:
        ref = read_zip(args.verify)
        diffs = []
        ignore = ("README.md",)
        for name in sorted(set(files) | set(ref)):
            if name in ignore or name.startswith(".github/"):
                continue          # the release archive ships these; the built pack drops them
            if name not in files or name not in ref:
                diffs.append((name, "one side only"))
            elif norm(files[name]) != norm(ref[name]):
                diffs.append((name, "content"))
        print("verify: %d differing files" % len(diffs))
        for name, why in diffs[:20]:
            print("   ", name, why)
        if diffs:
            sys.exit(1)


if __name__ == "__main__":
    main()
