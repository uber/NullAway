package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A rewrite of a seed that Java gives no meaning to, so NullAway must report the same warnings on
 * the rewritten seed.
 *
 * <p>Every rewrite keeps each line of every unit on the same line number, so warnings compare by
 * unit, line, and message. A rewrite may add a unit; a warning in it has no counterpart and shows
 * as a divergence.
 */
interface Relation {

  /** Returns the name the report and the counters use. */
  String name();

  /**
   * Returns the rewritten variants of {@code seed}, most rewritten first, or an empty list if the
   * relation does not apply. The oracle compares the first variant javac accepts.
   */
  ImmutableList<ImmutableList<Source>> variants(Seed seed);

  /**
   * One logical change to one unit: the text edits that make it, such as the opening and closing
   * parenthesis around one expression.
   *
   * @param unit index of the unit in the seed's sources
   * @param edits the edits, with offsets into that unit's text
   */
  record Change(int unit, ImmutableList<TextEdit> edits) {

    static Change of(int unit, TextEdit... edits) {
      return new Change(unit, ImmutableList.copyOf(edits));
    }

    int start() {
      return edits.stream().mapToInt(TextEdit::start).min().orElseThrow();
    }

    int end() {
      return edits.stream().mapToInt(TextEdit::end).max().orElseThrow();
    }

    /** Returns whether this change replaces text, as opposed to only inserting it. */
    boolean replaces() {
      return edits.stream().anyMatch(e -> e.end() > e.start());
    }
  }

  /**
   * Returns the variant with every change applied, followed by up to {@code singles} variants with
   * one change each, for the case where javac rejects the first. A change that lies inside a
   * replacement made by another is dropped, since the replacement already carries the original text
   * of the span, and so is a change at a position javac did not record.
   */
  static ImmutableList<ImmutableList<Source>> variants(
      Seed seed, List<Change> changes, int singles) {
    // javac records no position for some trees, such as parts of a compact constructor.
    List<Change> sorted = new ArrayList<>();
    for (Change c : changes) {
      if (c.edits().stream().allMatch(e -> e.start() >= 0 && e.end() >= e.start())) {
        sorted.add(c);
      }
    }
    sorted.sort(
        Comparator.comparingInt(Change::unit)
            .thenComparingInt(Change::start)
            .thenComparing(Comparator.comparingInt(Change::end).reversed()));
    List<Change> kept = new ArrayList<>();
    for (Change c : sorted) {
      boolean inside =
          kept.stream()
              .anyMatch(
                  k ->
                      k.unit() == c.unit()
                          && k.replaces()
                          && k.start() <= c.start()
                          && c.end() <= k.end());
      if (!inside) {
        kept.add(c);
      }
    }
    if (kept.isEmpty()) {
      return ImmutableList.of();
    }
    ImmutableList.Builder<ImmutableList<Source>> result = ImmutableList.builder();
    addIfLinesKept(result, seed, apply(seed, kept));
    if (kept.size() > 1) {
      kept.stream()
          .limit(singles)
          .forEach(c -> addIfLinesKept(result, seed, apply(seed, List.of(c))));
    }
    return result.build();
  }

  /**
   * Adds {@code variant} unless one of its units has a different number of lines than in {@code
   * seed}, which would move warnings to other lines and make them look like divergences.
   */
  private static void addIfLinesKept(
      ImmutableList.Builder<ImmutableList<Source>> result,
      Seed seed,
      ImmutableList<Source> variant) {
    for (int i = 0; i < seed.sources().size(); i++) {
      if (lines(seed.sources().get(i)) != lines(variant.get(i))) {
        return;
      }
    }
    result.add(variant);
  }

  private static long lines(Source source) {
    return source.text().chars().filter(c -> c == '\n').count();
  }

  private static ImmutableList<Source> apply(Seed seed, List<Change> changes) {
    ImmutableList.Builder<Source> result = ImmutableList.builder();
    for (int i = 0; i < seed.sources().size(); i++) {
      int unit = i;
      List<TextEdit> edits =
          changes.stream().filter(c -> c.unit() == unit).flatMap(c -> c.edits().stream()).toList();
      Source source = seed.sources().get(i);
      result.add(source.withText(TextEdit.apply(source.text(), edits)));
    }
    return result.build();
  }
}
