#!/usr/bin/env python3
"""
zip_project.py  —  GrabKit release zipper
Reads .gitignore from the repo root, skips everything it excludes,
and writes grabkit-<version>.zip next to this script.

Usage:
    python zip_project.py              # uses version from CHANGELOG.md
    python zip_project.py --version 0.2.0
    python zip_project.py --out /tmp/release.zip
"""

import argparse
import os
import re
import zipfile
from pathlib import Path


# ── gitignore parser ──────────────────────────────────────────────────────────

def load_gitignore_patterns(root: Path) -> list[re.Pattern]:
    gitignore = root / ".gitignore"
    if not gitignore.exists():
        return []

    patterns = []
    for raw in gitignore.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue

        # strip leading negation (we don't support ! rules — just skip them)
        if line.startswith("!"):
            continue

        # convert glob to regex
        pat = line
        is_dir_only = pat.endswith("/")
        if is_dir_only:
            pat = pat[:-1]

        # escape everything except * and ?
        pat = re.escape(pat)
        pat = pat.replace(r"\*\*", ".*")   # ** → match anything including /
        pat = pat.replace(r"\*", "[^/]*")  # *  → match within one segment
        pat = pat.replace(r"\?", "[^/]")   # ?  → single char

        # if no slash in the original (before escaping), match basename anywhere
        if "/" not in line.rstrip("/"):
            pat = r"(^|.*\/)" + pat + r"(/.*|$)"
        else:
            pat = r"^" + pat + r"(/.*|$)"

        try:
            patterns.append(re.compile(pat))
        except re.error:
            pass  # malformed pattern — ignore

    return patterns


def is_ignored(path: Path, root: Path, patterns: list[re.Pattern]) -> bool:
    rel = path.relative_to(root).as_posix()
    for pat in patterns:
        if pat.search(rel):
            return True
    return False


# ── version detection ─────────────────────────────────────────────────────────

def detect_version(root: Path) -> str:
    """Pull the latest version tag from CHANGELOG.md."""
    for changelog in (root / "CHANGELOG.md", root / "grabkit-android" / "CHANGELOG.md"):
        if changelog.exists():
            text = changelog.read_text(encoding="utf-8")
            m = re.search(r"##\s+\[(\d+\.\d+\.\d+)\]", text)
            if m:
                return m.group(1)
    return "0.0.0"


# ── zip ───────────────────────────────────────────────────────────────────────

def build_zip(root: Path, out: Path, patterns: list[re.Pattern]) -> int:
    always_skip = {".git", ".gradle", "__pycache__", "node_modules"}
    count = 0

    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        for dirpath, dirnames, filenames in os.walk(root):
            dp = Path(dirpath)

            # prune dirs in-place so os.walk won't descend into them
            dirnames[:] = [
                d for d in dirnames
                if d not in always_skip
                and not is_ignored(dp / d, root, patterns)
            ]

            for fname in filenames:
                fp = dp / fname
                if fp == out:                          # skip the zip itself
                    continue
                if is_ignored(fp, root, patterns):
                    continue

                arcname = fp.relative_to(root).as_posix()
                zf.write(fp, arcname)
                count += 1

    return count


# ── main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(description="Zip GrabKit, respecting .gitignore")
    parser.add_argument("--version", help="Override version string")
    parser.add_argument("--out", help="Output path (default: grabkit-<version>.zip in repo root)")
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    patterns = load_gitignore_patterns(root)

    version = args.version or detect_version(root)
    out = Path(args.out) if args.out else root / f"grabkit-{version}.zip"

    print(f"Root  : {root}")
    print(f"Output: {out}")
    print(f"Version: {version}")
    print("Zipping…")

    count = build_zip(root, out, patterns)
    size_kb = out.stat().st_size / 1024

    print(f"Done  : {count} files → {out.name} ({size_kb:.1f} KB)")


if __name__ == "__main__":
    main()