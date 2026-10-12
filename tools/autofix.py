#!/usr/bin/env python3
"""Rule-based build auto-fixer. Usage: autofix.py <failed-log>. Prints the ids of applied rules (comma separated)."""
import re
import sys
from pathlib import Path

MANIFEST = Path("app/src/main/AndroidManifest.xml")
GRADLE = Path("app/build.gradle.kts")
SETTINGS = Path("settings.gradle.kts")
GPROPS = Path("gradle.properties")
PROGUARD = Path("app/proguard-rules.pro")
RES = Path("app/src/main/res")
TOOLS_NS = 'xmlns:tools="http://schemas.android.com/tools"'

LOG = ""
APPLIED = []


def rd(p):
    return Path(p).read_text(encoding="utf-8")


def wr(p, t):
    Path(p).write_text(t, encoding="utf-8")


def applied(name):
    if name not in APPLIED:
        APPLIED.append(name)


def rel(path):
    m = re.search(r"/((?:app|src|core|lib)[\w-]*/.*)$", path)
    return Path(m.group(1)) if m else Path(path)


# ---------------------------------------------------------------- gradle helpers
def android_insert(snippet, marker):
    if not GRADLE.exists():
        return False
    t = rd(GRADLE)
    if marker in t:
        return False
    m = re.search(r"^android\s*\{", t, re.M)
    if not m:
        return False
    t = t[: m.end()] + "\n    " + snippet + "   // autofix" + t[m.end():]
    wr(GRADLE, t)
    return True


def set_gradle_int(key, value, only_raise=True):
    if not GRADLE.exists():
        return False
    t = rd(GRADLE)
    m = re.search(r"(\b" + key + r"\s*=\s*)(\d+)", t)
    if not m:
        return False
    cur = int(m.group(2))
    if (only_raise and value <= cur) or value == cur:
        return False
    wr(GRADLE, t[: m.start(2)] + str(value) + t[m.end(2):])
    return True


def append_config_exclude(line, marker):
    if not GRADLE.exists():
        return False
    t = rd(GRADLE)
    if marker in t:
        return False
    if "// autofix: configurations" not in t:
        t = t.rstrip() + "\n\n// autofix: configurations\nconfigurations.all {\n}\n"
    t = t.replace("configurations.all {\n", "configurations.all {\n    " + line + "   // " + marker + "\n", 1)
    wr(GRADLE, t)
    return True


# ---------------------------------------------------------------- rules: manifest
def rule_manifest_merge():
    attrs = set(re.findall(r"Attribute application@([\w.]+) value=", LOG))
    if not attrs or not MANIFEST.exists():
        return False
    t = rd(MANIFEST)
    o = t
    if TOOLS_NS not in t:
        t = t.replace("<manifest ", "<manifest " + TOOLS_NS + " ", 1)
    wanted = {"android:" + a for a in attrs}
    m = re.search(r'tools:replace="([^"]*)"', t)
    if m:
        cur = {x.strip() for x in m.group(1).split(",") if x.strip()}
        t = t.replace(m.group(0), 'tools:replace="' + ",".join(sorted(cur | wanted)) + '"', 1)
    else:
        t = re.sub(r"<application\b", '<application\n        tools:replace="' + ",".join(sorted(wanted)) + '"', t, count=1)
    if t != o:
        wr(MANIFEST, t)
        return True
    return False


def rule_exported():
    names = re.findall(r"<(?:activity|service|receiver|provider)#([\w.$]+)", LOG)
    if "android:exported" not in LOG or not names or not MANIFEST.exists():
        return False
    t = rd(MANIFEST)
    changed = False
    for n in set(names):
        short = n.split(".")[-1]
        pat = re.compile(r"<(activity|service|receiver|provider)\b((?:(?!>).)*?android:name=\"[^\"]*" + re.escape(short) + r"\"(?:(?!>).)*?)(/?)>(.*?)(?=<(?:activity|service|receiver|provider|/application)\b)", re.S)
        m = pat.search(t)
        if not m or "android:exported" in m.group(0).split(">")[0]:
            continue
        val = "true" if "<intent-filter" in m.group(4) else "false"
        head = "<" + m.group(1) + m.group(2) + ' android:exported="' + val + '"'
        t = t[: m.start()] + head + m.group(3) + ">" + m.group(4) + t[m.end():]
        changed = True
    if changed:
        wr(MANIFEST, t)
    return changed


def rule_min_sdk():
    m = re.search(r"minSdkVersion (\d+) cannot be smaller than version (\d+) declared in library", LOG)
    return bool(m) and set_gradle_int("minSdk", int(m.group(2)))


