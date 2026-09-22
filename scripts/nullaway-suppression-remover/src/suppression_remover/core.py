# Copyright (c) 2026 Uber Technologies, Inc.
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in
# all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
# THE SOFTWARE.

"""
Core logic for removing unnecessary @SuppressWarnings annotations.

Algorithm
---------
1. Discover all Java source files in the target module directory.
2. Parse every @SuppressWarnings annotation that contains the target checker
   (or any alternate suppression string). Tree-sitter is used for parsing so
   occurrences inside string literals, block comments, and line comments are
   correctly ignored.
3. Strip the checker from every such annotation (or delete the whole annotation
   line if it was the only checker). Save a backup of every original file.
4. Run a build (e.g. ``gradle compileJava``) with ``--continue`` so all
   previously-hidden errors surface.
5. Map each error location back to the annotation that was suppressing it, using
   a line-number mapping that accounts for deleted annotation lines.
6. Iteratively restore -> remove unneeded -> verify, expanding the "needed" set
   whenever new errors surface.
"""

import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import tree_sitter as ts
import tree_sitter_java as tsjava

JAVA_LANGUAGE = ts.Language(tsjava.language())


@dataclass
class Annotation:
    """A @SuppressWarnings annotation found in a Java source file."""

    start_line: int  # 1-indexed, inclusive
    end_line: int  # 1-indexed, inclusive
    original_lines: list  # list[str] — exact source lines (with newlines)
    checkers: list  # list[str] — checker names in original order
    needed: bool = field(default=False)


# ---------------------------------------------------------------------------
# Project root detection
# ---------------------------------------------------------------------------


def find_project_root(start: Path | None = None) -> Path:
    """Walk up from *start* (default: cwd) looking for a Gradle or Maven root."""
    path = (start or Path.cwd()).resolve()
    while path != path.parent:
        if any(
            (path / f).exists()
            for f in (
                "settings.gradle",
                "settings.gradle.kts",
                "build.gradle",
                "build.gradle.kts",
                "pom.xml",
            )
        ):
            return path
        path = path.parent
    raise RuntimeError(
        "Could not find a Gradle or Maven project root; "
        "run from inside the project or pass --project-root."
    )


# ---------------------------------------------------------------------------
# Source file discovery
# ---------------------------------------------------------------------------


def get_source_files(
    module_dir: Path, include_nested_modules: bool = False
) -> list[Path]:
    """Return Java sources for one module, or for all modules below it.

    When *include_nested_modules* is true, every directory named ``src`` below
    *module_dir* is treated as a source root. Otherwise, only the direct
    ``module_dir/src`` tree is searched.
    """
    if include_nested_modules:
        source_dirs = sorted(
            path for path in module_dir.rglob("src") if path.is_dir()
        )
        return sorted(
            {
                java_file
                for source_dir in source_dirs
                for java_file in source_dir.rglob("*.java")
            }
        )

    src = module_dir / "src"
    if not src.is_dir():
        return []
    return sorted(src.rglob("*.java"))


# ---------------------------------------------------------------------------
# Annotation parsing (tree-sitter based)
# ---------------------------------------------------------------------------


def _make_parser() -> ts.Parser:
    parser = ts.Parser()
    parser.language = JAVA_LANGUAGE
    return parser


_parser: ts.Parser | None = None


def _get_parser() -> ts.Parser:
    global _parser
    if _parser is None:
        _parser = _make_parser()
    return _parser


def find_suppress_warnings_nodes(root: ts.Node) -> list[ts.Node]:
    results: list[ts.Node] = []
    _collect(root, results)
    return results


def _collect(node: ts.Node, results: list[ts.Node]) -> None:
    if node.type == "annotation":
        name = node.child_by_field_name("name")
        if name and name.text == b"SuppressWarnings":
            results.append(node)
            return
    for child in node.children:
        _collect(child, results)


def annotation_checkers(node: ts.Node) -> list[str]:
    results: list[str] = []
    _collect_strings(node, results)
    return results


def _collect_strings(node: ts.Node, results: list[str]) -> None:
    if node.type == "string_literal" and node.text:
        text = node.text.decode()
        if len(text) >= 2 and text[0] == '"' and text[-1] == '"':
            results.append(text[1:-1])
        return
    for child in node.children:
        _collect_strings(child, results)


