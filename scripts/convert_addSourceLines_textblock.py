#!/usr/bin/env python3
"""
Convert CompilationTestHelper.addSourceLines(...) calls in a single Java file to use text blocks.

Assumptions:
- Each argument is on its own line.
- The first argument is the filename string literal.
- Subsequent arguments are string literals representing source lines, with optional // or /* */ comments
  on their own line or trailing after a string literal.
"""

from __future__ import annotations

import argparse
import sys
from typing import Iterable, Iterator, List, Optional, Tuple


def find_add_source_lines_calls(src: str) -> List[Tuple[int, int]]:
    """Return (open_paren_idx, close_paren_idx) pairs for addSourceLines calls."""
    calls = []
    i = 0
    n = len(src)
    state = "normal"
    while i < n:
        c = src[i]
        if state == "normal":
            if c == '"':
                state = "string"
            elif c == "'":
                state = "char"
            elif c == "/" and i + 1 < n and src[i + 1] == "/":
                state = "line_comment"
                i += 1
            elif c == "/" and i + 1 < n and src[i + 1] == "*":
                state = "block_comment"
                i += 1
            elif src.startswith("addSourceLines(", i):
                open_idx = i + len("addSourceLines")
                close_idx = find_matching_paren(src, open_idx)
                if close_idx is not None:
                    calls.append((open_idx, close_idx))
                    i = close_idx
        elif state == "string":
            if c == "\\":
                i += 1
            elif c == '"':
                state = "normal"
        elif state == "char":
            if c == "\\":
                i += 1
            elif c == "'":
                state = "normal"
        elif state == "line_comment":
            if c == "\n":
                state = "normal"
        elif state == "block_comment":
            if c == "*" and i + 1 < n and src[i + 1] == "/":
                state = "normal"
                i += 1
        i += 1
    return calls


def find_matching_paren(src: str, open_idx: int) -> Optional[int]:
    """Find matching ')' for the '(' at open_idx."""
    if open_idx >= len(src) or src[open_idx] != "(":
        return None
    depth = 1
    i = open_idx + 1
    state = "normal"
    while i < len(src):
        c = src[i]
        if state == "normal":
            if c == '"':
                state = "string"
            elif c == "'":
                state = "char"
            elif c == "/" and i + 1 < len(src) and src[i + 1] == "/":
                state = "line_comment"
                i += 1
            elif c == "/" and i + 1 < len(src) and src[i + 1] == "*":
                state = "block_comment"
                i += 1
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    return i
        elif state == "string":
            if c == "\\":
                i += 1
            elif c == '"':
                state = "normal"
        elif state == "char":
            if c == "\\":
                i += 1
            elif c == "'":
                state = "normal"
        elif state == "line_comment":
            if c == "\n":
                state = "normal"
        elif state == "block_comment":
            if c == "*" and i + 1 < len(src) and src[i + 1] == "/":
                state = "normal"
                i += 1
        i += 1
    return None


def split_string_literal(body: str) -> Optional[Tuple[str, str]]:
    """Split a Java string literal from the start of body, returning (content, rest)."""
    if not body.startswith('"'):
        return None
    i = 1
    content = []
    while i < len(body):
        c = body[i]
        if c == "\\":
            if i + 1 < len(body):
                content.append(body[i : i + 2])
                i += 2
                continue
        if c == '"':
            return "".join(content), body[i + 1 :]
        content.append(c)
        i += 1
    return None


def unescape_for_text_block(raw: str) -> str:
    """Make string literal content suitable for a text block."""
    return raw.replace('\\"', '"')