def rule_compile_sdk():
    vals = re.findall(r"compile against version (\d+) or later of the Android APIs", LOG)
    vals += re.findall(r"requires libraries and applications that depend on it to compile against version (\d+)", LOG)
    return bool(vals) and set_gradle_int("compileSdk", max(int(v) for v in vals))


# ---------------------------------------------------------------- rules: dependencies
LEGACY_GROUPS = ["com.android.support"]
STDLIB_DUPES = ["kotlin-stdlib-jdk7", "kotlin-stdlib-jdk8"]


def rule_duplicate_classes():
    if "Duplicate class" not in LOG:
        return False
    ch = False
    if "com.android.support" in LOG:
        ch |= append_config_exclude('exclude(group = "com.android.support")', "excl-support")
    for s in STDLIB_DUPES:
        if s in LOG:
            ch |= append_config_exclude('exclude(group = "org.jetbrains.kotlin", module = "' + s + '")', "excl-" + s)
    if "org.jetbrains:annotations-java5" in LOG:
        ch |= append_config_exclude('exclude(group = "org.jetbrains", module = "annotations-java5")', "excl-annotations5")
    if "com.google.guava:listenablefuture" in LOG:
        ch |= append_config_exclude('exclude(group = "com.google.guava", module = "listenablefuture")', "excl-listenable")
    return ch


def rule_jitpack():
    if not re.search(r"Could not (?:find|resolve) com\.github\.", LOG) or not SETTINGS.exists():
        return False
    t = rd(SETTINGS)
    if "jitpack.io" in t:
        return False
    wr(SETTINGS, re.sub(r"(dependencyResolutionManagement\s*\{\s*repositories\s*\{)", r'\1\n        maven { url = uri("https://jitpack.io") }', t, count=1))
    return rd(SETTINGS) != t


def rule_packaging():
    ch = False
    for p in set(re.findall(r"\d+ files found with path '([^']+)'", LOG)):
        ch |= android_insert('packaging { resources { excludes += "' + p + '" } }', 'excludes += "' + p + '"')
    for p in set(re.findall(r"More than one file was found with OS independent path '([^']+)'", LOG)):
        ch |= android_insert('packaging { jniLibs { pickFirsts += "' + p + '" } }', 'pickFirsts += "' + p + '"')
    return ch


# ---------------------------------------------------------------- rules: gradle / env
def rule_lint():
    if not re.search(r"Lint found (?:errors|fatal errors)|lintVital", LOG):
        return False
    return android_insert("lint { abortOnError = false; checkReleaseBuilds = false }", "abortOnError")


def rule_oom():
    if not re.search(r"java\.lang\.OutOfMemoryError|Java heap space|Metaspace|GC overhead limit", LOG) or not GPROPS.exists():
        return False
    t = rd(GPROPS)
    m = re.search(r"org\.gradle\.jvmargs=(.*)", t)
    if m and re.search(r"-Xmx4g", m.group(1)):
        return False
    new = "org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
    t = t.replace(m.group(0), new) if m else t.rstrip() + "\n" + new + "\n"
    wr(GPROPS, t)
    return True


def rule_build_config():
    if not re.search(r"Unresolved reference '?BuildConfig'?", LOG):
        return False
    t = rd(GRADLE)
    if re.search(r"buildConfig\s*=\s*true", t):
        return False
    return android_insert("buildFeatures { buildConfig = true }", "buildConfig = true")


# ---------------------------------------------------------------- rules: r8
def rule_r8_missing():
    cls = set(re.findall(r"Missing class ([\w.$]+)", LOG))
    if not cls or not PROGUARD.exists():
        return False
    t = rd(PROGUARD)
    add = [c for c in sorted(cls) if "-dontwarn " + c not in t]
    if not add:
        return False
    wr(PROGUARD, t.rstrip() + "\n\n# autofix: R8 missing classes\n" + "\n".join("-dontwarn " + c for c in add) + "\n")
    return True


# ---------------------------------------------------------------- rules: resources
RES_DEFAULT = {"string": ("values/strings.xml", "string", "{n}"), "color": ("values/colors.xml", "color", "#000000"),
               "dimen": ("values/dimens.xml", "dimen", "0dp"), "bool": ("values/bools.xml", "bool", "false"),
               "integer": ("values/integers.xml", "integer", "0")}


def rule_missing_resources():
    ch = False
    for kind, name in set(re.findall(r"resource (string|color|dimen|bool|integer)/([\w.]+) \(aka [^)]*\) not found", LOG)):
        rel_path, tag, val = RES_DEFAULT[kind]
        f = RES / rel_path
        f.parent.mkdir(parents=True, exist_ok=True)
        t = rd(f) if f.exists() else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
        if 'name="' + name + '"' in t:
            continue
        line = '    <' + tag + ' name="' + name + '">' + val.replace("{n}", name) + '</' + tag + '>\n'
        wr(f, t.replace("</resources>", line + "</resources>"))
        ch = True
    return ch