def _string_literal_nodes(node: ts.Node) -> list[ts.Node]:
    """Return the string-literal descendants of *node* in source order."""
    results: list[ts.Node] = []

    def collect(current: ts.Node) -> None:
        if current.type == "string_literal":
            results.append(current)
            return
        for child in current.children:
            collect(child)

    collect(node)
    return results


def _rewrite_single_line_annotation(
    annotation_line: str, new_value: str, suppression_names: set[str]
) -> str:
    """Rewrite one suppression annotation while preserving surrounding text exactly."""
    source = annotation_line.encode("utf-8")
    tree = _get_parser().parse(source)
    for node in find_suppress_warnings_nodes(tree.root_node):
        if any(checker in suppression_names for checker in annotation_checkers(node)):
            replacement = f"@SuppressWarnings({new_value})".encode("utf-8")
            rewritten = (
                source[: node.start_byte] + replacement + source[node.end_byte :]
            )
            return rewritten.decode("utf-8")
    raise ValueError("Could not reparse @SuppressWarnings annotation")


def _rewrite_multiline_annotation(
    annotation_text: str, suppression_names: set[str]
) -> tuple[str, set[int]]:
    """Remove selected suppression strings without flattening a multiline annotation.

    A suppression occupying its own line is removed together with that line. For a
    suppression sharing a line with another value, the smallest adjacent comma-delimited
    region is removed. All other annotation text, including braces and indentation, is
    retained.
    """
    source = annotation_text.encode("utf-8")
    deleted_lines: set[int] = set()

    def include_newly_trailing_whitespace(data: bytes, start: int, end: int) -> int:
        """Expand an edit to remove whitespace it would newly leave at line end."""
        if re.match(rb"[ \t]*(?:\r?\n|$)", data[end:]):
            return len(data[:start].rstrip(b" \t"))
        return start

    def target_annotation(data: bytes) -> Optional[ts.Node]:
        """Find the suppression annotation in *data* that still has a target value."""
        tree = _get_parser().parse(data)
        for node in find_suppress_warnings_nodes(tree.root_node):
            if any(
                literal.text
                and literal.text.decode()[1:-1] in suppression_names
                for literal in _string_literal_nodes(node)
            ):
                return node
        return None

    # First remove values that occupy a line by themselves. This produces the natural
    # formatting for the common one-value-per-line style.
    annotation = target_annotation(source)
    if annotation is None:
        raise ValueError("Could not reparse @SuppressWarnings annotation")
    literals = _string_literal_nodes(annotation)
    line_deletions: set[tuple[int, int]] = set()
    for literal in literals:
        if not literal.text or literal.text.decode()[1:-1] not in suppression_names:
            continue
        line_start = source.rfind(b"\n", 0, literal.start_byte) + 1
        newline = source.find(b"\n", literal.end_byte)
        line_end = len(source) if newline == -1 else newline + 1
        without_literal = (
            source[line_start : literal.start_byte]
            + source[literal.end_byte : line_end]
        )
        if re.fullmatch(rb"[ \t]*,?[ \t]*(?:\r?\n)?", without_literal):
            line_deletions.add((line_start, line_end))

    for start, end in sorted(line_deletions, reverse=True):
        deleted_lines.add(source.count(b"\n", 0, start) + 1)
        source = source[:start] + source[end:]

    # Handle values that share a line. Work one contiguous target run at a time and
    # reparse after each edit so byte offsets remain accurate.
    while True:
        annotation = target_annotation(source)
        if annotation is None:
            break
        literals = _string_literal_nodes(annotation)
        is_target = [
            bool(
                literal.text
                and literal.text.decode()[1:-1] in suppression_names
            )
            for literal in literals
        ]
        try:
            run_start = is_target.index(True)
        except ValueError:
            break

        run_end = run_start
        while run_end + 1 < len(literals) and is_target[run_end + 1]:
            run_end += 1

        if run_end + 1 < len(literals):
            start = literals[run_start].start_byte
            next_literal = literals[run_end + 1]
            if literals[run_end].end_point[0] == next_literal.start_point[0]:
                end = next_literal.start_byte
            else:
                separator = source.rfind(
                    b",", literals[run_end].end_byte, next_literal.start_byte
                )
                if separator == -1:
                    raise ValueError("Missing separator between suppression values")
                end = separator + 1
                start = include_newly_trailing_whitespace(source, start, end)
            source = source[:start] + source[end:]
        else:
            previous_literal = literals[run_start - 1]
            if previous_literal.end_point[0] == literals[run_start].start_point[0]:
                start = previous_literal.end_byte
                end = literals[run_end].end_byte
            else:
                separator = source.find(
                    b",", previous_literal.end_byte, literals[run_start].start_byte
                )
                if separator == -1:
                    raise ValueError("Missing separator between suppression values")
                source = source[:separator] + source[separator + 1 :]
                start = literals[run_start].start_byte - 1
                end = literals[run_end].end_byte - 1
                start = include_newly_trailing_whitespace(source, start, end)
            source = source[:start] + source[end:]

    return source.decode("utf-8"), deleted_lines


