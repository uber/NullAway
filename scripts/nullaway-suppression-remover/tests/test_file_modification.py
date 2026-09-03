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

from unittest import TestCase

from suppression_remover.core import Annotation, build_modified_lines_and_deleted


def _lines(text):
    return text.splitlines(keepends=True)


def _ann(start, end, checkers, lines_text):
    raw = lines_text.splitlines(keepends=True)
    return Annotation(
        start_line=start,
        end_line=end,
        original_lines=raw,
        checkers=checkers,
    )


class TestBuildModifiedLinesAndDeleted(TestCase):
    def test_sole_checker_deletes_line(self):
        src = '    @SuppressWarnings("NullAway")\n    public void foo() {}\n'
        lines = _lines(src)
        ann = _ann(1, 1, ["NullAway"], lines[0])
        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertEqual(deleted, {1})
        self.assertEqual(new_lines, ["    public void foo() {}\n"])

    def test_multiple_checkers_keeps_others(self):
        src = '    @SuppressWarnings({"NullAway", "unchecked"})\n    public void foo() {}\n'
        lines = _lines(src)
        ann = _ann(1, 1, ["NullAway", "unchecked"], lines[0])
        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertEqual(deleted, set())
        self.assertIn('@SuppressWarnings("unchecked")', new_lines[0])
        self.assertEqual(new_lines[1], "    public void foo() {}\n")

    def test_multiple_checkers_preserves_indentation(self):
        src = '        @SuppressWarnings({"NullAway", "unchecked"})\n'
        lines = _lines(src)
        ann = _ann(1, 1, ["NullAway", "unchecked"], lines[0])
        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertTrue(new_lines[0].startswith("        "))

    def test_multiple_checkers_preserves_exact_leading_and_trailing_whitespace(self):
        src = '\t  @SuppressWarnings({"NullAway", "removal"})  \r\n'
        lines = _lines(src)
        ann = _ann(1, 1, ["NullAway", "removal"], lines[0])

        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")

        self.assertEqual(deleted, set())
        self.assertEqual(new_lines, ['\t  @SuppressWarnings("removal")  \r\n'])

    def test_three_checkers_keeps_two(self):
        src = '    @SuppressWarnings({"NullAway", "unchecked", "deprecation"})\n'
        lines = _lines(src)
        ann = _ann(1, 1, ["NullAway", "unchecked", "deprecation"], lines[0])
        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertIn('"unchecked"', new_lines[0])
        self.assertIn('"deprecation"', new_lines[0])
        self.assertNotIn('"NullAway"', new_lines[0])

    def test_alt_suppression_removed(self):
        src = '    @SuppressWarnings("NullAway.Init")\n'
        lines = _lines(src)
        ann = _ann(1, 1, ["NullAway.Init"], lines[0])
        new_lines, deleted = build_modified_lines_and_deleted(
            lines, [ann], "NullAway", alt_suppressions=["NullAway.Init"]
        )
        self.assertEqual(deleted, {1})
        self.assertEqual(new_lines, [])

    def test_multiline_annotation_deleted(self):
        src = (
            "    @SuppressWarnings({\n"
            '        "NullAway"\n'
            "    })\n"
            "    public void foo() {}\n"
        )
        lines = _lines(src)
        ann = _ann(1, 3, ["NullAway"], "".join(lines[:3]))
        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertEqual(deleted, {1, 2, 3})
        self.assertEqual(new_lines, ["    public void foo() {}\n"])

    def test_multiline_annotation_preserves_layout_when_partial(self):
        src = (
            "    @SuppressWarnings({\n"
            '        "NullAway",\n'
            '        "unchecked"\n'
            "    })\n"
        )
        lines = _lines(src)
        ann = _ann(1, 4, ["NullAway", "unchecked"], "".join(lines))
        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertEqual(deleted, {2})
        self.assertEqual(
            new_lines,
            _lines(
                "    @SuppressWarnings({\n"
                '        "unchecked"\n'
                "    })\n"
            ),
        )

    def test_multiline_annotation_preserves_layout_when_removing_last(self):
        src = (
            "    @SuppressWarnings({\n"
            '        "unchecked",\n'
            '        "NullAway"\n'
            "    })\n"
        )
        lines = _lines(src)
        ann = _ann(1, 4, ["unchecked", "NullAway"], "".join(lines))
        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertEqual(deleted, {3})
        self.assertEqual(
            new_lines,
            _lines(
                "    @SuppressWarnings({\n"
                '        "unchecked",\n'
                "    })\n"
            ),
        )

    def test_multiline_annotation_preserves_layout_for_inline_values(self):
        src = (
            "    @SuppressWarnings({\n"
            '        "NullAway", "unchecked", "deprecation"\n'
            "    })\n"
        )
        lines = _lines(src)
        ann = _ann(
            1,
            3,
            ["NullAway", "unchecked", "deprecation"],
            "".join(lines),
        )
        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertEqual(deleted, set())
        self.assertEqual(
            new_lines,
            _lines(
                "    @SuppressWarnings({\n"
                '        "unchecked", "deprecation"\n'
                "    })\n"
            ),
        )

    def test_multiline_annotation_preserves_newline_after_removed_value(self):
        src = (
            '    @SuppressWarnings({  "NullAway",  \n'
            '        "unchecked",\n'
            '        "deprecation"})\n'
        )
        lines = _lines(src)
        ann = _ann(
            1,
            3,
            ["NullAway", "unchecked", "deprecation"],
            "".join(lines),
        )
        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertEqual(deleted, set())
        self.assertEqual(
            new_lines,
            _lines(
                "    @SuppressWarnings({  \n"
                '        "unchecked",\n'
                '        "deprecation"})\n'
            ),
        )

    def test_multiline_annotation_does_not_introduce_trailing_whitespace(self):
        src = (
            '    @SuppressWarnings({\t"NullAway",\r\n'
            '        "unchecked"  \r\n'
            "    })\r\n"
        )
        lines = _lines(src)
        ann = _ann(1, 3, ["NullAway", "unchecked"], "".join(lines))
        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertEqual(deleted, set())
        self.assertEqual(
            new_lines,
            _lines(
                "    @SuppressWarnings({\r\n"
                '        "unchecked"  \r\n'
                "    })\r\n"
            ),
        )

    def test_no_annotations_to_remove(self):
        src = "    public void foo() {}\n"
        lines = _lines(src)
        new_lines, deleted = build_modified_lines_and_deleted(lines, [], "NullAway")
        self.assertEqual(deleted, set())
        self.assertEqual(new_lines, lines)

    def test_removes_only_target_checker_from_pair(self):
        src = '    @SuppressWarnings({"unchecked", "NullAway"})\n'
        lines = _lines(src)
        ann = _ann(1, 1, ["unchecked", "NullAway"], lines[0])
        new_lines, _ = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertIn('"unchecked"', new_lines[0])
        self.assertNotIn('"NullAway"', new_lines[0])

    def test_line_numbers_outside_annotation_untouched(self):
        src = (
            "class Foo {\n"
            '    @SuppressWarnings("NullAway")\n'
            "    public void foo() {}\n"
            "}\n"
        )
        lines = _lines(src)
        ann = _ann(2, 2, ["NullAway"], lines[1])
        new_lines, deleted = build_modified_lines_and_deleted(lines, [ann], "NullAway")
        self.assertEqual(new_lines[0], "class Foo {\n")
        self.assertEqual(new_lines[1], "    public void foo() {}\n")
        self.assertEqual(new_lines[2], "}\n")
