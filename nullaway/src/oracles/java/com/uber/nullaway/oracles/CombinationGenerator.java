package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Generates small programs that combine a declared type, a construct that moves a value of that
 * type, where the construct sits, and how the code is null-marked, so that every three of these
 * features appear together in some program.
 *
 * <p>The crashes the oracles found each needed two or three features at once, such as a wildcard
 * receiver in JSpecify mode or a varargs method reference with a {@code @Nullable} type argument,
 * on paths the NullAway tests already cover one feature at a time. A program here is a test method
 * {@code D test(D p, D q, boolean c)} whose body moves {@code p} and {@code q} through one {@link
 * Construct}, beside a generic {@code Box<T>} and the helper methods the constructs call. The
 * programs are chosen as a covering array of strength 3 over the dimensions of {@link Row}: every
 * valid combination of values of any three dimensions occurs in at least one program. Most programs
 * compile; the ones javac rejects, such as {@code Box<? super String>.get()} assigned to a {@code
 * String}, are left for the compiler to reject.
 */
final class CombinationGenerator {

  /**
   * The base of the declared type {@code D}, with its forms without and with a top-level
   * {@code @Nullable}.
   */
  enum Base {
    STRING("String", "@Nullable String"),
    BOX("Box<String>", "@Nullable Box<String>"),
    BOX_OF_NULLABLE("Box<@Nullable String>", "@Nullable Box<@Nullable String>"),
    BOX_EXTENDS("Box<? extends String>", "@Nullable Box<? extends String>"),
    BOX_EXTENDS_NULLABLE(
        "Box<? extends @Nullable String>", "@Nullable Box<? extends @Nullable String>"),
    BOX_SUPER("Box<? super String>", "@Nullable Box<? super String>"),
    BOX_SUPER_NULLABLE("Box<? super @Nullable String>", "@Nullable Box<? super @Nullable String>"),
    BOX_UNBOUNDED("Box<?>", "@Nullable Box<?>"),
    BOX_NESTED(
        "Box<Box<? super @Nullable String>>", "@Nullable Box<Box<? super @Nullable String>>"),
    ARRAY("String[]", "String @Nullable []"),
    ARRAY_OF_NULLABLE("@Nullable String []", "@Nullable String @Nullable []"),
    TYPE_VARIABLE("T", "@Nullable T"),
    INTEGER("Integer", "@Nullable Integer"),
    LIST_EXTENDS_NULLABLE(
        "java.util.List<? extends @Nullable String>",
        "java.util.@Nullable List<? extends @Nullable String>"),
    SUPPLIER_OF_NULLABLE(
        "java.util.function.Supplier<@Nullable String>",
        "java.util.function.@Nullable Supplier<@Nullable String>");

    final String nonNullTop;
    final String nullableTop;

    Base(String nonNullTop, String nullableTop) {
      this.nonNullTop = nonNullTop;
      this.nullableTop = nullableTop;
    }

    boolean isBox() {
      return name().startsWith("BOX");
    }

    boolean isArray() {
      return name().startsWith("ARRAY");
    }
  }

