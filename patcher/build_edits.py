"""Build the declarative patch that turns a stock Eclipse e89e6c6 into ours.

The patch must not carry Eclipse's code. Their pack is not redistributed here, so nothing built
from stock is shipped verbatim: a stock file we modify is stored as line edits (only our lines are
written into patch.txt), and a stock file we need under another name - the per-dimension shadow
shaders are the case - is stored as COPY plus line edits, with the applier taking the bytes from the
player's own Eclipse archive. What ships is our own additions and our own changed lines, never a copy
of their file.

Each edit or copy carries the SHA-256 of its stock content, so the applier proves it is looking at
exactly the build the patch was made for and refuses anything else. Files whose bytes differ from
stock only in line endings produce no diff and are omitted entirely.

Run, with the shader workspace's two archives:

    py build_edits.py --base <stock eclipse-e89e6c6.zip> --ours <our repacked zip>

The defaults come from the SUSY_ECLIPSE_BASE / SUSY_ECLIPSE_OURS environment variables; both refer
to the private shader workspace, which is why the generated `data\` is committed to the installer.
"""
import argparse
import difflib
import hashlib
import json
import os
import shutil
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
BASE_ZIP = os.environ.get("SUSY_ECLIPSE_BASE", "")
OUR_ZIP = os.environ.get("SUSY_ECLIPSE_OURS", "")
OUT_DIR = os.path.join(HERE, "data")
FILES_DIR = os.path.join(OUT_DIR, "files")

# A file we add that is this close to a stock file is treated as that file plus our changes.
COPY_MIN_RATIO = 0.90
COPY_MIN_LINES = 10


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
    return (name.endswith((".fsh", ".vsh", ".gsh", ".tcs", ".tes", ".csh", ".glsl",
                            ".properties", ".json", ".lang", ".txt", ".md", ".mcmeta",
                            ".bat", ".cmd", ".sh", ".toml", ".cfg"))
            or name.endswith(".gitignore"))


def ops_between(a, b):
    """Line ops that turn a into b; only the inserted (our) lines are stored."""
    ops = []
    sm = difflib.SequenceMatcher(None, a, b, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            continue
        ops.append({"line": i1, "delete": i2 - i1, "insert": b[j1:j2]})
    return ops


def sha_lines(lines):
    return hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()


def closest(name, lines, base_lines):
    """The stock file this addition is a near copy of, or None."""
    best = None
    slack = max(6, len(lines) // 8)
    for stock, stock_lines in base_lines.items():
        if abs(len(stock_lines) - len(lines)) > slack:
            continue
        ratio = difflib.SequenceMatcher(None, stock_lines, lines, autojunk=False).ratio()
        if ratio >= COPY_MIN_RATIO and (best is None or ratio > best[1]):
            best = (stock, ratio, stock_lines)
    return best


def build():
    if not BASE_ZIP or not OUR_ZIP:
        raise SystemExit(
            "give the two archives: py build_edits.py --base <stock.zip> --ours <ours.zip>\n"
            "or set SUSY_ECLIPSE_BASE and SUSY_ECLIPSE_OURS")
    base = read_zip(BASE_ZIP)
    ours = read_zip(OUR_ZIP)
    edits, copy_files, add_files = [], [], []

    base_lines = {n: norm(raw).split("\n") for n, raw in base.items() if is_text(n)}

    for name in sorted(ours):
        if name not in base:
            if is_text(name):
                b = norm(ours[name]).split("\n")
                if len(b) >= COPY_MIN_LINES:
                    hit = closest(name, b, base_lines)
                    if hit is not None:
                        stock, _ratio, a = hit
                        copy_files.append({
                            "file": name,
                            "from": stock,
                            "sha256": sha_lines(a),
                            "ops": ops_between(a, b),
                        })
                        continue
            add_files.append({"file": name, "source": None})
            continue
        if not is_text(name):
            if base[name] != ours[name]:
                # Binary stock content is never shipped; that would redistribute their art.
                raise SystemExit("binary file differs from stock and cannot be expressed as edits: %s"
                                 % name)
            continue
        a = norm(base[name]).split("\n")
        b = norm(ours[name]).split("\n")
        if a == b:
            continue
        edits.append({"file": name, "sha256": sha_lines(a), "ops": ops_between(a, b)})

    # Start clean: a file that is stock verbatim this run must not survive from an older one.
    shutil.rmtree(FILES_DIR, ignore_errors=True)
    os.makedirs(FILES_DIR, exist_ok=True)
    for entry in add_files:
        safe = entry["file"].replace("/", "__")
        entry["source"] = "files/" + safe
        with open(os.path.join(OUT_DIR, entry["source"]), "wb") as fh:
            fh.write(ours[entry["file"]])

    doc = {
        "schema": 4,
        "base": {
            "name": "Eclipse e89e6c6",
            "detect": {"file": "shaders/lang/en_us.lang", "contains": "ECLIPSE"},
        },
        "edits": edits,
        "copy_files": copy_files,
        "add_files": add_files,
    }
    with open(os.path.join(OUT_DIR, "edits.json"), "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False)

    # Java-friendly mirror of the same data: line based so the pre-launch tool needs no JSON
    # parser. Format:
    #   PATCH 2
    #   DETECT <file> <marker>
    #   EDIT <path> <sha256> <ops>
    #   COPY <dest> <stock path> <sha256> <ops>     (dest built from the stock file, then the ops)
    #   OP <line> <delete> <insertlines> \n +<line> ...
    #   ADD <path> <source file>
    def write_ops(fh, ops):
        for op in ops:
            fh.write("OP %d %d %d\n" % (op["line"], op["delete"], len(op["insert"])))
            for line in op["insert"]:
                fh.write("+" + line + "\n")

    with open(os.path.join(OUT_DIR, "patch.txt"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write("PATCH 2\n")
        fh.write("DETECT %s %s\n" % (doc["base"]["detect"]["file"], doc["base"]["detect"]["contains"]))
        for e in doc["edits"]:
            fh.write("EDIT %s %s %d\n" % (e["file"], e["sha256"], len(e["ops"])))
            write_ops(fh, e["ops"])
        for e in doc["copy_files"]:
            fh.write("COPY %s %s %s %d\n"
                     % (e["file"], e["from"], e["sha256"], len(e["ops"])))
            write_ops(fh, e["ops"])
        for r in doc["add_files"]:
            fh.write("ADD %s %s\n" % (r["file"], r["source"]))

    print("files edited:", len(edits), "line ops:", sum(len(e["ops"]) for e in edits))
    print("copied from stock:", len(copy_files),
          "ops:", sum(len(e["ops"]) for e in copy_files))
    print("added:", len(add_files))


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