def transform_args_text(args_text: str) -> Optional[str]:
    lines = args_text.splitlines(keepends=True)
    if not lines:
        return None

    items: List[Tuple[str, str, str, Optional[str]]] = []
    arg_line_indices: List[int] = []
    filename_line_idx = None
    filename_line = None
    arg_indent = None

    for idx, line in enumerate(lines):
        if not line.strip():
            continue
        indent_len = len(line) - len(line.lstrip())
        indent = line[:indent_len]
        body = line[indent_len:]
        if body.startswith('"""'):
            return None
        if body.startswith('"'):
            parsed = split_string_literal(body)
            if parsed is None:
                return None
            content, rest = parsed
            rest_no_nl = rest.rstrip("\r\n")
            comment_idx = rest_no_nl.find("//")
            block_idx = rest_no_nl.find("/*")
            idx_comment = min(
                [i for i in (comment_idx, block_idx) if i != -1],
                default=-1,
            )
            comment = None
            spacing = ""
            rest_prefix = rest_no_nl
            if idx_comment != -1:
                comment = rest_no_nl[idx_comment:]
                spacing = rest_no_nl[:idx_comment].replace(",", "")
                rest_prefix = rest_no_nl[:idx_comment]
            if rest_prefix.strip().strip(","):
                return None
            if filename_line_idx is None:
                filename_line_idx = idx
                filename_line = line
                arg_indent = indent
            else:
                items.append(("string", content, spacing, comment))
                arg_line_indices.append(idx)
        elif body.lstrip().startswith("//") or body.lstrip().startswith("/*"):
            if filename_line_idx is None:
                return None
            if arg_indent is None:
                arg_indent = indent
            if line.startswith(arg_indent):
                comment_body = line[len(arg_indent) :].rstrip("\r\n")
            else:
                comment_body = line.lstrip().rstrip("\r\n")
            items.append(("comment", comment_body, "", None))
            arg_line_indices.append(idx)
        else:
            return None

    if filename_line_idx is None or filename_line is None:
        return None
    if not items:
        return None
    if arg_indent is None:
        arg_indent = ""

    last_arg_line_idx = max(arg_line_indices) if arg_line_indices else filename_line_idx
    line_ending = "\n"
    for line in lines:
        if line.endswith("\r\n"):
            line_ending = "\r\n"
            break

    content_lines: List[str] = []
    for kind, content, spacing, comment in items:
        if kind == "string":
            line = unescape_for_text_block(content)
            if comment:
                line += f"{spacing}{comment}"
            content_lines.append(line)
        else:
            content_lines.append(content)

    prefix_lines = lines[:filename_line_idx]
    suffix_lines = lines[last_arg_line_idx + 1 :]

    new_lines: List[str] = []
    new_lines.extend(prefix_lines)
    if filename_line.endswith(("\n", "\r\n")):
        new_lines.append(filename_line)
    else:
        new_lines.append(filename_line + line_ending)
    new_lines.append(f'{arg_indent}"""{line_ending}')
    for content_line in content_lines:
        new_lines.append(f"{arg_indent}{content_line}{line_ending}")
    new_lines.append(f'{arg_indent}"""{line_ending}')
    new_lines.extend(suffix_lines)
    return "".join(new_lines)


def transform_source(src: str) -> Tuple[str, int]:
    calls = find_add_source_lines_calls(src)
    if not calls:
        return src, 0

    out = []
    last = 0
    changes = 0
    for open_idx, close_idx in calls:
        args_text = src[open_idx + 1 : close_idx]
        new_args = transform_args_text(args_text)
        if new_args is None:
            continue
        out.append(src[last : open_idx + 1])
        out.append(new_args)
        last = close_idx
        changes += 1
    if changes == 0:
        return src, 0
    out.append(src[last:])
    return "".join(out), changes


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Convert addSourceLines calls to text blocks in a single Java file."
    )
    parser.add_argument("path", help="Path to a .java file")
    parser.add_argument(
        "--stdout",
        action="store_true",
        help="Write output to stdout instead of modifying the file",
    )
    args = parser.parse_args(argv)

    path = args.path
    try:
        src = open(path, "r", encoding="utf-8").read()
    except OSError as exc:
        print(f"Failed to read {path}: {exc}", file=sys.stderr)
        return 1

    updated, changes = transform_source(src)
    if changes == 0:
        print("No changes needed.", file=sys.stderr)
        return 0

    if args.stdout:
        sys.stdout.write(updated)
        return 0

    try:
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(updated)
    except OSError as exc:
        print(f"Failed to write {path}: {exc}", file=sys.stderr)
        return 1

    print(f"Updated {changes} addSourceLines call(s) in {path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
