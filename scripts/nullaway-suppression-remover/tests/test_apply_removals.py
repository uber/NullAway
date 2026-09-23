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

from suppression_remover.core import Annotation, apply_removals


def _java_file(content: str) -> Path:
    f = tempfile.NamedTemporaryFile(
        mode="w", suffix=".java", delete=False, encoding="utf-8"
    )
    f.write(content)
    f.flush()
    f.close()
    return Path(f.name)


def _ann(start, end, checkers, lines_text):
    return Annotation(
        start_line=start,
        end_line=end,
        original_lines=lines_text.splitlines(keepends=True),
        checkers=checkers,
    )


class TestApplyRemovals(TestCase):
    def test_empty_annotations_returns_empty_set_and_file_unchanged(self):
        content = '    @SuppressWarnings("NullAway")\n    public void foo() {}\n'
        path = _java_file(content)
        try:
            deleted = apply_removals(path, [], "NullAway")
            self.assertEqual(deleted, set())
            self.assertEqual(path.read_text(encoding="utf-8"), content)
        finally:
            path.unlink()

    def test_sole_checker_deletes_line(self):
        content = '    @SuppressWarnings("NullAway")\n    public void foo() {}\n'
        path = _java_file(content)
        lines = content.splitlines(keepends=True)
        ann = _ann(1, 1, ["NullAway"], lines[0])
        try:
            deleted = apply_removals(path, [ann], "NullAway")
            self.assertEqual(deleted, {1})
            self.assertEqual(
                path.read_text(encoding="utf-8"), "    public void foo() {}\n"
            )
        finally:
            path.unlink()

    def test_multi_checker_replaces_line_strips_target(self):
        content = '    @SuppressWarnings({"NullAway", "unchecked"})\n    public void foo() {}\n'
        path = _java_file(content)
        lines = content.splitlines(keepends=True)
        ann = _ann(1, 1, ["NullAway", "unchecked"], lines[0])
        try:
            deleted = apply_removals(path, [ann], "NullAway")
            self.assertEqual(deleted, set())
            result = path.read_text(encoding="utf-8")
            self.assertIn('@SuppressWarnings("unchecked")', result)
            self.assertNotIn('"NullAway"', result)
        finally:
            path.unlink()