def find_annotations(
    file_path: Path, checker: str, alt_suppressions: list[str] | None = None
) -> list[Annotation]:
    """Return all Annotation objects in *file_path* containing *checker* or any alt."""
    all_suppressions = set([checker] + (alt_suppressions or []))
    source = file_path.read_bytes()
    lines = source.decode("utf-8").splitlines(keepends=True)

    annotations: list[Annotation] = []
    tree = _get_parser().parse(source)
    for node in find_suppress_warnings_nodes(tree.root_node):
        checkers = annotation_checkers(node)
        if any(c in all_suppressions for c in checkers):
            start_line = node.start_point[0] + 1
            end_line = node.end_point[0] + 1
            annotations.append(
                Annotation(
                    start_line=start_line,
                    end_line=end_line,
                    original_lines=lines[start_line - 1 : end_line],
                    checkers=checkers,
                )
            )
    return annotations


# ---------------------------------------------------------------------------
# File modification
# ---------------------------------------------------------------------------


def build_modified_lines_and_deleted(
    original_lines: list[str],
    annotations_to_remove: list[Annotation],
    checker: str,
    alt_suppressions: list[str] | None = None,
) -> tuple[list[str], set[int]]:
    all_suppressions = set([checker] + (alt_suppressions or []))
    deleted: set[int] = set()
    new_lines = original_lines.copy()

    # Work from the end of the file so replacing an annotation does not invalidate
    # the original line indexes of annotations that precede it.
    for ann in sorted(
        annotations_to_remove, key=lambda item: item.start_line, reverse=True
    ):
        remaining = [c for c in ann.checkers if c not in all_suppressions]

        if not remaining:
            for ln in range(ann.start_line, ann.end_line + 1):
                deleted.add(ln)
            del new_lines[ann.start_line - 1 : ann.end_line]
        elif ann.start_line != ann.end_line:
            original_annotation_lines = original_lines[
                ann.start_line - 1 : ann.end_line
            ]
            rewritten, deleted_relative_lines = _rewrite_multiline_annotation(
                "".join(original_annotation_lines), all_suppressions
            )
            rewritten_lines = rewritten.splitlines(keepends=True)
            removed_line_count = len(original_annotation_lines) - len(rewritten_lines)
            deleted.update(
                ann.start_line + relative_line - 1
                for relative_line in deleted_relative_lines
            )
            unaccounted_lines = removed_line_count - len(deleted_relative_lines)
            if unaccounted_lines > 0:
                deleted.update(
                    range(
                        ann.end_line - unaccounted_lines + 1,
                        ann.end_line + 1,
                    )
                )
            new_lines[ann.start_line - 1 : ann.end_line] = rewritten_lines
        else:
            if len(remaining) == 1:
                new_value = f'"{remaining[0]}"'
            else:
                new_value = "{" + ", ".join(f'"{c}"' for c in remaining) + "}"

            new_lines[ann.start_line - 1] = _rewrite_single_line_annotation(
                original_lines[ann.start_line - 1], new_value, all_suppressions
            )

    return new_lines, deleted


def apply_removals(
    file_path: Path,
    annotations_to_remove: list[Annotation],
    checker: str,
    alt_suppressions: list[str] | None = None,
) -> set[int]:
    if not annotations_to_remove:
        return set()
    original_lines = file_path.read_text(encoding="utf-8").splitlines(keepends=True)
    new_lines, deleted = build_modified_lines_and_deleted(
        original_lines, annotations_to_remove, checker, alt_suppressions
    )
    file_path.write_text("".join(new_lines), encoding="utf-8")
    return deleted


# ---------------------------------------------------------------------------
# Build and error parsing
# ---------------------------------------------------------------------------


