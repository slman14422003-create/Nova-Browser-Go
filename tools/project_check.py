#!/usr/bin/env python3
"""فحص سريع للمشروع قبل البناء: توازن الأقواس، وجود المكونات في الـ Manifest، واستخدام Prefs.
الاستخدام:  python3 tools/project_check.py
"""
import re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/nova/browser"
errors = []
warnings = []

_TOKEN = re.compile("|".join([
    r'"""[\s\S]*?"""',               # نص خام
    r'"(?:\\.|[^"\\\n])*"',            # نص عادي
    r"/\*[\s\S]*?\*/",                 # تعليق كتلي
    r"//[^\n]*",                       # تعليق سطري
    r"'(?:\\.|[^'\\\n])'",             # محرف
]))

def strip(code: str) -> str:
    """يزيل النصوص والتعليقات بمرور واحد (حتى لا يُفهم // داخل رابط كتعليق)."""
    def repl(m):
        t = m.group(0)
        if t.startswith("//") or t.startswith("/*"): return ""
        return "''" if t.startswith("'") else '""'
    return _TOKEN.sub(repl, code)

# 1) توازن الأقواس
for f in sorted(SRC.rglob("*.*")):
    if f.suffix not in (".kt", ".java"):
        continue
    c = strip(f.read_text(encoding="utf-8"))
    for a, b in ("{}", "()", "[]"):
        if c.count(a) != c.count(b):
            warnings.append(f"{f.name}: تحذير (قد يكون إيجابياً كاذباً) عدم توازن {a}{b} ({c.count(a)} مقابل {c.count(b)})")

# 2) مكونات الـ Manifest موجودة
mf = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for name in re.findall(r'android:name="\.(\w+)"', mf):
    if not any(SRC.rglob(f"{name}.kt")) and not any(SRC.rglob(f"{name}.java")):
        errors.append(f"Manifest يشير إلى .{name} ولا يوجد ملف له")

# 2.5) تدقيق أمني للـ Manifest
if 'android:allowBackup="false"' not in mf: warnings.append("أمان: allowBackup غير معطّل (قد تُسرَّب بيانات عبر النسخ الاحتياطي)")
if 'android:debuggable="true"' in mf: errors.append("أمان: debuggable=true في Manifest")
if "EnableSafeBrowsing" not in mf: warnings.append("أمان: لم يُفعَّل Safe Browsing في Manifest")
for m in re.finditer(r"<(?:service|receiver|provider)[^>]*android:exported=\"true\"", mf):
    errors.append("أمان: مكوّن مكشوف exported=true: " + m.group(0)[:60])
if re.search(r"setJavaScriptEnabled|addJavascriptInterface", "".join(f.read_text(encoding="utf-8") for f in SRC.rglob("*.kt"))) and "addJavascriptInterface" in "".join(f.read_text(encoding="utf-8") for f in SRC.rglob("*.kt")):
    warnings.append("أمان: addJavascriptInterface مستخدم — تأكد من حصره بمصادر موثوقة")

# 2.7) ملفات assets المطلوبة
AS = ROOT / "app/src/main/assets"
for need in ("blocklist.txt", "privacy.js"):
    if not (AS / need).exists(): errors.append(f"assets/{need} مفقود")
bl = AS / "blocklist.txt"
if bl.exists():
    doms = [l.strip().lower() for l in bl.read_text(encoding="utf-8").splitlines() if l.strip() and not l.startswith("#")]
    for d in doms:
        if not re.fullmatch(r"[a-z0-9.-]+\.[a-z]{2,}", d): errors.append(f"blocklist: نطاق غير صالح: {d}")
    if len(doms) != len(set(doms)): warnings.append("blocklist: نطاقات مكررة")
    for must in ("trackersimulator.org", "eviltracker.net"):
        if must not in doms: warnings.append(f"blocklist: {must} غير موجود")
if (AS / "privacy.js").exists() and "__SEED__" not in (AS / "privacy.js").read_text(encoding="utf-8"):
    errors.append("privacy.js: العلامة __SEED__ مفقودة")

# 2.8) كل نص L("...") له ترجمة إنجليزية في I18n.kt
i18n = (SRC / "core" / "I18n.kt").read_text(encoding="utf-8")
keys = set(re.findall(r'^\s+"((?:\\.|[^"\\])*)" to "', i18n, re.M))
used = set()
for f in SRC.rglob("*.kt"):
    if f.name == "I18n.kt": continue
    used |= set(re.findall(r'\bL\("((?:\\.|[^"\\])*)"\)', f.read_text(encoding="utf-8")))
for u in sorted(used - keys): errors.append(f"i18n: لا ترجمة إنجليزية للنص: {u}")
for k in sorted(keys - used): warnings.append(f"i18n: ترجمة غير مستخدمة: {k}")
if "__LANG__" not in (AS / "privacy.js").read_text(encoding="utf-8"): errors.append("privacy.js: العلامة __LANG__ مفقودة")

# 2.9) أسرار وملفات GitHub
for pat in ("*.jks", "*.keystore", "keystore.properties"):
    for f in ROOT.rglob(pat):
        if ".git" not in f.parts and "build" not in f.parts and f.name != "keystore.properties.example":
            errors.append(f"أمان: ملف توقيع داخل المشروع ({f.relative_to(ROOT)}) — لا ترفعه إلى git")
gi = (ROOT / ".gitignore").read_text(encoding="utf-8") if (ROOT / ".gitignore").exists() else ""
for need in ("*.jks", "keystore.properties"):
    if need not in gi: errors.append(f".gitignore ينقصه: {need}")
