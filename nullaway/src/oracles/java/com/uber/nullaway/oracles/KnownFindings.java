package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The findings a person has already triaged, read from a checked-in file, so that a run can report
 * what is new apart from what is known.
 *
 * <p>Each line of the file is one entry with tab-separated fields; blank lines and lines starting
 * with {@code #} are ignored. An entry is one of:
 *
 * <pre>
 * crash       SIGNATURE                          VERDICT  [NOTE]
 * divergence  RELATION  SEED  SIDE  TEMPLATE     VERDICT  [NOTE]
 * growth      SHAPE  NULLNESS  USE               VERDICT  [NOTE]
 * </pre>
 *
 * <p>{@code SIGNATURE} is a {@link CrashSignature}. A divergence group is keyed by the relation
 * name, the seed name or {@code *} for every seed, the side that reported the warning ({@code left}
 * for the seed, {@code right} for the rewrite), and the warning's message template: the message
 * with the parts a rewrite may change masked, as {@code new-findings.tsv} prints it. A growth
 * finding is keyed by the three words of a {@link BoundGraphGenerator.Family#name()}, any of which
 * may be {@code *} to match every value, and the first entry that matches wins. {@code VERDICT} is
 * an issue number such as {@code #1993}, {@code pending} for a defect not filed yet, or {@code
 * noise} for a finding the harness or a test fixture causes rather than NullAway. {@code NOTE} is
 * free text, and {@code pending} and {@code noise} require one.
 */
final class KnownFindings {

  private static final Pattern ISSUE = Pattern.compile("#\\d+");

  /**
   * What a person decided about a finding.
   *
   * @param verdict an issue number such as {@code #1993}, {@code pending}, or {@code noise}
   * @param note free text, empty for an issue number given without one
   */
  record Verdict(String verdict, String note) {

    /** Returns the verdict and the note, for the report. */
    @Override
    public String toString() {
      return note.isEmpty() ? verdict : verdict + " (" + note + ")";
    }
  }

  /**
   * One line of the file.
   *
   * @param key the fields before the verdict, joined with tabs; for a divergence, a seed of {@code
   *     *} matches every seed
   * @param verdict the verdict and the note
   * @param line the 1-based line number, for the report
   */
  record Entry(String key, Verdict verdict, int line) {}

  private final ImmutableList<Entry> entries;
  private final Set<Entry> matched = new LinkedHashSet<>();

  private KnownFindings(ImmutableList<Entry> entries) {
    this.entries = entries;
  }

  /** Reads the entries of {@code file}, or returns no entries if {@code file} does not exist. */
  static KnownFindings read(Path file) throws IOException {
    return Files.exists(file) ? parse(Files.readAllLines(file)) : parse(List.of());
  }

  /**
   * Parses the lines of a file in the format the class comment describes.
   *
   * @throws IllegalArgumentException if a line has the wrong number of fields, an unknown kind or
   *     side, an unknown verdict, a {@code pending} or {@code noise} verdict with no note, or the
   *     key of an earlier line
   */
  static KnownFindings parse(List<String> lines) {
    ImmutableList.Builder<Entry> entries = ImmutableList.builder();
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      int number = i + 1;
      List<String> fields = List.of(line.split("\t", -1));
      int keyFields =
          switch (fields.get(0)) {
            case "crash" -> 2;
            case "divergence" -> 5;
            case "growth" -> 4;
            default -> throw invalid(number, "unknown kind '" + fields.get(0) + "'");
          };
      if (fields.size() < keyFields + 1 || fields.size() > keyFields + 2) {
        throw invalid(
            number,
            fields.get(0)
                + " takes "
                + (keyFields + 1)
                + " or "
                + (keyFields + 2)
                + " fields, found "
                + fields.size());
      }
      if (keyFields == 5 && !fields.get(3).equals("left") && !fields.get(3).equals("right")) {
        throw invalid(number, "side must be left or right, found '" + fields.get(3) + "'");
      }
      String verdict = fields.get(keyFields);
      String note = fields.size() > keyFields + 1 ? fields.get(keyFields + 1).strip() : "";
      if (!ISSUE.matcher(verdict).matches()
          && !verdict.equals("pending")
          && !verdict.equals("noise")) {
        throw invalid(
            number, "verdict must be #<issue>, pending, or noise, found '" + verdict + "'");
      }
      if (!ISSUE.matcher(verdict).matches() && note.isEmpty()) {
        throw invalid(number, verdict + " needs a note saying why");
      }
      String key = String.join("\t", fields.subList(0, keyFields));
      if (!keys.add(key)) {
        throw invalid(number, "repeats the key of an earlier line");
      }
      entries.add(new Entry(key, new Verdict(verdict, note), number));
    }
    return new KnownFindings(entries.build());
  }

  private static IllegalArgumentException invalid(int line, String problem) {
    return new IllegalArgumentException("Known findings, line " + line + ": " + problem);
  }

  /** Returns the verdict for the crash {@code signature}, or empty if it is new. */
  Optional<Verdict> crash(String signature) {
    return find("crash\t" + signature);
  }

  /**
   * Returns the verdict for a divergence group, or empty if it is new. An entry for {@code seed}
   * wins over an entry for {@code *}.
   *
   * @param side {@code left} or {@code right}
   */
  Optional<Verdict> divergence(String relation, String seed, String side, String template) {
    Optional<Verdict> exact = find(String.join("\t", "divergence", relation, seed, side, template));
    return exact.isPresent()
        ? exact
        : find(String.join("\t", "divergence", relation, "*", side, template));
  }

  /**
   * Returns the verdict for a family whose time grew too fast, or empty if it is new.
   *
   * @param family the words of a {@link BoundGraphGenerator.Family#name()}: shape, nullness, and
   *     use
   */
  Optional<Verdict> growth(String family) {
    List<String> words = List.of(family.split(" "));
    for (Entry entry : entries) {
      List<String> fields = List.of(entry.key().split("\t"));
      if (fields.get(0).equals("growth") && matches(fields.subList(1, fields.size()), words)) {
        matched.add(entry);
        return Optional.of(entry.verdict());
      }
    }
    return Optional.empty();
  }

  /** Returns whether each of {@code patterns} is {@code *} or the word at its position. */
  private static boolean matches(List<String> patterns, List<String> words) {
    if (patterns.size() != words.size()) {
      return false;
    }
    for (int i = 0; i < patterns.size(); i++) {
      if (!patterns.get(i).equals("*") && !patterns.get(i).equals(words.get(i))) {
        return false;
      }
    }
    return true;
  }

  private Optional<Verdict> find(String key) {
    for (Entry entry : entries) {
      if (entry.key().equals(key)) {
        matched.add(entry);
        return Optional.of(entry.verdict());
      }
    }
    return Optional.empty();
  }

  /** Returns the entries no lookup has matched so far, in file order. */
  ImmutableList<Entry> unmatched() {
    List<Entry> result = new ArrayList<>(entries);
    result.removeAll(matched);
    return ImmutableList.copyOf(result);
  }
}