# ---------------------------------------------------------------- rules: kotlin
IMPORTS = {
    "Modifier": "androidx.compose.ui.Modifier", "Color": "androidx.compose.ui.graphics.Color",
    "dp": "androidx.compose.ui.unit.dp", "sp": "androidx.compose.ui.unit.sp", "Dp": "androidx.compose.ui.unit.Dp",
    "Alignment": "androidx.compose.ui.Alignment", "Composable": "androidx.compose.runtime.Composable",
    "remember": "androidx.compose.runtime.remember", "mutableStateOf": "androidx.compose.runtime.mutableStateOf",
    "getValue": "androidx.compose.runtime.getValue", "setValue": "androidx.compose.runtime.setValue",
    "LaunchedEffect": "androidx.compose.runtime.LaunchedEffect", "DisposableEffect": "androidx.compose.runtime.DisposableEffect",
    "rememberCoroutineScope": "androidx.compose.runtime.rememberCoroutineScope", "derivedStateOf": "androidx.compose.runtime.derivedStateOf",
    "mutableIntStateOf": "androidx.compose.runtime.mutableIntStateOf", "collectAsState": "androidx.compose.runtime.collectAsState",
    "State": "androidx.compose.runtime.State", "MutableState": "androidx.compose.runtime.MutableState",
    "Text": "androidx.compose.material3.Text", "Icon": "androidx.compose.material3.Icon", "Button": "androidx.compose.material3.Button",
    "IconButton": "androidx.compose.material3.IconButton", "Surface": "androidx.compose.material3.Surface",
    "Scaffold": "androidx.compose.material3.Scaffold", "MaterialTheme": "androidx.compose.material3.MaterialTheme",
    "Card": "androidx.compose.material3.Card", "TextField": "androidx.compose.material3.TextField",
    "OutlinedTextField": "androidx.compose.material3.OutlinedTextField", "Switch": "androidx.compose.material3.Switch",
    "Checkbox": "androidx.compose.material3.Checkbox", "Divider": "androidx.compose.material3.HorizontalDivider",
    "HorizontalDivider": "androidx.compose.material3.HorizontalDivider", "AlertDialog": "androidx.compose.material3.AlertDialog",
    "TextButton": "androidx.compose.material3.TextButton", "CircularProgressIndicator": "androidx.compose.material3.CircularProgressIndicator",
    "LinearProgressIndicator": "androidx.compose.material3.LinearProgressIndicator",
    "ExperimentalMaterial3Api": "androidx.compose.material3.ExperimentalMaterial3Api",
    "Box": "androidx.compose.foundation.layout.Box", "Column": "androidx.compose.foundation.layout.Column",
    "Row": "androidx.compose.foundation.layout.Row", "Spacer": "androidx.compose.foundation.layout.Spacer",
    "Arrangement": "androidx.compose.foundation.layout.Arrangement", "PaddingValues": "androidx.compose.foundation.layout.PaddingValues",
    "padding": "androidx.compose.foundation.layout.padding", "fillMaxSize": "androidx.compose.foundation.layout.fillMaxSize",
    "fillMaxWidth": "androidx.compose.foundation.layout.fillMaxWidth", "size": "androidx.compose.foundation.layout.size",
    "height": "androidx.compose.foundation.layout.height", "width": "androidx.compose.foundation.layout.width",
    "clickable": "androidx.compose.foundation.clickable", "background": "androidx.compose.foundation.background",
    "RoundedCornerShape": "androidx.compose.foundation.shape.RoundedCornerShape", "CircleShape": "androidx.compose.foundation.shape.CircleShape",
    "LazyColumn": "androidx.compose.foundation.lazy.LazyColumn", "LazyRow": "androidx.compose.foundation.lazy.LazyRow",
    "items": "androidx.compose.foundation.lazy.items", "AnimatedVisibility": "androidx.compose.animation.AnimatedVisibility",
    "animateFloatAsState": "androidx.compose.animation.core.animateFloatAsState", "tween": "androidx.compose.animation.core.tween",
    "Uri": "android.net.Uri", "Intent": "android.content.Intent", "Context": "android.content.Context",
    "ContentValues": "android.content.ContentValues", "Bundle": "android.os.Bundle", "Handler": "android.os.Handler",
    "Looper": "android.os.Looper", "Build": "android.os.Build", "Environment": "android.os.Environment",
    "Log": "android.util.Log", "Toast": "android.widget.Toast", "WebView": "android.webkit.WebView",
    "MediaStore": "android.provider.MediaStore", "Settings": "android.provider.Settings", "Color_": "android.graphics.Color",
    "Bitmap": "android.graphics.Bitmap", "BitmapFactory": "android.graphics.BitmapFactory",
    "File": "java.io.File", "IOException": "java.io.IOException", "InputStream": "java.io.InputStream",
    "OutputStream": "java.io.OutputStream", "URL": "java.net.URL", "HttpURLConnection": "java.net.HttpURLConnection",
    "URLEncoder": "java.net.URLEncoder", "JSONObject": "org.json.JSONObject", "JSONArray": "org.json.JSONArray",
    "Executors": "java.util.concurrent.Executors", "TimeUnit": "java.util.concurrent.TimeUnit",
    "AtomicInteger": "java.util.concurrent.atomic.AtomicInteger", "AtomicLong": "java.util.concurrent.atomic.AtomicLong",
    "ConcurrentHashMap": "java.util.concurrent.ConcurrentHashMap", "Locale": "java.util.Locale",
    "Dispatchers": "kotlinx.coroutines.Dispatchers", "withContext": "kotlinx.coroutines.withContext",
    "launch": "kotlinx.coroutines.launch", "delay": "kotlinx.coroutines.delay", "CoroutineScope": "kotlinx.coroutines.CoroutineScope",
    "Job": "kotlinx.coroutines.Job", "toRequestBody": "okhttp3.RequestBody.Companion.toRequestBody",
    "toMediaType": "okhttp3.MediaType.Companion.toMediaType", "OkHttpClient": "okhttp3.OkHttpClient",
    "Request": "okhttp3.Request",
}
KT_ERR = re.compile(r"^e: (?:file://)?(\S+?\.kt):(\d+):(\d+)\s+Unresolved reference[: ]+'?(\w+)'?", re.M)
QUALIFIED = re.compile(r"(?:[a-z_]\w*\.){2,}([A-Z]\w*)")


