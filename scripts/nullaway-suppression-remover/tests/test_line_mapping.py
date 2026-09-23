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

from suppression_remover.core import (
    Annotation,
    build_modified_to_original,
    find_outer_annotation,
    find_responsible_annotation,
    mark_all_needed,
)


def _ann(start, end=None, checkers=None):
    if end is None:
        end = start
    return Annotation(
        start_line=start,
        end_line=end,
        original_lines=[],
        checkers=checkers or ["NullAway"],
    )


class TestBuildModifiedToOriginal(TestCase):
    def test_no_deletions(self):
        result = build_modified_to_original(5, set())
        self.assertEqual(result, [1, 2, 3, 4, 5])

    def test_delete_first_line(self):
        result = build_modified_to_original(5, {1})
        self.assertEqual(result, [2, 3, 4, 5])

    def test_delete_middle_line(self):
        result = build_modified_to_original(5, {3})
        self.assertEqual(result, [1, 2, 4, 5])

    def test_delete_multiple_lines(self):
        result = build_modified_to_original(5, {2, 4})
        self.assertEqual(result, [1, 3, 5])

    def test_empty_file(self):
        result = build_modified_to_original(0, set())
        self.assertEqual(result, [])

    def test_all_deleted(self):
        result = build_modified_to_original(3, {1, 2, 3})
        self.assertEqual(result, [])


class TestFindResponsibleAnnotation(TestCase):
    def _mapping(self, total, deleted=None):
        return build_modified_to_original(total, deleted or set())

    def test_annotation_immediately_above(self):
        anns = [_ann(5)]
        mapping = self._mapping(10)
        result = find_responsible_annotation(6, anns, mapping)
        self.assertIs(result, anns[0])

    def test_closest_preceding_wins(self):
        anns = [_ann(2), _ann(8)]
        mapping = self._mapping(15)
        result = find_responsible_annotation(10, anns, mapping)
        self.assertIs(result, anns[1])

    def test_following_annotation_within_lookahead(self):
        anns = [_ann(10)]
        mapping = self._mapping(20)
        result = find_responsible_annotation(7, anns, mapping, lookahead=5)
        self.assertIs(result, anns[0])

    def test_following_annotation_outside_lookahead_ignored(self):
        anns = [_ann(15)]
        mapping = self._mapping(20)
        result = find_responsible_annotation(5, anns, mapping, lookahead=5)
        self.assertIsNone(result)

    def test_preceding_beats_equidistant_following(self):
        anns = [_ann(5), _ann(15)]
        mapping = self._mapping(20)
        result = find_responsible_annotation(10, anns, mapping, lookahead=10)
        self.assertIs(result, anns[0])

    def test_out_of_range_line_returns_none(self):
        anns = [_ann(5)]
        mapping = self._mapping(10)
        self.assertIsNone(find_responsible_annotation(0, anns, mapping))
        self.assertIsNone(find_responsible_annotation(11, anns, mapping))

    def test_empty_annotations_returns_none(self):
        mapping = self._mapping(10)
        self.assertIsNone(find_responsible_annotation(5, [], mapping))

    def test_accounts_for_deleted_lines(self):
        anns = [_ann(5)]
        mapping = self._mapping(10, deleted={1})
        result = find_responsible_annotation(5, anns, mapping)
        self.assertIs(result, anns[0])

    def test_annotation_on_same_line_as_error(self):
        anns = [_ann(3)]
        mapping = self._mapping(10)
        result = find_responsible_annotation(3, anns, mapping)
        self.assertIs(result, anns[0])


class TestFindOuterAnnotation(TestCase):
    def test_basic_outer(self):
        inner = _ann(10)
        outer = _ann(5)
        result = find_outer_annotation(inner.start_line, [inner, outer])
        self.assertIs(result, outer)

    def test_closest_outer_wins(self):
        anns = [_ann(2), _ann(7), _ann(15)]
        result = find_outer_annotation(15, anns)
        self.assertIs(result, anns[1])

    def test_no_outer_returns_none(self):
        anns = [_ann(10)]
        result = find_outer_annotation(10, anns)
        self.assertIsNone(result)

    def test_equal_start_line_not_counted_as_outer(self):
        anns = [_ann(5), _ann(10)]
        result = find_outer_annotation(5, anns)
        self.assertIsNone(result)

    def test_empty_annotations(self):
        self.assertIsNone(find_outer_annotation(5, []))


class TestMarkAllNeeded(TestCase):
    def test_marks_all_unneeded_annotations(self):
        anns = [_ann(2), _ann(5), _ann(9)]
        marked = mark_all_needed(anns)
        self.assertEqual(marked, 3)
        self.assertTrue(all(a.needed for a in anns))

    def test_already_needed_not_recounted(self):
        anns = [_ann(2), _ann(5)]
        anns[0].needed = True
        marked = mark_all_needed(anns)
        self.assertEqual(marked, 1)
        self.assertTrue(all(a.needed for a in anns))

    def test_all_already_needed_returns_zero(self):
        anns = [_ann(2), _ann(5)]
        for a in anns:
            a.needed = True
        marked = mark_all_needed(anns)
        self.assertEqual(marked, 0)

    def test_empty_list_returns_zero(self):
        self.assertEqual(mark_all_needed([]), 0)
