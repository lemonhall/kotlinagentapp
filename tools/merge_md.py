#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from datetime import datetime, timezone
from pathlib import Path


DEFAULT_INPUTS = [
    "docs/plan/v38-radio-module-overview.md",
    "docs/plan/v39-radio-recording.md",
    "docs/plan/v40-radio-offline-transcript.md",
    "docs/plan/v41-radio-offline-translation.md",
    "docs/plan/v42-radio-language-learning.md",
    "docs/plan/v43-radio-dual-language.md",
    "docs/plan/v44-asr-tts-modularization.md",
    "docs/plan/v45-radio-live-translation-full.md",
    "docs/plan/v46-radio-live-AudioFocusManager.md",
]

# Matches a file whose entire content is wrapped in ```markdown ... ```
_OUTER_FENCE_RE = re.compile(
    r"\A\s*```markdown\s*\n(.*)\n\s*```\s*\Z",
    re.DOTALL,
)


def _read_utf8(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _normalize_newlines(text: str) -> str:
    return text.replace("\r\n", "\n").replace("\r", "\n")


def _strip_outer_fence(text: str) -> str:
    """If the entire text is wrapped in a single ```markdown ... ``` fence, strip it."""
    m = _OUTER_FENCE_RE.match(text)
    if m:
        return m.group(1)
    return text


def _display_path(repo_root: Path, path: Path) -> str:
    try:
        return path.relative_to(repo_root).as_posix()
    except ValueError:
        return path.as_posix()


def merge_markdown(repo_root: Path, paths: list[Path], title: str) -> str:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    included = "\n".join([f"- `{_display_path(repo_root, p)}`" for p in paths])
    header = f"# {title}\n\n生成时间（UTC）：`{now}`\n\n## 包含文件\n{included}\n"

    parts: list[str] = [header]
    for p in paths:
        raw = _normalize_newlines(_read_utf8(p))
        raw = _strip_outer_fence(raw)
        raw = raw.rstrip() + "\n"
        shown = _display_path(repo_root, p)
        parts.append(
            "\n---\n\n"
            f"## Source：`{shown}`\n\n"
            "<!-- merged by tools/merge_md.py -->\n\n"
            f"{raw}"
        )
    return "".join(parts).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Merge multiple markdown files into a single markdown document (UTF-8)."
    )
    parser.add_argument(
        "inputs",
        nargs="*",
        help="Input markdown file paths. If omitted, uses v38-v46 radio plan defaults.",
    )
    parser.add_argument(
        "--out",
        help="Output markdown path. If omitted, prints to stdout.",
    )
    parser.add_argument(
        "--title",
        default="Radio Plans v38-v46（Merged）",
        help="Title for the merged markdown document.",
    )
    args = parser.parse_args()

    repo_root = Path.cwd()
    input_strs = args.inputs if args.inputs else DEFAULT_INPUTS
    inputs = [(repo_root / s).resolve() for s in input_strs]

    missing = [p for p in inputs if not p.exists()]
    if missing:
        msg = "\n".join([f"- {p}" for p in missing])
        raise SystemExit(f"Missing input files:\n{msg}")

    merged = merge_markdown(repo_root, inputs, title=args.title)

    if args.out:
        out_path = (repo_root / args.out).resolve() if not Path(args.out).is_absolute() else Path(args.out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(merged, encoding="utf-8")
        print(out_path)
        return 0

    print(merged, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())