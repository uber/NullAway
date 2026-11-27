package com.uber.nullaway.oracles;

import java.util.Comparator;
import java.util.List;

/**
 * Replaces the characters in {@code [start, end)} of a source text with {@code replacement}. An
 * edit with {@code start == end} inserts.
 *
 * @param start offset of the first character replaced
 * @param end offset just past the last character replaced
 * @param replacement the text put in their place
 */
record TextEdit(int start, int end, String replacement) {

  /**
   * Applies {@code edits} to {@code text}. The edits must not overlap, though several insertions
   * may share an offset; offsets refer to {@code text} as it was before any edit.
   */
  static String apply(String text, List<TextEdit> edits) {
    StringBuilder result = new StringBuilder(text);
    edits.stream()
        .sorted(Comparator.comparingInt(TextEdit::start).thenComparingInt(TextEdit::end).reversed())
        .forEach(e -> result.replace(e.start(), e.end(), e.replacement()));
    return result.toString();
  }
}