try:
    import yaml
    for f in (ROOT / ".github").rglob("*.y*ml"):
        try: yaml.safe_load(f.read_text(encoding="utf-8"))
        except Exception as e: errors.append(f"YAML غير صالح: {f.relative_to(ROOT)}: {e}")
except ImportError:
    warnings.append("PyYAML غير مثبّت: تخطّي فحص ملفات YAML")
for wf in (ROOT / ".github/workflows").glob("*.yml"):
    t = wf.read_text(encoding="utf-8")
    if re.search(r"uses:\s*[\w./-]+@(main|master)\b", t): warnings.append(f"{wf.name}: action مثبّت على main/master")
    if "permissions:" not in t: warnings.append(f"{wf.name}: بلا permissions صريحة")


# 2.95) تنظيم الواجهة: القياسات تأتي من UiLayout فقط
if not (SRC / "ui" / "UiLayout.kt").exists():
    errors.append("UiLayout.kt مفقود (ملف تنظيم الواجهة)")
else:
    sb = (SRC / "pwa" / "SiteBar.kt").read_text(encoding="utf-8")
    if re.search(r"height\((45|48|3)\.dp\)", sb): errors.append("SiteBar.kt: ارتفاع الشريط مكتوب رقماً — استخدم UiLayout.BAR_ROW_DP / PROGRESS_DP")
    if re.search(r"BAR_H\s*=\s*\d", (SRC / "pwa" / "Pwa.kt").read_text(encoding="utf-8")): errors.append("Pwa.kt: BAR_H يجب أن يشير إلى UiLayout.BAR_DP")
    if "fun Modifier.pageInsets" in (SRC / "perf" / "Smooth.kt").read_text(encoding="utf-8"): errors.append("Smooth.kt: pageInsets مكرّرة — مكانها UiLayout.kt")
    if not (AS / "pwa.js").exists(): errors.append("assets/pwa.js مفقود")


# 2.96) المتصفح الافتراضي: الـ Manifest يجب أن يعلن فلتر http/https القابل للتصفح + المشاركة + البحث
mf2 = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
for need, msg in (('android.intent.category.BROWSABLE', 'BROWSABLE'), ('android.intent.action.SEND', 'ACTION_SEND (مشاركة ← Nova)'),
                  ('android.intent.action.WEB_SEARCH', 'ACTION_WEB_SEARCH'), ('android:scheme="https"', 'scheme https')):
    if need not in mf2: errors.append("Manifest: ينقصه " + msg + " (مطلوب ليصلح التطبيق كمتصفح افتراضي)")
if not (SRC / "core" / "DefaultBrowser.kt").exists(): errors.append("DefaultBrowser.kt مفقود")




# 2.98) يوتيوب: WebView مخصّص + سكربت واحد، ولا بقايا للواجهة الأصلية أو للملفات المدموجة
_yt = AS / "yt.js"
if not _yt.exists(): errors.append("yt.js مفقود")
else:
    _t = _yt.read_text(encoding="utf-8")
    for tag in ("__CC__", "__UI__", "__PLAY__", "__BG__"):
        if tag not in _t: errors.append("yt.js: العلامة " + tag + " مفقودة")
    if "NovaYtApp" in _t or "__novaYtApp" in _t: errors.append("yt.js: بقايا جسر الواجهة الأصلية")
if not (SRC / "yt" / "YtWeb.kt").exists(): errors.append("YtWeb.kt مفقود (WebView يوتيوب المخصّص)")
if "var yt = false" not in (SRC / "browser" / "BrowserTab.kt").read_text(encoding="utf-8"): errors.append("BrowserTab.yt مفقود")
for gone in ("yt-all.js", "render.js", "boost.js"):
    if (AS / gone).exists(): errors.append("assets/" + gone + " يجب أن يُحذف (مدموج في yt.js / smooth.js)")
for gone in ("YtScreen", "YtApp", "YtMini"):
    for f in SRC.rglob("*.kt"):
        if re.search(r"\b" + gone + r"\b", f.read_text(encoding="utf-8")): errors.append(f.name + ": مرجع إلى " + gone + " المحذوف")
if not (AS / "smooth.js").exists() or not (SRC / "perf" / "Smooth.kt").exists(): errors.append("smooth.js / Smooth.kt مفقود")
else:
    _s = (AS / "smooth.js").read_text(encoding="utf-8")
    for tag in ("__FIT__", "__LAZY__", "__BOOST__", "__POP__"):
        if tag not in _s: errors.append("smooth.js: العلامة " + tag + " مفقودة")

# 3) كل Prefs.xxx المستخدمة معرّفة
prefs = (SRC / "core" / "Settings.kt").read_text(encoding="utf-8")
defined = set(re.findall(r"(?:va[lr])\s+(\w+)\s*(?:by|=|:)|fun\s+(\w+)\(", prefs))
defined = {x for t in defined for x in t if x}
for f in SRC.rglob("*.kt"):
    for m in set(re.findall(r"\bPrefs\.(\w+)", f.read_text(encoding="utf-8"))):
        if m not in defined and m != "engines":
            errors.append(f"{f.name}: Prefs.{m} غير معرّف")

print("\n".join(warnings + errors) if (warnings or errors) else "OK: لا أخطاء ظاهرة")
if not errors and warnings:
    print("لا أخطاء مانعة")
sys.exit(1 if errors else 0)
