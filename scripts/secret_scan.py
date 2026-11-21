#!/usr/bin/env python3
"""High-confidence local secret scan. Does not upload source to a third party."""
from pathlib import Path
import re
import subprocess
import sys

PATTERNS = {
    "AWS access key": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "GitHub token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"),
    "Slack token": re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b"),
    "Stripe secret key": re.compile(r"\b(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}\b"),
    "Google API key": re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    "Private key block": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "Credential-bearing database URL": re.compile(r"\b(?:postgres(?:ql)?|mysql|mongodb(?:\+srv)?):\/\/[^\s/:@]+:[^\s/@]+@", re.I),
    "Sensitive key/token assignment": re.compile(
        r"\b(?:supabase[_-]?service[_-]?role[_-]?key|service[_-]?role[_-]?key|client[_-]?secret|private[_-]?key|access[_-]?token|refresh[_-]?token)\b\s*[:=]\s*['\"](?!\$\{\{)[^'\"\s]{16,}['\"]",
        re.I,
    ),
    "JWT-like token": re.compile(r"\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b"),
}

ALLOWED_SUFFIXES = {
    ".kt", ".java", ".kts", ".gradle", ".properties", ".xml", ".json", ".js",
    ".yml", ".yaml", ".toml", ".md", ".txt", ".sh", ".py", ".conf", ".config",
}
EXCLUDED_PARTS = {".git", ".gradle", "build", "node_modules"}


def tracked_paths():
    result = subprocess.run(["git", "ls-files", "-z"], check=True, stdout=subprocess.PIPE)
    for raw in result.stdout.split(b"\0"):
        if not raw:
            continue
        path = Path(raw.decode("utf-8", "replace"))
        if any(part in EXCLUDED_PARTS for part in path.parts):
            continue
        if path.suffix.lower() in ALLOWED_SUFFIXES:
            yield path


def main():
    findings = []
    for path in tracked_paths():
        try:
            lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for line_number, line in enumerate(lines, 1):
            for label, pattern in PATTERNS.items():
                if pattern.search(line):
                    # Supabase anon/publishable client keys are intentionally public client config.
                    if label == "JWT-like token" and re.search(r"SUPABASE_(?:ANON|PUBLISHABLE)_KEY", line, re.I):
                        continue
                    findings.append((path, line_number, label))
    if findings:
        for path, line_number, label in findings:
            print(f"Potential {label}: {path}:{line_number}")
        print(f"Secret scan failed: {len(findings)} high-confidence match(es). Review before merging.")
        return 1
    print("Secret scan passed: no high-confidence credential patterns found in tracked source.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