def _source_set_compile_task(source_set: str) -> str:
    """Map a Gradle source set directory name to its compile task name."""
    if source_set == "main":
        return "compileJava"
    return f"compile{source_set[0].upper()}{source_set[1:]}Java"


def discover_source_sets(module_dir: Path) -> list[str]:
    """Return source set names under *module_dir*/src/ that contain Java files."""
    src = module_dir / "src"
    if not src.is_dir():
        return ["main"]
    sets = []
    for child in sorted(src.iterdir()):
        if child.is_dir() and any(child.rglob("*.java")):
            sets.append(child.name)
    return sets or ["main"]


def default_gradle_cmd(module_dir: Path, project_root: Path) -> list[str]:
    """Build the default Gradle command that compiles all source sets."""
    module_name = module_dir.relative_to(project_root).as_posix().replace("/", ":")
    gradlew = project_root / "gradlew"
    if not gradlew.exists():
        gradlew = Path("gradle")
    source_sets = discover_source_sets(module_dir)
    tasks = [f":{module_name}:{_source_set_compile_task(s)}" for s in source_sets]
    return [str(gradlew)] + tasks + ["--continue"]


def run_build(
    module_dir: Path,
    project_root: Path,
    build_cmd: list[str] | None = None,
) -> tuple[str, bool]:
    """
    Run a build for the target module.

    *build_cmd* overrides the default Gradle invocation.  The default
    compiles every source set that contains Java files::

        ./gradlew :<module>:compileJava :<module>:compileTestJava ... --continue

    Returns ``(combined_output, success)``.
    """
    cmd = build_cmd or default_gradle_cmd(module_dir, project_root)

    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        cwd=str(project_root),
        check=False,
    )
    return result.stdout + result.stderr, result.returncode == 0


def parse_checker_errors(build_output: str, checker: str) -> list[tuple[str, int]]:
    """
    Extract deduplicated ``(relative_path, line_number)`` pairs for *checker*.

    Matches lines like::

        some/path/File.java:42: error: [NullAway] ...
        some/path/File.java:42: warning: [NullAway] ...
    """
    pattern = re.compile(
        r"^[ \t>]*(.+?\.java):(\d+):\s+(?:error|warning):\s+\["
        + re.escape(checker)
        + r"\]",
        re.MULTILINE,
    )
    seen: set[tuple[str, int]] = set()
    results: list[tuple[str, int]] = []
    for m in pattern.finditer(build_output):
        key = (m.group(1), int(m.group(2)))
        if key not in seen:
            seen.add(key)
            results.append(key)
    return results


# ---------------------------------------------------------------------------
# Line-number mapping
# ---------------------------------------------------------------------------


def build_modified_to_original(total_original_lines: int, deleted: set[int]) -> list[int]:
    return [ln for ln in range(1, total_original_lines + 1) if ln not in deleted]


# ---------------------------------------------------------------------------
# Annotation lookup
# ---------------------------------------------------------------------------


def find_responsible_annotation(
    error_modified_line: int,
    annotations: list[Annotation],
    modified_to_original: list[int],
    lookahead: int = 5,
) -> Optional[Annotation]:
    if not (1 <= error_modified_line <= len(modified_to_original)):
        return None
    original_line = modified_to_original[error_modified_line - 1]

    best: Optional[Annotation] = None
    best_dist = float("inf")
    best_is_preceding = False

    for ann in annotations:
        if ann.start_line <= original_line:
            dist = original_line - ann.start_line
            is_preceding = True
        elif ann.start_line <= original_line + lookahead:
            dist = ann.start_line - original_line
            is_preceding = False
        else:
            continue

        if dist < best_dist or (
            dist == best_dist and is_preceding and not best_is_preceding
        ):
            best = ann
            best_dist = dist
            best_is_preceding = is_preceding

    return best


def mark_all_needed(annotations: list[Annotation]) -> int:
    newly_marked = 0
    for ann in annotations:
        if not ann.needed:
            ann.needed = True
            newly_marked += 1
    return newly_marked


def find_outer_annotation(
    inner_start: int, annotations: list[Annotation]
) -> Optional[Annotation]:
    outer: Optional[Annotation] = None
    for ann in annotations:
        if ann.start_line < inner_start:
            if outer is None or ann.start_line > outer.start_line:
                outer = ann
    return outer


# ---------------------------------------------------------------------------
# Path resolution helpers
# ---------------------------------------------------------------------------


