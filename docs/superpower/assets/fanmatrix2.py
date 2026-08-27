"""Fan-in / fan-out matrix over a checked-out tree (fast: one read per file, not one git call).

  fan-in  = OTHER .java files referencing the simple name as a word, after stripping comments and
            string literals (so a javadoc {@link X} does NOT count as coupling).
  fanOut  "plan"     = distinct `com.gamma.*` types from imports + inline FQNs -- the plan's own
                       stated metric, so the numbers are comparable to its table.
  fanOut  "+samePkg" = the above PLUS same-package types used by simple name (no import needed),
                       resolved against the real file list of the class's own package.
"""
import os
import re
import sys

ROOT = sys.argv[1]
CLASSES = sys.argv[2].split(",")


def strip(src):
    src = re.sub(r'"""(?:.|\n)*?"""', '""', src)      # text blocks FIRST
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r"/\*(?:.|\n)*?\*/", "", src)
    src = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', src)
    return src


raw, code, main_files = {}, {}, []
for dirpath, _, names in os.walk(ROOT):
    d = (dirpath + os.sep).replace("\\", "/")
    # ⚠ .claude/worktrees holds a FULL second checkout pinned to an old commit -- including it
    # doubles every tree-wide count and silently mixes stale sources in.
    if "/target/" in d or "/.claude/" in d or "/.git/" in d:
        continue
    for n in names:
        if not n.endswith(".java"):
            continue
        p = os.path.join(dirpath, n).replace("\\", "/")
        if "/src/" not in p:
            continue
        try:
            t = open(p, encoding="utf-8", errors="replace").read()
        except OSError:
            continue
        raw[p], code[p] = t, strip(t)
        if "/src/main/" in p:
            main_files.append(p)

by_name = {}
for p in main_files:
    by_name.setdefault(p.rsplit("/", 1)[-1][:-5], p)

print("%-22s %7s %7s %9s %9s %8s" % ("class", "fanIn", "fanIn", "fanOut", "fanOut", "com.gamma"))
print("%-22s %7s %7s %9s %9s %8s" % ("", "main", "all", "plan", "+samePkg", "imports"))
print("-" * 70)
for cls in CLASSES:
    path = by_name.get(cls)
    if not path:
        print("%-22s   (absent at this ref)" % cls)
        continue
    src, r = code[path], raw[path]

    imports = re.findall(r"^import\s+(?:static\s+)?(com\.gamma\.[\w.]+)", r, re.M)

    def head(fq):
        segs = fq.split(".")
        for i, s in enumerate(segs):
            if s[:1].isupper():
                return ".".join(segs[: i + 1])
        return None

    imported = {h for h in map(head, imports) if h}
    inline = {h for h in map(head, re.findall(r"\bcom\.gamma\.[\w.]+", src)) if h}

    pkg_dir = path.rsplit("/", 1)[0]
    pkg = pkg_dir.split("/java/")[-1].replace("/", ".")
    same_pkg = set()
    for p in main_files:
        if p.rsplit("/", 1)[0] == pkg_dir:
            n = p.rsplit("/", 1)[-1][:-5]
            if n != cls and re.search(r"(?<![\w.])" + re.escape(n) + r"(?![\w])", src):
                same_pkg.add(pkg + "." + n)

    self_fq = {pkg + "." + cls}
    fo_plan = (imported | inline) - self_fq
    fo_ext = (imported | inline | same_pkg) - self_fq

    pat = re.compile(r"(?<![\w.])" + re.escape(cls) + r"(?![\w])")
    fi_main = fi_all = 0
    for p, c in code.items():
        if p == path:
            continue
        if pat.search(c):
            fi_all += 1
            if "/src/main/" in p:
                fi_main += 1
    print("%-22s %7d %7d %9d %9d %8d"
          % (cls, fi_main, fi_all, len(fo_plan), len(fo_ext), len(imports)))
