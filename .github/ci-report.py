"""Turn build.log and the JUnit results into GitHub annotations (shown on the exact file and line,
and by `gh run view`) plus a one-screen summary on the run page.

Usage (CI): python3 .github/ci-report.py      Self-check: python3 .github/ci-report.py --test
"""
import glob, os, re, sys, xml.etree.ElementTree as ET


def compile_errors(log, root):
    """Kotlin 'e: file:///abs/path.kt:12:5 message' lines -> unique (path, line, col, message)."""
    found = re.findall(r"^(?:\S+Z )?e: (?:file://)?(\S+?):(\d+):(\d+) (.+?)\s*$", log, re.M)
    return list(dict.fromkeys((p.replace(root, "", 1), l, c, m) for p, l, c, m in found))  # debug+release repeat each


def gradle_failure(log):
    """The 'What went wrong' text Gradle prints for failures that aren't compile errors."""
    m = re.search(r"\* What went wrong:\n(.*?)\n\* Try:", log, re.S)
    return re.sub(r"(?m)^\S+Z ", "", m.group(1)).strip() if m else ""


def test_results(pattern="app/build/test-results/**/*.xml"):
    total, failed = 0, []
    for f in glob.glob(pattern, recursive=True):
        suite = ET.parse(f).getroot()
        total += int(suite.get("tests", 0))
        for case in suite.iter("testcase"):
            for bad in case.findall("failure") + case.findall("error"):
                failed.append((case.get("classname", ""), case.get("name", ""), (bad.get("message") or "").strip()))
    return total, failed


def esc(s):  # annotation message escaping
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def main():
    root = os.getcwd().rstrip("/") + "/"
    log = open("build.log", encoding="utf-8", errors="replace").read() if os.path.exists("build.log") else ""
    errors, (total, failed) = compile_errors(log, root), test_results()
    other = "" if errors else gradle_failure(log)
    apks = glob.glob("StorageCleaner-*.apk")

    for path, line, col, msg in errors:
        print(f"::error file={path},line={line},col={col},title=Compile error::{esc(msg)}")
    for cls, name, msg in failed:
        print(f"::error title=Test failed: {name}::{esc(cls + '.' + name + ': ' + msg.splitlines()[0][:400] if msg else cls + '.' + name)}")
    if other:
        print(f"::error title=Gradle failed::{esc(other[:1500])}")

    ok = os.environ.get("JOB_STATUS", "success") == "success"
    tests = f"{total - len(failed)} passed, {len(failed)} failed" if total else "not run"
    apk = ", ".join(f"`{a}` ({os.path.getsize(a) / 1048576:.1f} MB)" for a in apks) or "not built"
    out = [f"## {'✅ Build passed' if ok else '❌ Build failed'}", "",
           "| Check | Result |", "|---|---|",
           f"| Compile | {'❌ ' + str(len(errors)) + ' error(s)' if errors else '✅ ok' if total or apks else '—'} |",
           f"| Unit tests | {'❌ ' if failed else '✅ ' if total else ''}{tests} |",
           f"| APK | {apk} |"]
    if errors:
        out += ["", "### Compile errors", *(f"- `{p}:{l}` {m}" for p, l, _, m in errors)]
    if failed:
        out += ["", "### Failed tests", *(f"- `{c}.{n}`: {m.splitlines()[0] if m else ''}" for c, n, m in failed)]
    if other:
        out += ["", "### What went wrong", "```", other[:3000], "```"]
    if apks:
        out += ["", "Download the APK from **Artifacts** below" + (", or from the release." if os.environ.get("GITHUB_REF_TYPE") == "tag" else ".")]
    summary = "\n".join(out) + "\n"
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as f:
            f.write(summary)
    else:
        print(summary)


def selftest():
    import tempfile
    log = ("2026-11-25T04:47:45.0177096Z e: file:///w/r/app/src/A.kt:124:70 Platform declaration clash: x\n"
           "2026-11-25T04:47:45.0186234Z e: file:///w/r/app/src/A.kt:124:70 Platform declaration clash: x\n"
           "e: file:///w/r/app/src/B.kt:3:1 Unresolved reference 'List'.\n")
    assert compile_errors(log, "/w/r/") == [("app/src/A.kt", "124", "70", "Platform declaration clash: x"),
                                            ("app/src/B.kt", "3", "1", "Unresolved reference 'List'.")]
    assert gradle_failure("* What went wrong:\nExecution failed for task ':app:x'.\n> boom\n\n* Try:\n") == \
        "Execution failed for task ':app:x'.\n> boom"
    with tempfile.TemporaryDirectory() as d:
        with open(os.path.join(d, "TEST-x.xml"), "w") as f:
            f.write('<testsuite tests="3"><testcase classname="T" name="a"/><testcase classname="T" name="b">'
                    '<failure message="expected 1 but was 2">trace</failure></testcase><testcase classname="T" name="c"/></testsuite>')
        assert test_results(os.path.join(d, "*.xml")) == (3, [("T", "b", "expected 1 but was 2")])
    assert esc("50%\nx") == "50%25%0Ax"
    print("ci-report selftest ok")


if __name__ == "__main__":
    selftest() if "--test" in sys.argv else main()