  /**
   * How the body of the test method moves {@code p} and {@code q} to its result. {@code {D}} is the
   * declared type and {@code {T2}} the target type, which differs from {@code {D}} at most in its
   * top-level nullness.
   */
  enum Construct {
    RETURN("return p;"),
    LOCAL("{T2} x = p; return x;"),
    VAR("var x = p; return x;"),
    NULL_CHECK("if (p == null) { return q; } return p;"),
    TERNARY("return c ? p : q;"),
    TERNARY_NULL("return c ? p : null;"),
    SWITCH("return switch (c ? 1 : 0) { case 0 -> p; default -> q; };"),
    CAST("return ({T2}) p;"),
    GENERIC_CALL("return id(p);"),
    GENERIC_CALL_EXPLICIT("return Test.<{T2}>id(p);"),
    LAMBDA_IMPLICIT("java.util.function.Function<{D}, {T2}> f = x -> x; return f.apply(p);"),
    LAMBDA_EXPLICIT("java.util.function.Function<{D}, {T2}> f = ({D} x) -> x; return f.apply(p);"),
    METHOD_REF("java.util.function.Function<{D}, {T2}> f = Test::id; return f.apply(p);"),
    LAMBDA_EXPLICIT_WILDCARD_TARGET(
        "java.util.function.Function<? super {D}, ? extends {T2}> f = ({D} x) -> x;"
            + " return f.apply(p);"),
    LAMBDA_EXPLICIT_UNBOUNDED_TARGET("java.util.function.Consumer<?> f = ({D} x) -> {}; return p;"),
    METHOD_REF_WILDCARD_TARGET(
        "java.util.function.Function<? super {D}, ? extends {T2}> f = Test::id; return f.apply(p);"),
    VARARGS_METHOD_REF(
        "java.util.function.BiFunction<{D}, {T2}, {D}> f = Test::vararg; return f.apply(p, q);"),
    ANONYMOUS_FUNCTION(
        "java.util.function.Function<{D}, {T2}> f = new java.util.function.Function<{D}, {T2}>() {"
            + " public {T2} apply({D} x) { return x; } }; return f.apply(p);"),
    STREAM("return java.util.stream.Stream.of(p, q).filter(x -> x != null).findFirst().orElse(q);"),
    STREAM_UNMARKED_FILTER(
        "return java.util.stream.Stream.of(p, q).filter(new java.util.function.Predicate<{D}>() {"
            + " @NullUnmarked public boolean test({D} x) { return true; } })"
            + ".map(x -> { x.hashCode(); return x; }).findFirst().orElse(q);"),
    OPTIONAL("return java.util.Optional.ofNullable(p).orElse(q);"),
    OPTIONAL_MAP("return java.util.Optional.ofNullable(p).map(x -> c ? x : q).orElseGet(() -> q);"),
    COLLECT(
        "return java.util.stream.Stream.of(p, q)"
            + ".collect(java.util.stream.Collectors.toList()).get(0);"),
    REQUIRE_NON_NULL("java.util.Objects.requireNonNull(p); return p;"),
    INSTANCEOF_PATTERN("if (p instanceof Object o) { return c ? p : q; } return q;"),
    PATTERN_SWITCH(
        "return switch ((Object) p) { case String s -> p; case null -> q; default -> p; };"),
    TRY_CATCH("try { return id(p); } catch (RuntimeException e) { return q; }"),
    TRY_FINALLY("try { return p; } finally { q = null; }"),
    LOOP_ASSIGN("{T2} x = q; for (int i = 0; i < 2; i++) { x = c ? p : x; } return x;"),
    LOCAL_RECORD("record R({D} v) {} return new R(p).v();"),
    CONSTRUCTOR_REF(
        "java.util.function.Function<{D}, Box<{D}>> f = Box::new; return f.apply(p).get();"),
    BOUND_METHOD_REF("java.util.function.Supplier<{T2}> f = new Box<{D}>(p)::get; return f.get();"),
    FOR_EACH("for ({T2} x : java.util.Arrays.asList(p, q)) { return x; } return p;"),
    ARRAY_CONSTRUCTOR_REF("java.util.function.IntFunction<{D}> f = {D}::new; return f.apply(1);"),
    BOX_GET("Object v = p.get(); return v == null ? q : p;"),
    BOX_GET_ARGUMENT("take(p.get()); return p;"),
    BOX_SET_NULL("p.set(null); return p;"),
    BOX_SET_VALUE("p.set(\"s\"); return p;"),
    BOX_COPY("q.set(p.get()); return q;");

    final String body;

    Construct(String body) {
      this.body = body;
    }
  }

  /** Where the construct runs relative to the test method. */
  enum Placement {
    DIRECT,
    LAMBDA,
    LOCAL_CLASS,
    NESTED_LOCAL_CLASS,
    ANONYMOUS_CLASS,
    SWITCH_CASE,
    TRY_BLOCK
  }

  /** Which declarations carry {@code @NullMarked} or {@code @NullUnmarked}. */
  enum Marking {
    CLASS,
    METHOD,
    UNMARKED_METHOD_IN_MARKED_CLASS,
    NONE,
    MARKED_LOCAL_CLASS_IN_UNMARKED_CLASS,
    SUPPRESSED_METHOD_IN_MARKED_CLASS
  }

  /** The upper bound of the type parameters of {@code Box} and of the test method. */
  enum Bound {
    OBJECT("Object"),
    NULLABLE_OBJECT("@Nullable Object"),
    COMPARABLE("Comparable<String>");

    final String text;

    Bound(String text) {
      this.text = text;
    }
  }

