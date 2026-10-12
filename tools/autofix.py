import re
import sys
from pathlib import Path

MANIFEST = Path("app/src/main/AndroidManifest.xml")
GRADLE = Path("app/build.gradle.kts")
TOOLS_NS = 'xmlns:tools="http://schemas.android.com/tools"'


def fix_manifest(log):
    attrs = set(re.findall(r"Attribute application@([\w.]+) value=", log))
    attrs |= set(re.findall(r"tools:replace=\"android:([\w.]+)\"", log))
    if not attrs or not MANIFEST.exists():
        return False
    text = MANIFEST.read_text(encoding="utf-8")
    orig = text
    if TOOLS_NS not in text:
        text = text.replace("<manifest ", "<manifest " + TOOLS_NS + " ", 1)
    m = re.search(r'tools:replace="([^"]*)"', text)
    wanted = {"android:" + a for a in attrs}
    if m:
        cur = set(x.strip() for x in m.group(1).split(",") if x.strip())
        merged = ",".join(sorted(cur | wanted))
        text = text.replace(m.group(0), 'tools:replace="' + merged + '"', 1)
    else:
        text = re.sub(r"<application\b", '<application\n        tools:replace="' + ",".join(sorted(wanted)) + '"', text, count=1)
    if text != orig:
        MANIFEST.write_text(text, encoding="utf-8")
        return True
    return False


def fix_duplicates(log):
    if "Duplicate class android.support." not in log or not GRADLE.exists():
        return False
    text = GRADLE.read_text(encoding="utf-8")
    marker = "// autofix: exclude legacy support libs"
    if marker in text:
        return False
    block = (
        "\n" + marker + "\n"
        "configurations.all {\n"
        '    exclude(group = "com.android.support")\n'
        "}\n"
    )
    GRADLE.write_text(text.rstrip() + "\n" + block, encoding="utf-8")
    return True


KT_ERR = re.compile(r"^e: file://(\S+?\.kt):(\d+):(\d+) Unresolved reference '(\w+)'", re.M)
QUALIFIED = re.compile(r"(?:[a-z_]\w*\.){2,}([A-Z]\w*)")


def fix_kotlin_qualified(log):
    """Unresolved package segment inside a fully qualified name: use the simple class name instead."""
    changed = False
    for path, line, col, name in KT_ERR.findall(log):
        m = re.search(r"(/app/src/.*)$", path)
        f = Path(m.group(1).lstrip("/")) if m else Path(path)
        if not f.exists():
            continue
        lines = f.read_text(encoding="utf-8").split("\n")
        i = int(line) - 1
        if i >= len(lines):
            continue
        c = int(col) - 1
        for q in QUALIFIED.finditer(lines[i]):
            if q.start() <= c <= q.end() and name in q.group(0).split("."):
                lines[i] = lines[i][: q.start()] + q.group(1) + lines[i][q.end():]
                changed = True
                break
        if changed:
            f.write_text("\n".join(lines), encoding="utf-8")
    return changed


def main():
    log = Path(sys.argv[1]).read_text(encoding="utf-8", errors="ignore")
    fixed = []
    if fix_manifest(log):
        fixed.append("manifest-merge")
    if fix_duplicates(log):
        fixed.append("duplicate-classes")
    if fix_kotlin_qualified(log):
        fixed.append("kotlin-qualified-name")
    print(",".join(fixed))


main()
