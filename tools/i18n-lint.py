#!/usr/bin/env python3
"""i18n gate for the HarnessForAndroid UI.

Rule: every user-visible string in a migrated screen comes from
design/src/main/res/values*/strings.xml, so the system locale can switch it.
Chinese literals that must stay (runtime comparisons, engine-owned data) are
listed in tools/i18n-allowlist.txt with a reason.

Two modes:

  python tools/i18n-lint.py            # gate: only the files in tools/i18n-surface.txt
  python tools/i18n-lint.py --census   # whole-tree census, never fails

Gate exit codes: 0 clean, 1 unallowlisted literal found, 2 bad config.

i18n-surface.txt: one path per line, relative to the app root, '#' comments.
i18n-allowlist.txt: 'path<TAB>字面量原文<TAB>为什么保留' — matched on the literal
text, not the line number, so unrelated edits don't invalidate entries.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

CJK = re.compile(r"[\u3400-\u9fff\uff00-\uffef\u3000-\u303f]")
SKIP_DIRS = {"build", ".gradle", ".git", ".idea", "generated"}


def literal_hits(path: Path):
    """(line_no, raw) for CJK inside double-quoted strings; comment lines skipped."""
    hits = []
    in_block = False
    for no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        s = line.strip()
        if in_block:
            if "*/" in s:
                in_block = False
            continue
        if s.startswith("/*"):
            if "*/" not in s:
                in_block = True
            continue
        if s.startswith("//") or s.startswith("*"):
            continue
        i, n = 0, len(line)
        while i < n:
            if line[i] == "/" and i + 1 < n and line[i + 1] == "/":
                break
            if line[i] != '"':
                i += 1
                continue
            i += 1
            buf = []
            while i < n:
                ch = line[i]
                if ch == "\\" and i + 1 < n:
                    buf.append(line[i:i + 2])
                    i += 2
                    continue
                if ch == '"':
                    i += 1
                    break
                buf.append(ch)
                i += 1
            raw = "".join(buf)
            if CJK.search(raw):
                hits.append((no, raw))
    return hits


def read_list(path: Path):
    out = []
    if not path.exists():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.lstrip().startswith("#"):
            out.append(line)
    return out


def main() -> int:
    here = Path(__file__).resolve().parent
    root = here.parent
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(root))
    ap.add_argument("--surface", default=str(here / "i18n-surface.txt"))
    ap.add_argument("--allowlist", default=str(here / "i18n-allowlist.txt"))
    ap.add_argument("--census", action="store_true", help="print whole-tree counts, never fail")
    ap.add_argument("--file", help="detail one file: every CJK literal, allowlisted or not")
    args = ap.parse_args()
    root = Path(args.root).resolve()

    allowed = {}
    for line in read_list(Path(args.allowlist)):
        parts = line.split("\t")
        if len(parts) < 2:
            print(f"bad allowlist line: {line!r}", file=sys.stderr)
            return 2
        allowed.setdefault(parts[0].strip(), set()).add(parts[1])

    if args.file:
        target = Path(args.file)
        if not target.is_absolute():
            target = root / target
        rel = target.resolve().relative_to(root).as_posix() if target.resolve().is_relative_to(root) else target.as_posix()
        hits = literal_hits(target)
        for no, raw in hits:
            mark = "allow" if raw in allowed.get(rel, set()) else "BAD"
            print(f"{mark}  {rel}:{no}: {raw}")
        refs = target.read_text(encoding="utf-8").count("stringResource(") + target.read_text(encoding="utf-8").count(".getString(")
        print(f"file: {rel} — {len(hits)} CJK literal(s), {refs} resource reference(s)")
        return 0

    if args.census:
        files = {}
        total = 0
        for p in sorted(root.rglob("*.kt")):
            if any(part in SKIP_DIRS for part in p.parts):
                continue
            n = len(literal_hits(p))
            if n:
                files[p.relative_to(root).as_posix()] = n
                total += n
        print(f"census: {total} Chinese literal(s) in {len(files)} file(s) (informational)")
        for rel in sorted(files, key=lambda k: -files[k]):
            print(f"  {files[rel]:5d}  {rel}")
        return 0

    surface = read_list(Path(args.surface))
    if not surface:
        print(f"no surface files listed in {args.surface}", file=sys.stderr)
        return 2

    bad = 0
    scanned = 0
    for rel in surface:
        p = root / rel
        if not p.exists():
            print(f"missing surface file: {rel}", file=sys.stderr)
            return 2
        scanned += 1
        for no, raw in literal_hits(p):
            if raw in allowed.get(rel, set()):
                continue
            bad += 1
            print(f"{rel}:{no}: {raw}")
    if bad:
        print(f"i18n-lint: {bad} hardcoded Chinese literal(s) across {scanned} surface file(s)")
        return 1
    print(f"i18n-lint: clean — {scanned} surface file(s), 0 unallowlisted literals")
    return 0


if __name__ == "__main__":
    sys.exit(main())