  /**
   * One generated program.
   *
   * @param base the base of the declared type
   * @param nullableTop whether the declared type has a top-level {@code @Nullable}
   * @param flippedTarget whether the target type has the opposite top-level nullness
   * @param construct how the body moves the value
   * @param placement where the body runs
   * @param marking which declarations are null-marked
   * @param bound the bound of the type parameters
   * @param config a key of {@link OracleRunner#CONFIGS}
   */
  record Row(
      Base base,
      boolean nullableTop,
      boolean flippedTarget,
      Construct construct,
      Placement placement,
      Marking marking,
      Bound bound,
      String config) {

    /** Returns the program's single unit. */
    Source source() {
      String d = nullableTop ? base.nullableTop : base.nonNullTop;
      String t2 = nullableTop != flippedTarget ? base.nullableTop : base.nonNullTop;
      String body = construct.body.replace("{D}", d).replace("{T2}", t2);
      String innerMark =
          marking == Marking.MARKED_LOCAL_CLASS_IN_UNMARKED_CLASS ? "@NullMarked " : "";
      String placed =
          switch (placement) {
            case DIRECT -> body;
            case LAMBDA ->
                "java.util.function.Supplier<"
                    + d
                    + "> s = () -> { "
                    + body
                    + " }; return s.get();";
            case LOCAL_CLASS ->
                innerMark + "class L { " + d + " run() { " + body + " } } return new L().run();";
            case NESTED_LOCAL_CLASS ->
                "class O { "
                    + d
                    + " run() { "
                    + innerMark
                    + "class L { "
                    + d
                    + " run() { "
                    + body
                    + " } } return new L().run(); } } return new O().run();";
            case SWITCH_CASE ->
                "switch (c ? 1 : 0) { case 0: { " + body + " } default: return q; }";
            case TRY_BLOCK -> "try { " + body + " } catch (RuntimeException e) { return q; }";
            case ANONYMOUS_CLASS ->
                "return new java.util.function.Supplier<"
                    + d
                    + ">() { public "
                    + d
                    + " get() { "
                    + body
                    + " } }.get();";
          };
      String classMark =
          switch (marking) {
            case CLASS, UNMARKED_METHOD_IN_MARKED_CLASS, SUPPRESSED_METHOD_IN_MARKED_CLASS ->
                "@NullMarked\n";
            default -> "";
          };
      String methodMark =
          switch (marking) {
            case METHOD -> "  @NullMarked\n";
            case UNMARKED_METHOD_IN_MARKED_CLASS -> "  @NullUnmarked\n";
            case SUPPRESSED_METHOD_IN_MARKED_CLASS -> "  @SuppressWarnings(\"NullAway\")\n";
            default -> "";
          };
      String text =
          String.join(
              "\n",
              "package com.uber.gen;",
              "import org.jspecify.annotations.NullMarked;",
              "import org.jspecify.annotations.NullUnmarked;",
              "import org.jspecify.annotations.Nullable;",
              classMark + "public class Test {",
              "  static class Box<T extends " + bound.text + "> {",
              "    T value;",
              "    Box(T value) { this.value = value; }",
              "    T get() { return value; }",
              "    void set(T value) { this.value = value; }",
              "  }",
              "  static <T extends @Nullable Object> T id(T t) { return t; }",
              "  static void take(Object o) {}",
              "  static <T extends "
                  + bound.text
                  + "> "
                  + d
                  + " vararg("
                  + d
                  + "... xs) { return xs[0]; }",
              methodMark
                  + "  static <T extends "
                  + bound.text
                  + "> "
                  + d
                  + " test("
                  + d
                  + " p, "
                  + d
                  + " q, boolean c) {",
              "    " + placed,
              "  }",
              "}",
              "");
      return new Source("com/uber/gen/Test.java", text);
    }

    @Override
    public String toString() {
      return String.join(
          " ",
          "base=" + base,
          "top=" + (nullableTop ? "nullable" : "non-null"),
          "target=" + (flippedTarget ? "flipped" : "same"),
          "construct=" + construct,
          "placement=" + placement,
          "marking=" + marking,
          "bound=" + bound,
          "config=" + config);
    }
  }

  /** The number of values of each dimension of {@link Row}, in the order of its components. */
  private static final int[] SIZES = {
    Base.values().length,
    2,
    2,
    Construct.values().length,
    Placement.values().length,
    Marking.values().length,
    Bound.values().length,
    OracleRunner.CONFIGS.size()
  };

  /** Candidate rows built for each row the covering array keeps. */
  private static final int CANDIDATES = 40;

  private CombinationGenerator() {}

  /** Returns the programs of {@link #generate(long)} with seed 1. */
  static ImmutableList<Row> generate() {
    return generate(1);
  }

