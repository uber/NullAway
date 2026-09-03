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

import tempfile
from pathlib import Path
from unittest import TestCase

from suppression_remover.core import Annotation, find_annotations


def _java_file(content: str) -> Path:
    f = tempfile.NamedTemporaryFile(
        mode="w", suffix=".java", delete=False, encoding="utf-8"
    )
    f.write(content)
    f.flush()
    f.close()
    return Path(f.name)


class TestFindAnnotationsSingleChecker(TestCase):
    def test_basic_single_checker(self):
        path = _java_file(
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            anns = find_annotations(path, "NullAway")
            self.assertEqual(len(anns), 1)
            self.assertEqual(anns[0].checkers, ["NullAway"])
            self.assertEqual(anns[0].start_line, 2)
            self.assertEqual(anns[0].end_line, 2)
            self.assertFalse(anns[0].needed)
        finally:
            path.unlink()

    def test_multiple_annotations_in_file(self):
        path = _java_file(
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void bar() {}\n"
            "}\n"
        )
        try:
            anns = find_annotations(path, "NullAway")
            self.assertEqual(len(anns), 2)
            self.assertEqual(anns[0].start_line, 2)
            self.assertEqual(anns[1].start_line, 4)
        finally:
            path.unlink()

    def test_different_checker_not_matched(self):
        path = _java_file(
            "class Foo {\n"
            '    @SuppressWarnings("unchecked")\n'
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            self.assertEqual(find_annotations(path, "NullAway"), [])
        finally:
            path.unlink()

    def test_no_annotations(self):
        path = _java_file("class Foo {}\n")
        try:
            self.assertEqual(find_annotations(path, "NullAway"), [])
        finally:
            path.unlink()


class TestFindAnnotationsMultipleCheckers(TestCase):
    def test_multi_checker_annotation_matched(self):
        path = _java_file(
            "class Foo {\n"
            '    @SuppressWarnings({"NullAway", "unchecked"})\n'
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            anns = find_annotations(path, "NullAway")
            self.assertEqual(len(anns), 1)
            self.assertIn("NullAway", anns[0].checkers)
            self.assertIn("unchecked", anns[0].checkers)
        finally:
            path.unlink()

    def test_multi_checker_annotation_not_matched(self):
        path = _java_file(
            "class Foo {\n"
            '    @SuppressWarnings({"deprecation", "unchecked"})\n'
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            self.assertEqual(find_annotations(path, "NullAway"), [])
        finally:
            path.unlink()


class TestFindAnnotationsAltSuppression(TestCase):
    def test_alt_suppression_matched(self):
        path = _java_file(
            "class Foo {\n"
            '    @SuppressWarnings("NullAway.Init")\n'
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            anns = find_annotations(
                path, "NullAway", alt_suppressions=["NullAway.Init"]
            )
            self.assertEqual(len(anns), 1)
            self.assertEqual(anns[0].checkers, ["NullAway.Init"])
        finally:
            path.unlink()

    def test_alt_suppression_not_matched_without_flag(self):
        path = _java_file(
            "class Foo {\n"
            '    @SuppressWarnings("NullAway.Init")\n'
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            self.assertEqual(find_annotations(path, "NullAway"), [])
        finally:
            path.unlink()


class TestFindAnnotationsMultiline(TestCase):
    def test_multiline_annotation(self):
        path = _java_file(
            "class Foo {\n"
            "    @SuppressWarnings({\n"
            '        "NullAway",\n'
            '        "unchecked"\n'
            "    })\n"
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            anns = find_annotations(path, "NullAway")
            self.assertEqual(len(anns), 1)
            self.assertEqual(anns[0].start_line, 2)
            self.assertEqual(anns[0].end_line, 5)
            self.assertIn("NullAway", anns[0].checkers)
            self.assertIn("unchecked", anns[0].checkers)
        finally:
            path.unlink()


class TestFindAnnotationsIgnoresFalsePositives(TestCase):
    def test_ignores_string_literal(self):
        path = _java_file(
            "class Foo {\n"
            '    String msg = "@SuppressWarnings(\\"NullAway\\")";\n'
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            self.assertEqual(find_annotations(path, "NullAway"), [])
        finally:
            path.unlink()

    def test_ignores_line_comment(self):
        path = _java_file(
            "class Foo {\n"
            '    // @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            self.assertEqual(find_annotations(path, "NullAway"), [])
        finally:
            path.unlink()

    def test_ignores_block_comment(self):
        path = _java_file(
            "class Foo {\n"
            "    /*\n"
            '     * @SuppressWarnings("NullAway")\n'
            "     */\n"
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            self.assertEqual(find_annotations(path, "NullAway"), [])
        finally:
            path.unlink()

    def test_ignores_javadoc_comment(self):
        path = _java_file(
            "class Foo {\n"
            "    /**\n"
            '     * Use {@code @SuppressWarnings("NullAway")} to suppress.\n'
            "     */\n"
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            self.assertEqual(find_annotations(path, "NullAway"), [])
        finally:
            path.unlink()

    def test_real_annotation_present_alongside_comment(self):
        path = _java_file(
            "class Foo {\n"
            '    // @SuppressWarnings("NullAway") -- commented out\n'
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            "}\n"
        )
        try:
            anns = find_annotations(path, "NullAway")
            self.assertEqual(len(anns), 1)
            self.assertEqual(anns[0].start_line, 3)
        finally:
            path.unlink()
