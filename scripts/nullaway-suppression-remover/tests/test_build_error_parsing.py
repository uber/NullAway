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

from suppression_remover.core import parse_checker_errors


class TestParseCheckerErrors(TestCase):
    def _output(self, *lines):
        return "\n".join(lines)

    def test_single_error(self):
        output = self._output(
            "src/main/java/com/example/Foo.java:42: error: [NullAway] message here"
        )
        errors = parse_checker_errors(output, "NullAway")
        self.assertEqual(errors, [("src/main/java/com/example/Foo.java", 42)])

    def test_single_warning(self):
        output = self._output(
            "src/main/java/com/example/Foo.java:10: warning: [NullAway] message"
        )
        errors = parse_checker_errors(output, "NullAway")
        self.assertEqual(errors, [("src/main/java/com/example/Foo.java", 10)])

    def test_multiple_files(self):
        output = self._output(
            "com/Foo.java:1: error: [NullAway] msg",
            "com/Bar.java:2: error: [NullAway] msg",
        )
        errors = parse_checker_errors(output, "NullAway")
        self.assertEqual(len(errors), 2)
        self.assertIn(("com/Foo.java", 1), errors)
        self.assertIn(("com/Bar.java", 2), errors)

    def test_deduplication(self):
        output = self._output(
            "com/Foo.java:5: error: [NullAway] first",
            "com/Foo.java:5: error: [NullAway] second",
        )
        errors = parse_checker_errors(output, "NullAway")
        self.assertEqual(errors, [("com/Foo.java", 5)])

    def test_different_checker_not_matched(self):
        output = self._output(
            "com/Foo.java:1: error: [unchecked] msg",
        )
        errors = parse_checker_errors(output, "NullAway")
        self.assertEqual(errors, [])

    def test_no_errors(self):
        output = "Build successful."
        errors = parse_checker_errors(output, "NullAway")
        self.assertEqual(errors, [])

    def test_checker_with_special_regex_chars(self):
        output = self._output(
            "com/Foo.java:7: error: [NullAway.Init] msg",
        )
        errors = parse_checker_errors(output, "NullAway.Init")
        self.assertEqual(errors, [("com/Foo.java", 7)])

    def test_does_not_match_partial_checker_name(self):
        output = self._output(
            "com/Foo.java:1: error: [NullAwayExtra] msg",
        )
        errors = parse_checker_errors(output, "NullAway")
        self.assertEqual(errors, [])

    def test_preserves_order(self):
        output = self._output(
            "com/Foo.java:30: error: [NullAway] msg",
            "com/Foo.java:10: error: [NullAway] msg",
            "com/Bar.java:5: error: [NullAway] msg",
        )
        errors = parse_checker_errors(output, "NullAway")
        self.assertEqual(
            errors,
            [("com/Foo.java", 30), ("com/Foo.java", 10), ("com/Bar.java", 5)],
        )