def namespace():
    m = re.search(r'namespace\s*=\s*"([^"]+)"', rd(GRADLE)) if GRADLE.exists() else None
    return m.group(1) if m else None


def add_import(path, fq):
    t = rd(path)
    if re.search(r"^import " + re.escape(fq) + r"\s*$", t, re.M):
        return False
    lines = t.split("\n")
    last = max([i for i, l in enumerate(lines) if l.startswith("import ")] or [-1])
    if last < 0:
        pk = [i for i, l in enumerate(lines) if l.startswith("package ")]
        last = pk[0] if pk else -1
        lines.insert(last + 1, "")
        last += 1
    lines.insert(last + 1, "import " + fq)
    wr(path, "\n".join(lines))
    return True


def rule_kotlin_unresolved():
    ch = False
    for path, line, col, name in KT_ERR.findall(LOG):
        f = rel(path)
        if not f.exists():
            continue
        lines = rd(f).split("\n")
        i, c = int(line) - 1, int(col) - 1
        if i >= len(lines):
            continue
        done = False
        for q in QUALIFIED.finditer(lines[i]):
            if q.start() <= c <= q.end() and name in q.group(0).split("."):
                lines[i] = lines[i][: q.start()] + q.group(1) + lines[i][q.end():]
                wr(f, "\n".join(lines))
                applied("kotlin-qualified-name")
                ch = done = True
                break
        if done:
            continue
        if name == "R" and namespace():
            if add_import(f, namespace() + ".R"):
                applied("kotlin-import-R")
                ch = True
            continue
        if name == "BuildConfig" and namespace():
            if add_import(f, namespace() + ".BuildConfig"):
                applied("kotlin-import-BuildConfig")
                ch = True
            continue
        if name in IMPORTS and add_import(f, IMPORTS[name]):
            applied("kotlin-import")
            ch = True
    return False if not ch else True


# ---------------------------------------------------------------- driver
RULES = [
    ("manifest-merge", rule_manifest_merge), ("manifest-exported", rule_exported),
    ("min-sdk", rule_min_sdk), ("compile-sdk", rule_compile_sdk),
    ("duplicate-classes", rule_duplicate_classes), ("jitpack", rule_jitpack), ("packaging", rule_packaging),
    ("lint", rule_lint), ("gradle-memory", rule_oom), ("build-config", rule_build_config),
    ("r8-missing", rule_r8_missing), ("missing-resources", rule_missing_resources),
    ("kotlin-unresolved", rule_kotlin_unresolved),
]


def main():
    global LOG
    LOG = Path(sys.argv[1]).read_text(encoding="utf-8", errors="ignore")
    for name, fn in RULES:
        try:
            if fn():
                applied(name)
        except Exception as e:
            print("rule " + name + " failed: " + str(e), file=sys.stderr)
    print(",".join(APPLIED))


main()