  /**
   * Returns programs that together contain every valid combination of values of any three
   * dimensions. The same seed gives the same rows; another seed gives another covering array, which
   * covers other combinations of four dimensions and more.
   */
  static ImmutableList<Row> generate(long seed) {
    Set<Long> uncovered = new HashSet<>();
    int n = SIZES.length;
    for (int a = 0; a < n; a++) {
      for (int b = a + 1; b < n; b++) {
        for (int c = b + 1; c < n; c++) {
          for (int va = 0; va < SIZES[a]; va++) {
            for (int vb = 0; vb < SIZES[b]; vb++) {
              for (int vc = 0; vc < SIZES[c]; vc++) {
                int[] partial = unassigned();
                partial[a] = va;
                partial[b] = vb;
                partial[c] = vc;
                if (valid(partial)) {
                  uncovered.add(key(a, va, b, vb, c, vc));
                }
              }
            }
          }
        }
      }
    }
    Random random = new Random(seed);
    ImmutableList.Builder<Row> rows = ImmutableList.builder();
    while (!uncovered.isEmpty()) {
      int[] best = null;
      int bestGain = -1;
      List<Long> pool = new ArrayList<>(uncovered);
      for (int candidate = 0; candidate < CANDIDATES; candidate++) {
        int[] row = candidate(pool.get(random.nextInt(pool.size())), uncovered, random);
        int gain = covered(row, uncovered).size();
        if (gain > bestGain) {
          best = row;
          bestGain = gain;
        }
      }
      uncovered.removeAll(covered(best, uncovered));
      rows.add(toRow(best));
    }
    return rows.build();
  }

  /**
   * Builds a row that contains the triple {@code seed}, filling the other dimensions in random
   * order, each with the valid value that covers the most uncovered triples.
   */
  private static int[] candidate(long seed, Set<Long> uncovered, Random random) {
    int[] row = unassigned();
    for (int i = 0; i < 3; i++) {
      long part = (seed >>> (20 * i)) & 0xFFFFF;
      row[(int) (part >>> 10)] = (int) (part & 0x3FF);
    }
    List<Integer> order = new ArrayList<>();
    for (int d = 0; d < SIZES.length; d++) {
      if (row[d] < 0) {
        order.add(d);
      }
    }
    Collections.shuffle(order, random);
    for (int d : order) {
      int bestValue = -1;
      int bestGain = -1;
      int start = random.nextInt(SIZES[d]);
      for (int i = 0; i < SIZES[d]; i++) {
        int v = (start + i) % SIZES[d];
        row[d] = v;
        if (!valid(row)) {
          continue;
        }
        int gain = covered(row, uncovered).size();
        if (gain > bestGain) {
          bestValue = v;
          bestGain = gain;
        }
      }
      row[d] = bestValue;
    }
    return row;
  }

  /** Returns the uncovered triples that {@code row} covers, counting only assigned dimensions. */
  private static List<Long> covered(int[] row, Set<Long> uncovered) {
    List<Long> result = new ArrayList<>();
    int n = SIZES.length;
    for (int a = 0; a < n; a++) {
      for (int b = a + 1; b < n; b++) {
        for (int c = b + 1; c < n; c++) {
          if (row[a] >= 0 && row[b] >= 0 && row[c] >= 0) {
            long k = key(a, row[a], b, row[b], c, row[c]);
            if (uncovered.contains(k)) {
              result.add(k);
            }
          }
        }
      }
    }
    return result;
  }

  /**
   * Returns whether no two assigned dimensions of {@code row} conflict: a construct that needs a
   * {@code Box} or an array has one, and a null-marked local class has a local class to mark,
   * possibly inside another.
   */
  private static boolean valid(int[] row) {
    Base base = row[0] < 0 ? null : Base.values()[row[0]];
    Construct construct = row[3] < 0 ? null : Construct.values()[row[3]];
    Placement placement = row[4] < 0 ? null : Placement.values()[row[4]];
    Marking marking = row[5] < 0 ? null : Marking.values()[row[5]];
    if (base != null && construct != null) {
      boolean boxConstruct = construct.name().startsWith("BOX_");
      if (boxConstruct && !base.isBox()) {
        return false;
      }
      if (construct == Construct.ARRAY_CONSTRUCTOR_REF && !base.isArray()) {
        return false;
      }
    }
    return marking != Marking.MARKED_LOCAL_CLASS_IN_UNMARKED_CLASS
        || placement == null
        || placement == Placement.LOCAL_CLASS
        || placement == Placement.NESTED_LOCAL_CLASS;
  }

  private static int[] unassigned() {
    int[] row = new int[SIZES.length];
    Arrays.fill(row, -1);
    return row;
  }

  /** Packs three (dimension, value) pairs, dimensions ascending, into one key. */
  private static long key(int a, int va, int b, int vb, int c, int vc) {
    return pair(a, va) | pair(b, vb) << 20 | pair(c, vc) << 40;
  }

  private static long pair(int dimension, int value) {
    return (long) dimension << 10 | value;
  }

  /** Returns the program whose dimensions take the values with the indices in {@code row}. */
  private static Row toRow(int[] row) {
    return new Row(
        Base.values()[row[0]],
        row[1] == 1,
        row[2] == 1,
        Construct.values()[row[3]],
        Placement.values()[row[4]],
        Marking.values()[row[5]],
        Bound.values()[row[6]],
        OracleRunner.CONFIGS.keySet().asList().get(row[7]));
  }
}
