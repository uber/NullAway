package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import java.util.Locale;

/**
 * Generates programs whose type parameters have intersection bounds that refer to each other across
 * a number of levels, the depth, so that {@link GrowthOracle} can check one family of programs at
 * increasing depths.
 *
 * <p>Level {@code k} of a graph declares interfaces {@code Ak} and {@code Bk}, whose type parameter
 * is bounded by {@code A(k-1)<?> & B(k-1)<?>}. The interfaces of level 0 close the graph, and the
 * {@link Shape} decides how. A program declares the graph at the top level, outside any null-marked
 * code, and uses the type of its deepest level in a {@code @NullMarked} class, the way issue #2093
 * reported it. NullAway's checks of nested nullability compare the bounds level by level, so the
 * time of one check can grow exponentially with the depth even though the program grows linearly.
 */
final class BoundGraphGenerator {

  private BoundGraphGenerator() {}

  /** How the levels of a graph refer to each other. */
  enum Shape {
    /** Level 0 is bounded by the deepest level, so every path through the bounds is a cycle. */
    BACK_EDGE,
    /** Each interface of level 0 bounds itself, so the cycles are at the leaves only. */
    CYCLIC_LEAF,
    /**
     * A second graph of interfaces {@code Ck} and {@code Dk} extends the {@link #BACK_EDGE} graph
     * level by level, and the program converts its deepest {@code C} to the deepest {@code A}, so
     * every check first views the subtype as its supertype.
     */
    SUBTYPE,
  }

  /** Which components of each intersection bound are {@code @Nullable}. */
  enum Nullness {
    NONE,
    /** Every component; a {@link Shape#SUBTYPE} graph also passes {@code @Nullable T} upwards. */
    ALL,
    /** The first component of an {@code A} bound and the second of a {@code B} bound. */
    MIXED,
  }

  /** How the program moves a value of the deepest type, each a separate path into the checks. */
  enum Use {
    ASSIGNMENT("abstract {R} get(); void use(Test t) { {L} x = t.get(); }"),
    ARGUMENT("abstract {R} get(); abstract void take({L} p); void use(Test t) { take(t.get()); }"),
    RETURN("abstract {R} get(); {L} use(Test t) { return t.get(); }"),
    OVERRIDE(
        "interface Source { {L} get(); }"
            + " abstract static class Sub implements Source { public abstract {R} get(); }"),
    INFERENCE(
        "abstract {R} get(); abstract <X> X id(X x); void use(Test t) { {L} x = id(t.get()); }"),
    LAMBDA(
        "abstract {R} get();"
            + " void use(Test t) { java.util.function.Supplier<{L}> s = () -> t.get(); }"),
    CONDITIONAL(
        "abstract {R} get(); void use(Test t, boolean b) { {L} x = b ? t.get() : t.get(); }"),
    NESTED(
        "abstract java.util.List<{R}> get();"
            + " void use(Test t) { java.util.List<? extends {L}> x = t.get(); }"),
    FIELD("final {R} f; Test({R} f) { this.f = f; } void use(Test t) { {L} x = t.f; }");

    /**
     * The members of class {@code Test}, where {@code {L}} is the type of the deepest {@code A} and
     * {@code {R}} the type the value starts as.
     */
    private final String members;

    Use(String members) {
      this.members = members;
    }
  }

  /**
   * One family of programs, which differ only in the depth of the graph.
   *
   * @param shape how the levels refer to each other
   * @param nullness which bound components are {@code @Nullable}
   * @param use how the program moves a value of the deepest type
   */
  record Family(Shape shape, Nullness nullness, Use use) {

    /** Returns the family's name, such as {@code back-edge none assignment}. */
    String name() {
      return String.join(" ", name(shape), name(nullness), name(use));
    }

    private static String name(Enum<?> constant) {
      return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * Returns the program whose graph has levels 0 to {@code depth}.
     *
     * @throws IllegalArgumentException if {@code depth} is less than 1
     */
    Source source(int depth) {
      if (depth < 1) {
        throw new IllegalArgumentException("depth must be at least 1, found " + depth);
      }
      StringBuilder text =
          new StringBuilder(
              """
              package com.uber.gen;
              import org.jspecify.annotations.NullMarked;
              import org.jspecify.annotations.Nullable;
              """);
      String leafA = shape == Shape.CYCLIC_LEAF ? "A0<?>" : "A" + depth + "<?>";
      String leafB = shape == Shape.CYCLIC_LEAF ? "B0<?>" : "B" + depth + "<?>";
      declare(text, "A0", aBound(leafA, leafB), "");
      declare(text, "B0", (nullness == Nullness.ALL ? "@Nullable " : "") + leafB, "");
      for (int k = 1; k <= depth; k++) {
        declare(text, "A" + k, aBound("A" + (k - 1) + "<?>", "B" + (k - 1) + "<?>"), "");
        declare(text, "B" + k, bBound("A" + (k - 1) + "<?>", "B" + (k - 1) + "<?>"), "");
      }
      String rhs = "A" + depth + "<?>";
      if (shape == Shape.SUBTYPE) {
        String argument = nullness == Nullness.ALL ? "@Nullable T" : "T";
        declare(
            text, "C0", aBound("C" + depth + "<?>", "D" + depth + "<?>"), "A0<" + argument + ">");
        declare(
            text,
            "D0",
            (nullness == Nullness.ALL ? "@Nullable " : "") + "D" + depth + "<?>",
            "B0<" + argument + ">");
        for (int k = 1; k <= depth; k++) {
          String bound = "C" + (k - 1) + "<?>";
          String other = "D" + (k - 1) + "<?>";
          declare(text, "C" + k, aBound(bound, other), "A" + k + "<" + argument + ">");
          declare(text, "D" + k, bBound(bound, other), "B" + k + "<" + argument + ">");
        }
        rhs = "C" + depth + "<?>";
      }
      text.append("@NullMarked\nabstract class Test {\n")
          .append(use.members.replace("{L}", "A" + depth + "<?>").replace("{R}", rhs))
          .append("\n}\n");
      return new Source("com/uber/gen/Test.java", text.toString());
    }

    /** Returns the bound of an {@code A} or {@code C} interface over two components. */
    private String aBound(String first, String second) {
      return switch (nullness) {
        case NONE -> first + " & " + second;
        case ALL -> "@Nullable " + first + " & @Nullable " + second;
        case MIXED -> "@Nullable " + first + " & " + second;
      };
    }

    /** Returns the bound of a {@code B} or {@code D} interface over two components. */
    private String bBound(String first, String second) {
      return switch (nullness) {
        case NONE -> first + " & " + second;
        case ALL -> "@Nullable " + first + " & @Nullable " + second;
        case MIXED -> first + " & @Nullable " + second;
      };
    }

    private static void declare(StringBuilder text, String name, String bound, String supertype) {
      text.append("interface ").append(name).append("<T extends ").append(bound).append(">");
      if (!supertype.isEmpty()) {
        text.append(" extends ").append(supertype);
      }
      text.append(" {}\n");
    }
  }

  /** Returns every combination of a shape, a nullness, and a use, in declaration order. */
  static ImmutableList<Family> families() {
    ImmutableList.Builder<Family> result = ImmutableList.builder();
    for (Shape shape : Shape.values()) {
      for (Nullness nullness : Nullness.values()) {
        for (Use use : Use.values()) {
          result.add(new Family(shape, nullness, use));
        }
      }
    }
    return result.build();
  }
}
