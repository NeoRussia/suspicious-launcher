"""Build the declarative patch that turns a stock Eclipse e89e6c6 into ours.

Each edited file carries the SHA-256 of its stock content, so the applier can prove it is
looking at exactly the build the patch was made for and refuse anything else. Whole files
that are our own data are shipped verbatim; the four per-dimension folders are small stubs
we generate, so they are shipped as added files rather than derived at apply time.

Run, with the shader workspace's two archives:

    py build_edits.py --base <stock eclipse-e89e6c6.zip> --ours <our repacked zip>

The defaults come from the SUSY_ECLIPSE_BASE / SUSY_ECLIPSE_OURS environment variables; both refer
to the private shader workspace, which is why the generated `data\` is committed here.
"""
import argparse
import difflib
import hashlib
import json
import os
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
BASE_ZIP = os.environ.get("SUSY_ECLIPSE_BASE", "")
OUR_ZIP = os.environ.get("SUSY_ECLIPSE_OURS", "")
OUT_DIR = os.path.join(HERE, "data")
FILES_DIR = os.path.join(OUT_DIR, "files")

FORCE_WHOLE = {
    "shaders/block.properties",
    "shaders/dimension.properties",
    "shaders/colorwheel.properties",
    "shaders/entity.properties",
    "shaders/item.properties",
    "shaders/ph_lights.json",
}


def read_zip(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if not n.endswith("/")]
        prefix = names[0].split("/")[0] + "/" if names and "/" in names[0] else ""
        for n in names:
            rel = n[len(prefix):] if n.startswith(prefix) else n
            out[rel] = z.read(n)
    return out


def norm(raw):
    return raw.decode("utf-8", "replace").replace("\r\n", "\n")


def is_text(name):
    return name.endswith((".fsh", ".vsh", ".gsh", ".tcs", ".tes", ".csh", ".glsl",
                           ".properties", ".json", ".lang", ".txt", ".md"))


def build():
    if not BASE_ZIP or not OUR_ZIP:
        raise SystemExit(
            "give the two archives: py build_edits.py --base <stock.zip> --ours <ours.zip>\n"
            "or set SUSY_ECLIPSE_BASE and SUSY_ECLIPSE_OURS")
    base = read_zip(BASE_ZIP)
    ours = read_zip(OUR_ZIP)
    edits, replace_files, add_files = [], [], []

    for name in sorted(ours):
        if name not in base:
            add_files.append({"file": name, "source": None})
            continue
        if not is_text(name):
            if base[name] != ours[name]:
                replace_files.append({"file": name, "source": None})
            continue
        a = norm(base[name]).split("\n")
        b = norm(ours[name]).split("\n")
        if a == b:
            continue
        if name in FORCE_WHOLE:
            replace_files.append({"file": name, "source": None})
            continue
        ops = []
        sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag == "equal":
                continue
            ops.append({"line": i1, "delete": i2 - i1, "insert": b[j1:j2]})
        edits.append({
            "file": name,
            "sha256": hashlib.sha256("\n".join(a).encode("utf-8")).hexdigest(),
            "ops": ops,
        })

    os.makedirs(FILES_DIR, exist_ok=True)
    for entry in replace_files + add_files:
        safe = entry["file"].replace("/", "__")
        entry["source"] = "files/" + safe
        with open(os.path.join(OUT_DIR, entry["source"]), "wb") as fh:
            fh.write(ours[entry["file"]])

    doc = {
        "schema": 3,
        "base": {
            "name": "Eclipse e89e6c6",
            "detect": {"file": "shaders/lang/en_us.lang", "contains": "ECLIPSE"},
        },
        "edits": edits,
        "replace_files": replace_files,
        "add_files": add_files,
    }
    with open(os.path.join(OUT_DIR, "edits.json"), "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False)

    # Java-friendly mirror of the same data: line based so the pre-launch tool needs no JSON
    # parser. Format:
    #   PATCH 1
    #   DETECT <file> <marker>
    #   EDIT <path> <sha256> <ops>
    #   OP <line> <delete> <insertlines> \n +<line> ...
    #   WHOLE <path> <source file>   /  ADD <path> <source file>
    with open(os.path.join(OUT_DIR, "patch.txt"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("PATCH 1\n")
        fh.write("DETECT %s %s\n" % (doc["base"]["detect"]["file"], doc["base"]["detect"]["contains"]))
        for e in doc["edits"]:
            fh.write("EDIT %s %s %d\n" % (e["file"], e["sha256"], len(e["ops"])))
            for op in e["ops"]:
                fh.write("OP %d %d %d\n" % (op["line"], op["delete"], len(op["insert"])))
                for line in op["insert"]:
                    fh.write("+" + line + "\n")
        for r in doc["replace_files"]:
            fh.write("WHOLE %s %s\n" % (r["file"], r["source"]))
        for r in doc["add_files"]:
            fh.write("ADD %s %s\n" % (r["file"], r["source"]))

    print("files edited:", len(edits), "line ops:", sum(len(e["ops"]) for e in edits))
    print("replaced:", len(replace_files), " added:", len(add_files))


if __name__ == "__main__":
    args = argparse.ArgumentParser(description="Build the installer's patch data from two zips.")
    args.add_argument("--base", help="stock Eclipse e89e6c6 archive")
    args.add_argument("--ours", help="our repacked shader archive")
    parsed = args.parse_args()
    if parsed.base:
        BASE_ZIP = parsed.base
    if parsed.ours:
        OUR_ZIP = parsed.ours
    build()
