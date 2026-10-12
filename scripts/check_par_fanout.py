#!/usr/bin/env python3
"""Fail on a parallel fan-out whose concurrency is neither bounded nor justified.

Every call in Arrow's `parMap` family runs its body on `Dispatchers.IO` with no
limit of its own. When the body is an SMB call it parks that thread in the native
runtime's `block_on`, and that runtime has two worker threads behind it, so an
unbounded fan-out over a share fills the pool and then stops dead rather than
finishing slowly. That has been the cause of three separate hangs: the restore
scan, the metadata reload, and the DwarFS entry points.

A `parMap*` call must therefore either

  * name its bound -- `parMap(concurrency = 4) { ... }`, or
  * say why its fan-out cannot grow -- a `// unbounded: <reason>` comment on the
    call or just above it.

The second form exists because some fan-outs are bounded by their receiver rather
than by a number: `list.chunked(25).parMap { ... }` cannot exceed 25 no matter
what the list holds. Writing `concurrency = 25` there would be noise; writing the
reason is not.

Run from the repository root:  python3 scripts/check_par_fanout.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOTS = ("app/src/main/kotlin", "core")
CALL = re.compile(r"\b(parMap|parMapNotNull|parMapIndexed|parForEach|parForEachIndexed)\s*[({]")
BOUNDED = re.compile(r"\bconcurrency\s*=")
JUSTIFIED = re.compile(r"//\s*unbounded\s*:")
LOOKAHEAD = 4  # the argument list may be broken across lines


def is_comment(line: str) -> bool:
    stripped = line.lstrip()
    return stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*")


def justified(lines: list[str], index: int) -> bool:
    """Whether the comment block directly above the call carries the marker.

    Walking up the contiguous comment block rather than a fixed number of lines
    keeps a justification attached to the call it explains: a comment further up,
    separated by code, cannot cover it by accident.
    """
    if JUSTIFIED.search(lines[index]):
        return True
    cursor = index - 1
    while cursor >= 0 and is_comment(lines[cursor]):
        if JUSTIFIED.search(lines[cursor]):
            return True
        cursor -= 1
    return False


def kotlin_sources() -> list[Path]:
    files: list[Path] = []
    for root in ROOTS:
        path = Path(root)
        if path.exists():
            files.extend(path.rglob("*.kt"))
    return sorted(files)


def is_bounded(lines: list[str], index: int) -> bool:
    """Whether this call names its own bound.

    The argument list may be broken across lines, so a few lines are read ahead --
    but only until the next call starts. Otherwise a bound belonging to the call
    below would be read as this one's, and an unbounded call would pass.
    """
    if BOUNDED.search(lines[index]):
        return True
    for cursor in range(index + 1, min(index + LOOKAHEAD, len(lines))):
        if CALL.search(lines[cursor]):
            return False
        if BOUNDED.search(lines[cursor]):
            return True
    return False


def check(path: Path) -> list[tuple[int, str]]:
    lines = path.read_text(encoding="utf-8").splitlines()
    findings: list[tuple[int, str]] = []
    for index, line in enumerate(lines):
        if is_comment(line) or not CALL.search(line):
            continue
        if is_bounded(lines, index):
            continue
        if justified(lines, index):
            continue
        findings.append((index + 1, line.strip()))
    return findings


def main() -> int:
    failures: list[str] = []
    for path in kotlin_sources():
        for line_number, text in check(path):
            failures.append(f"{path}:{line_number}: {text}")

    if not failures:
        return 0

    print("Unbounded parallel fan-out. Name `concurrency = ...`, or say why it cannot grow:", file=sys.stderr)
    print(file=sys.stderr)
    for failure in failures:
        print(f"  {failure}", file=sys.stderr)
    print(file=sys.stderr)
    print(f"{len(failures)} call(s) to fix.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
