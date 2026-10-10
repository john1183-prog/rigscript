#!/usr/bin/env python3
"""Publish a "CI report" check run for the current commit.

Why this exists: the Claude sandbox that works on this repo cannot download
job logs or artifacts (GitHub redirects them to a host the sandbox cannot
resolve), but it can read check runs through api.github.com. This script
condenses the Gradle output and the JUnit XML results into one check run:
GET /repos/<owner>/<repo>/commits/<sha>/check-runs  ->  name "CI report".

It must never fail the job: every error is printed and swallowed.

Usage: ci_report.py <gradle-log-file>
Environment: GITHUB_TOKEN, GITHUB_REPOSITORY, GITHUB_SHA (set by Actions),
BUILD_OUTCOME (outcome of the build step), RUN_URL (optional).
"""
import glob
import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

LIMIT = 60000  # check-run text limit is 65535 characters
RUNNER_PREFIX = re.compile(r"file:///home/runner/work/[^/]+/[^/]+/")


def read_lines(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            return [line.rstrip("\n") for line in f]
    except OSError:
        return []


def clean(line):
    return RUNNER_PREFIX.sub("", line)


def compile_errors(lines):
    """Kotlin (`e: ...`) and javac/KSP style errors."""
    out = []
    for line in lines:
        if line.startswith("e: ") or re.search(r"\berror: ", line):
            out.append(clean(line))
    return out


def went_wrong(lines):
    out, on = [], False
    for line in lines:
        if line.startswith("* What went wrong:"):
            on = True
            continue
        if on and (line.startswith("* Try:") or line.startswith("* Exception is:")):
            break
        if on:
            out.append(clean(line))
    return out


def warning_count(lines):
    return sum(1 for line in lines if line.startswith("w: "))


def test_results():
    total = failed = errored = skipped = 0
    failures = []
    files = sorted(glob.glob("app/build/test-results/**/*.xml", recursive=True))
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except Exception:
            continue
        for case in root.iter("testcase"):
            total += 1
            name = "%s.%s" % (case.get("classname", "?"), case.get("name", "?"))
            failure = case.find("failure")
            error = case.find("error")
            if failure is not None or error is not None:
                node = failure if failure is not None else error
                if failure is not None:
                    failed += 1
                else:
                    errored += 1
                body = (node.get("message") or "") + "\n" + (node.text or "")
                failures.append((name, "\n".join(body.split("\n")[:14])))
            elif case.find("skipped") is not None:
                skipped += 1
    return len(files), total, failed, errored, skipped, failures


def captured_output(limit=40000):
    """Lines that tests print on purpose, prefixed `CAPTURE|` (WP0.3 legacy JSON, WP0.4 goldens)."""
    out, size = [], 0
    for path in sorted(glob.glob("app/build/test-results/**/*.xml", recursive=True)):
        try:
            root = ET.parse(path).getroot()
        except Exception:
            continue
        for node in root.iter("system-out"):
            for line in (node.text or "").split("\n"):
                if line.startswith("CAPTURE|"):
                    size += len(line) + 1
                    if size > limit:
                        out.append("...[capture truncated at %d characters]" % limit)
                        return out
                    out.append(line)
    return out


def apk_info():
    path = "app/build/outputs/apk/debug/app-debug.apk"
    if os.path.exists(path):
        return "app-debug.apk %.1f MB" % (os.path.getsize(path) / 1048576.0)
    return "no APK produced"


def build_report(log_lines):
    outcome = os.environ.get("BUILD_OUTCOME", "unknown")
    sha = os.environ.get("GITHUB_SHA", "?")
    run_url = os.environ.get("RUN_URL", "")
    nfiles, total, failed, errored, skipped, failures = test_results()
    errors = compile_errors(log_lines)
    wrong = went_wrong(log_lines)

    bits = ["build " + outcome.upper()]
    if nfiles:
        bits.append("tests %d run, %d failed, %d errors, %d skipped" % (total, failed, errored, skipped))
    else:
        bits.append("no unit-test results")
    if errors:
        bits.append("%d compiler error lines" % len(errors))
    title = " | ".join(bits)

    parts = ["## CI report", "", "- commit `%s`" % sha[:7], "- build step outcome: **%s**" % outcome]
    if run_url:
        parts.append("- run: " + run_url)
    parts.append("- %s" % apk_info())
    parts.append("- Kotlin warnings: %d" % warning_count(log_lines))
    parts.append("- unit tests: %d result files, %d tests, %d failed, %d errors, %d skipped"
                 % (nfiles, total, failed, errored, skipped))
    if failures:
        parts += ["", "### Failing tests"]
        for name, body in failures[:25]:
            parts += ["", "**%s**" % name, "```", body, "```"]
    if errors:
        parts += ["", "### Compiler errors (first 80)", "```"] + errors[:80] + ["```"]
    captured = captured_output()
    if captured:
        parts += ["", "### Captured test output (%d lines)" % len(captured), "```"] + captured + ["```"]
    if wrong:
        parts += ["", "### Gradle: what went wrong", "```"] + wrong[:60] + ["```"]
    tail = [clean(line) for line in log_lines[-70:]]
    parts += ["", "### Gradle output, last %d lines" % len(tail), "```"] + tail + ["```"]
    text = "\n".join(parts)
    if len(text) > LIMIT:
        text = text[: LIMIT - 120] + "\n...[truncated to fit the check-run limit]"
    summary = "%s\n\ncommit %s" % (title, sha[:7])
    return title, summary, text


def post(title, summary, text):
    token = os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY")
    sha = os.environ.get("GITHUB_SHA")
    if not (token and repo and sha):
        print("ci_report: GITHUB_TOKEN / GITHUB_REPOSITORY / GITHUB_SHA missing; not posting")
        return
    payload = {
        "name": "CI report",
        "head_sha": sha,
        "status": "completed",
        "conclusion": "neutral",
        "external_id": os.environ.get("GITHUB_RUN_ID", ""),
        "output": {"title": title[:250], "summary": summary, "text": text},
    }
    request = urllib.request.Request(
        "https://api.github.com/repos/%s/check-runs" % repo,
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={
            "Authorization": "Bearer " + token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            print("ci_report: check run posted, HTTP %d" % response.status)
    except urllib.error.HTTPError as err:
        print("ci_report: HTTP %d %s" % (err.code, err.read().decode("utf-8", "replace")[:400]))
    except Exception as err:  # never fail the job
        print("ci_report: post failed: %r" % (err,))


def main():
    log_path = sys.argv[1] if len(sys.argv) > 1 else "build.log"
    try:
        title, summary, text = build_report(read_lines(log_path))
        print(text)
        post(title, summary, text)
    except Exception as err:  # never fail the job
        print("ci_report: unexpected error: %r" % (err,))


if __name__ == "__main__":
    main()