def resolve_error_path(
    err_rel_path: str, project_root: Path, module_dir: Path
) -> Path | None:
    """Try to resolve an error path from build output to an absolute path.

    Gradle error paths may be absolute or relative to the project root, or
    relative to the module directory.  We try several strategies.
    """
    p = Path(err_rel_path)
    if p.is_absolute() and p.exists():
        return p

    candidate = project_root / p
    if candidate.exists():
        return candidate

    candidate = module_dir / p
    if candidate.exists():
        return candidate

    return None


# ---------------------------------------------------------------------------
# Orchestration
# ---------------------------------------------------------------------------

MAX_ITERATIONS = 5


def run(
    module_dir: Path,
    checker: str,
    alt_suppressions: list[str],
    project_root: Path,
    build_cmd: list[str] | None = None,
    max_iterations: int | None = None,
) -> None:
    """
    Discover, strip, build, and iteratively restore needed @SuppressWarnings
    annotations for *checker*. When *module_dir* is the project root, process
    source trees for all modules below it.
    """
    iteration_limit = max_iterations if max_iterations is not None else MAX_ITERATIONS
    if iteration_limit < 1:
        raise ValueError("max_iterations must be a positive integer")
    print(f"Module : {module_dir.relative_to(project_root)}")
    print(f"Checker: {checker}")
    if alt_suppressions:
        print(f"Alt suppressions: {', '.join(alt_suppressions)}")
    print()

    def _print_success(removed_count: int, kept_count: int) -> None:
        print(
            f"\nSuccess! Removed {removed_count} unnecessary "
            f"@SuppressWarnings annotation(s) for {checker!r}"
            + (
                f" (including alternates: {', '.join(alt_suppressions)})"
                if alt_suppressions
                else ""
            )
            + f", kept {kept_count}."
        )

    def _do_build(label: str) -> tuple[str, bool]:
        print(f"\nBuilding ({label}) ...")
        return run_build(module_dir, project_root, build_cmd)

    def _restore_all_and_confirm(remaining_errors: list[tuple[str, int]]) -> None:
        for err_rel_path, _ in remaining_errors:
            resolved = resolve_error_path(err_rel_path, project_root, module_dir)
            if resolved is not None:
                anns = annotations_by_file.get(resolved)
                if anns:
                    mark_all_needed(anns)

        for path, orig_bytes in originals.items():
            path.write_bytes(orig_bytes)

        removed_count = 0
        for path, anns in annotations_by_file.items():
            unneeded = [a for a in anns if not a.needed]
            if not unneeded:
                continue
            removed_count += len(unneeded)
            apply_removals(path, unneeded, checker, alt_suppressions)

        _final_output, final_ok = _do_build("final confirmation")

        if final_ok:
            _print_success(removed_count, total - removed_count)
            return

        print(
            "  Build still fails after restoring all suppression(s) in the "
            "affected file(s) — giving up."
        )
        for path, orig_bytes in originals.items():
            path.write_bytes(orig_bytes)
        sys.exit(1)

    # Step 1: Discover source files.
    print("Discovering source files ...")
    source_files = get_source_files(
        module_dir, include_nested_modules=module_dir == project_root
    )
    print(f"  {len(source_files)} Java source files")

    # Step 2: Locate all @SuppressWarnings(checker) annotations.
    all_suppression_names = [checker] + alt_suppressions
    print(
        f"Scanning for @SuppressWarnings containing: "
        f"{', '.join(repr(s) for s in all_suppression_names)} ..."
    )
    annotations_by_file: dict[Path, list[Annotation]] = {}
    for path in source_files:
        anns = find_annotations(path, checker, alt_suppressions)
        if anns:
            annotations_by_file[path] = anns

    total = sum(len(v) for v in annotations_by_file.values())
    print(f"  Found {total} annotation(s) across {len(annotations_by_file)} file(s)")
    if total == 0:
        print("Nothing to do.")
        return

    # Step 3: Save originals; strip checker from ALL annotations (pass 1).
    print(f"\nPass 1 — removing all suppression(s) for {checker!r} ...")
    originals: dict[Path, bytes] = {p: p.read_bytes() for p in annotations_by_file}
    original_line_counts: dict[Path, int] = {
        p: len(p.read_text(encoding="utf-8").splitlines()) for p in annotations_by_file
    }
    deleted_by_file: dict[Path, set[int]] = {}

    for path, anns in annotations_by_file.items():
        deleted_by_file[path] = apply_removals(path, anns, checker, alt_suppressions)
        print(
            f"  {path.relative_to(project_root)}  ({len(anns)} annotation(s) removed)"
        )

    # Step 4: Build with all suppressions gone.
    build_output, build_ok = _do_build("pass 1")

    if build_ok:
        print(f"  Build passed — all {total} annotation(s) were unnecessary!")
        return

    # Step 5: Map each error back to the responsible annotation.
    errors = parse_checker_errors(build_output, checker)
    print(f"  {len(errors)} unique {checker} error location(s) surfaced")

    unmapped = 0
    for err_rel_path, err_modified_line in errors:
        err_path = resolve_error_path(err_rel_path, project_root, module_dir)
        if err_path is None or err_path not in annotations_by_file:
            unmapped += 1
            continue

        anns = annotations_by_file[err_path]
        mapping = build_modified_to_original(
            original_line_counts[err_path], deleted_by_file[err_path]
        )
        responsible = find_responsible_annotation(err_modified_line, anns, mapping)
        if responsible is not None:
            responsible.needed = True
        else:
            unmapped += 1
            mark_all_needed(anns)
            print(
                f"  Warning: {err_path.relative_to(project_root)}:{err_modified_line} "
                "could not be mapped to an annotation — restoring all suppression(s) "
                "in this file"
            )

    needed = sum(1 for anns in annotations_by_file.values() for a in anns if a.needed)
    if unmapped:
        print(f"  Warning: {unmapped} error(s) could not be mapped to an annotation")
    print(f"  {needed} annotation(s) needed, {total - needed} unnecessary")

    # Steps 6+: Iteratively restore -> remove unneeded -> verify.
    for iteration in range(iteration_limit):
        label = f"Pass {iteration + 2}"
        print(f"\n{label} — applying current needed set ...")

        for path, orig_bytes in originals.items():
            path.write_bytes(orig_bytes)

        removed_count = 0
        deleted_iter: dict[Path, set[int]] = {}

        for path, anns in annotations_by_file.items():
            unneeded = [a for a in anns if not a.needed]
            if not unneeded:
                continue
            removed_count += len(unneeded)
            deleted_iter[path] = apply_removals(
                path, unneeded, checker, alt_suppressions
            )
            kept = len(anns) - len(unneeded)
            print(
                f"  {path.relative_to(project_root)}  "
                f"({len(unneeded)} removed, {kept} kept)"
            )

        needed_count = total - removed_count
        verify_output, verify_ok = _do_build(label)

        if verify_ok:
            _print_success(removed_count, needed_count)
            return

        remaining = parse_checker_errors(verify_output, checker)
        print(
            f"  {len(remaining)} {checker} error(s) remain — expanding needed set ..."
        )

        new_needed = 0
        for err_rel_path, err_line in remaining:
            err_path = resolve_error_path(err_rel_path, project_root, module_dir)
            if err_path is None or err_path not in annotations_by_file:
                continue

            anns = annotations_by_file[err_path]
            deleted = deleted_iter.get(err_path, set())
            mapping = build_modified_to_original(
                original_line_counts[err_path], deleted
            )

            responsible = find_responsible_annotation(err_line, anns, mapping)
            if responsible is None:
                marked = mark_all_needed(anns)
                if marked:
                    new_needed += marked
                    print(
                        f"  Warning: {err_path.relative_to(project_root)}:{err_line} "
                        "could not be mapped to an annotation — restoring all "
                        "suppression(s) in this file"
                    )
                continue

            if not responsible.needed:
                responsible.needed = True
                new_needed += 1
            else:
                candidate = find_outer_annotation(responsible.start_line, anns)
                while candidate is not None and candidate.needed:
                    candidate = find_outer_annotation(candidate.start_line, anns)
                if candidate is not None:
                    candidate.needed = True
                    new_needed += 1

        if new_needed == 0:
            print(
                "  No new annotations identified — restoring all suppression(s) "
                "in file(s) that still have errors ..."
            )
            _restore_all_and_confirm(remaining)
            return

        print(f"  Marked {new_needed} more annotation(s) as needed.")

    print(
        "Max iterations reached — restoring all suppression(s) in file(s) that "
        "still have errors ..."
    )
    _restore_all_and_confirm(remaining)
