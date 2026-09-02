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
Integration tests for core.run().

Each test writes real Java files to a temp directory and mocks only
get_source_files (to avoid filesystem discovery) and run_build (to
avoid a real build invocation).  All annotation parsing and file I/O
run for real.
"""

import tempfile
from pathlib import Path
from unittest import TestCase
from unittest.mock import patch

from suppression_remover.core import run

CHECKER = "NullAway"


def _write(directory: Path, filename: str, content: str) -> Path:
    p = directory / filename
    p.write_text(content, encoding="utf-8")
    return p


def _patch_run(root, java_files, build_side_effects):
    return (
        patch("suppression_remover.core.get_source_files", return_value=java_files),
        patch(
            "suppression_remover.core.run_build",
            side_effect=build_side_effects,
        ),
    )


class TestOrchestrationNoAnnotations(TestCase):
    def test_no_annotations_does_not_call_build(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Foo.java", "class Foo {}\n")

            get_src, mock_build = _patch_run(root, [java_file], [])
            with get_src, mock_build as mb:
                run(root, CHECKER, [], root)
                mb.assert_not_called()


class TestOrchestrationAllUnnecessary(TestCase):
    def test_annotation_removed_when_build_passes(self):
        content = (
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Foo.java", content)

            get_src, mock_build = _patch_run(root, [java_file], [("", True)])
            with get_src, mock_build:
                run(root, CHECKER, [], root)

            result = java_file.read_text(encoding="utf-8")
            self.assertNotIn("@SuppressWarnings", result)
            self.assertIn("public void foo()", result)

    def test_project_wide_run_processes_multiple_modules(self):
        content = (
            "class Example {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            first_source = root / "first" / "src" / "main" / "java"
            second_source = root / "second" / "src" / "test" / "java"
            first_source.mkdir(parents=True)
            second_source.mkdir(parents=True)
            first_java = _write(first_source, "First.java", content)
            second_java = _write(second_source, "Second.java", content)

            with patch(
                "suppression_remover.core.run_build", return_value=("", True)
            ) as mock_build:
                run(root, CHECKER, [], root, build_cmd=["build-everything"])

            self.assertNotIn(
                "@SuppressWarnings", first_java.read_text(encoding="utf-8")
            )
            self.assertNotIn(
                "@SuppressWarnings", second_java.read_text(encoding="utf-8")
            )
            mock_build.assert_called_once_with(root, root, ["build-everything"])


class TestOrchestrationAllNeeded(TestCase):
    def test_all_needed_restores_and_exits(self):
        content = (
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Foo.java", content)
            rel = "Foo.java"

            build_returns = [
                (f"{rel}:2: error: [NullAway] null not allowed\n", False),
                (f"{rel}:2: error: [NullAway] null not allowed\n", False),
                (f"{rel}:2: error: [NullAway] null not allowed\n", False),
            ]
            get_src, mock_build = _patch_run(root, [java_file], build_returns)
            with get_src, mock_build:
                with self.assertRaises(SystemExit):
                    run(root, CHECKER, [], root)

            self.assertEqual(java_file.read_text(encoding="utf-8"), content)


class TestOrchestrationMixed(TestCase):
    def test_needed_kept_unnecessary_removed(self):
        content = (
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void bar() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Foo.java", content)
            rel = "Foo.java"

            build_returns = [
                (f"{rel}:2: error: [NullAway] null\n", False),
                ("", True),
            ]
            get_src, mock_build = _patch_run(root, [java_file], build_returns)
            with get_src, mock_build:
                run(root, CHECKER, [], root)

            result = java_file.read_text(encoding="utf-8")
            ann_lines = [l for l in result.splitlines() if "@SuppressWarnings" in l]
            self.assertEqual(len(ann_lines), 1)
            self.assertIn("NullAway", ann_lines[0])
            self.assertIn("public void bar()", result)


class TestOrchestrationGiveUpRestoresAllAndConfirms(TestCase):
    def test_final_confirmation_succeeds_after_restoring_file(self):
        content = (
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void bar() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Foo.java", content)
            rel = "Foo.java"

            build_returns = [
                (f"{rel}:2: error: [NullAway] null\n", False),
                (f"{rel}:2: error: [NullAway] null\n", False),
                ("", True),
            ]
            get_src, mock_build = _patch_run(root, [java_file], build_returns)
            with get_src, mock_build:
                run(root, CHECKER, [], root)

            self.assertEqual(java_file.read_text(encoding="utf-8"), content)

    def test_final_confirmation_still_fails_restores_originals_and_exits(self):
        content = (
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void bar() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Foo.java", content)
            rel = "Foo.java"

            build_returns = [
                (f"{rel}:2: error: [NullAway] null\n", False),
                (f"{rel}:2: error: [NullAway] null\n", False),
                (f"{rel}:2: error: [NullAway] null\n", False),
            ]
            get_src, mock_build = _patch_run(root, [java_file], build_returns)
            with get_src, mock_build:
                with self.assertRaises(SystemExit):
                    run(root, CHECKER, [], root)

            self.assertEqual(java_file.read_text(encoding="utf-8"), content)


class TestOrchestrationUnmappedErrors(TestCase):
    def test_unmapped_errors_do_not_crash(self):
        content = (
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Foo.java", content)

            unrelated_error = "other/Bar.java:10: error: [NullAway] null\n"
            build_returns = [
                (unrelated_error, False),
                (unrelated_error, False),
                (unrelated_error, False),
            ]
            get_src, mock_build = _patch_run(root, [java_file], build_returns)
            with get_src, mock_build:
                with self.assertRaises(SystemExit):
                    run(root, CHECKER, [], root)


class TestOrchestrationUnmappedLineInFileRestoresAll(TestCase):
    def test_out_of_range_line_restores_all_suppressions_in_file(self):
        content = (
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void bar() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Foo.java", content)
            rel = "Foo.java"

            build_returns = [
                (f"{rel}:100: error: [NullAway] null\n", False),
                ("", True),
            ]
            get_src, mock_build = _patch_run(root, [java_file], build_returns)
            with get_src, mock_build:
                run(root, CHECKER, [], root)

            self.assertEqual(java_file.read_text(encoding="utf-8"), content)


class TestOrchestrationOuterAnnotation(TestCase):
    def test_outer_annotation_marked_needed(self):
        content = (
            "class Outer {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    class Inner {\n"
            '        @SuppressWarnings("NullAway")\n'
            "        public void foo() {}\n"
            "    }\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Outer.java", content)
            rel = "Outer.java"

            build_returns = [
                (f"{rel}:3: error: [NullAway] null\n", False),
                (f"{rel}:4: error: [NullAway] null\n", False),
                ("", True),
            ]
            get_src, mock_build = _patch_run(root, [java_file], build_returns)
            with get_src, mock_build:
                run(root, CHECKER, [], root)

            result = java_file.read_text(encoding="utf-8")
            self.assertEqual(result.count('@SuppressWarnings("NullAway")'), 2)


class TestOrchestrationMaxIterations(TestCase):
    def test_max_iterations_restores_all_and_confirms(self):
        content = (
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void bar() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Foo.java", content)
            rel = "Foo.java"

            build_returns = [
                (f"{rel}:2: error: [NullAway] null\n", False),
                (f"{rel}:4: error: [NullAway] null\n", False),
                ("", True),
            ]
            get_src, mock_build = _patch_run(root, [java_file], build_returns)
            with get_src, mock_build:
                run(root, CHECKER, [], root, max_iterations=1)

            self.assertEqual(java_file.read_text(encoding="utf-8"), content)

    def test_max_iterations_must_be_positive(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            with self.assertRaisesRegex(ValueError, "positive integer"):
                run(root, CHECKER, [], root, max_iterations=0)


class TestOrchestrationMaxIterationsStillFails(TestCase):
    def test_max_iterations_confirmation_fails_restores_and_exits(self):
        content = (
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void bar() {}\n"
            "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Foo.java", content)
            rel = "Foo.java"

            build_returns = [
                (f"{rel}:2: error: [NullAway] null\n", False),
                (f"{rel}:4: error: [NullAway] null\n", False),
                (f"{rel}:4: error: [NullAway] null\n", False),
            ]
            get_src, mock_build = _patch_run(root, [java_file], build_returns)
            with get_src, mock_build:
                with patch("suppression_remover.core.MAX_ITERATIONS", 1):
                    with self.assertRaises(SystemExit):
                        run(root, CHECKER, [], root)

            self.assertEqual(java_file.read_text(encoding="utf-8"), content)


class TestOrchestrationCommentsSafe(TestCase):
    def test_comments_and_strings_not_modified(self):
        line_comment = '    // @SuppressWarnings("NullAway")\n'
        block_comment = '    /* @SuppressWarnings("NullAway") */\n'
        string_literal = '    String s = "@SuppressWarnings(\\"NullAway\\")";\n'
        real_ann = '    @SuppressWarnings("NullAway")\n'
        method = "    public void foo() {}\n"

        content = (
            "class Foo {\n"
            + line_comment
            + block_comment
            + string_literal
            + real_ann
            + method
            + "}\n"
        )
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            java_file = _write(root, "Foo.java", content)

            get_src, mock_build = _patch_run(root, [java_file], [("", True)])
            with get_src, mock_build:
                run(root, CHECKER, [], root)

            result_lines = java_file.read_text(encoding="utf-8").splitlines(
                keepends=True
            )
            self.assertIn(line_comment, result_lines)
            self.assertIn(block_comment, result_lines)
            self.assertIn(string_literal, result_lines)
            self.assertNotIn(real_ann, result_lines)
            self.assertIn(method, result_lines)
