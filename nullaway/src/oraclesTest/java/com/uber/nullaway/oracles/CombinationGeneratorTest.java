package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableList;
import com.uber.nullaway.oracles.CombinationGenerator.Base;
import com.uber.nullaway.oracles.CombinationGenerator.Bound;
import com.uber.nullaway.oracles.CombinationGenerator.Construct;
import com.uber.nullaway.oracles.CombinationGenerator.Marking;
import com.uber.nullaway.oracles.CombinationGenerator.Placement;
import com.uber.nullaway.oracles.CombinationGenerator.Row;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * The generated programs contain every valid combination of values of any three dimensions, no
 * program combines a construct with a type it needs and does not have, and a row renders its types
 * into the test method.
 */
public class CombinationGeneratorTest {

  private static final ImmutableList<Row> ROWS = CombinationGenerator.generate();

  /** The values of every dimension, in the order of the components of {@link Row}. */
  private static final ImmutableList<ImmutableList<Object>> DIMENSIONS =
      ImmutableList.of(
          ImmutableList.copyOf(Base.values()),
          ImmutableList.of(false, true),
          ImmutableList.of(false, true),
          ImmutableList.copyOf(Construct.values()),
          ImmutableList.copyOf(Placement.values()),
          ImmutableList.copyOf(Marking.values()),
          ImmutableList.copyOf(Bound.values()),
          ImmutableList.copyOf(OracleRunner.CONFIGS.keySet()));

  private static List<Object> values(Row row) {
    return List.of(
        row.base(),
        row.nullableTop(),
        row.flippedTarget(),
        row.construct(),
        row.placement(),
        row.marking(),
        row.bound(),
        row.config());
  }

  /**
   * Returns whether the values of a combination, by dimension, can share a program: the {@code
   * BOX_} constructs need a {@code Box} base, the array constructor reference an array base, and
   * the null-marked local class a local class to mark, possibly inside another.
   */
  private static boolean compatible(Object[] combination) {
    Object base = combination[0];
    Object construct = combination[3];
    Object placement = combination[4];
    Object marking = combination[5];
    if (base != null && construct != null) {
      String constructName = ((Construct) construct).name();
      String baseName = ((Base) base).name();
      if (constructName.startsWith("BOX_") && !baseName.startsWith("BOX")) {
        return false;
      }
      if (construct == Construct.ARRAY_CONSTRUCTOR_REF && !baseName.startsWith("ARRAY")) {
        return false;
      }
    }
    return marking != Marking.MARKED_LOCAL_CLASS_IN_UNMARKED_CLASS
        || placement == null
        || placement == Placement.LOCAL_CLASS
        || placement == Placement.NESTED_LOCAL_CLASS;
  }

  @Test
  public void everyCompatibleTripleOfValuesAppearsInSomeProgram() {
    Set<List<Object>> present = new HashSet<>();
    for (Row row : ROWS) {
      List<Object> v = values(row);
      for (int a = 0; a < v.size(); a++) {
        for (int b = a + 1; b < v.size(); b++) {
          for (int c = b + 1; c < v.size(); c++) {
            present.add(List.of(a, v.get(a), b, v.get(b), c, v.get(c)));
          }
        }
      }
    }
    List<List<Object>> missing = new ArrayList<>();
    int n = DIMENSIONS.size();
    for (int a = 0; a < n; a++) {
      for (int b = a + 1; b < n; b++) {
        for (int c = b + 1; c < n; c++) {
          for (Object va : DIMENSIONS.get(a)) {
            for (Object vb : DIMENSIONS.get(b)) {
              for (Object vc : DIMENSIONS.get(c)) {
                Object[] combination = new Object[n];
                combination[a] = va;
                combination[b] = vb;
                combination[c] = vc;
                List<Object> triple = List.of(a, va, b, vb, c, vc);
                if (compatible(combination) && !present.contains(triple)) {
                  missing.add(triple);
                }
              }
            }
          }
        }
      }
    }
    assertWithMessage("triples no program contains").that(missing).isEmpty();
  }

  @Test
  public void noProgramCombinesAConstructWithATypeItCannotUse() {
    List<Row> incompatible = new ArrayList<>();
    for (Row row : ROWS) {
      if (!compatible(values(row).toArray())) {
        incompatible.add(row);
      }
    }
    assertThat(incompatible).isEmpty();
  }

  @Test
  public void aRowRendersTheDeclaredAndTheTargetTypeIntoTheTestMethod() {
    Row row =
        new Row(
            Base.BOX_SUPER,
            true,
            true,
            Construct.LOCAL,
            Placement.DIRECT,
            Marking.CLASS,
            Bound.NULLABLE_OBJECT,
            "jspecify");
    assertThat(row.source().text())
        .contains(
            "@NullMarked\npublic class Test {\n  static class Box<T extends @Nullable Object> {");
    assertThat(row.source().text())
        .contains(
            "  static <T extends @Nullable Object> @Nullable Box<? super String> test("
                + "@Nullable Box<? super String> p, @Nullable Box<? super String> q, boolean c) {\n"
                + "    Box<? super String> x = p; return x;\n");
  }

  @Test
  public void aMarkedLocalClassCarriesTheAnnotationInsideAnUnmarkedClass() {
    Row row =
        new Row(
            Base.STRING,
            false,
            false,
            Construct.RETURN,
            Placement.LOCAL_CLASS,
            Marking.MARKED_LOCAL_CLASS_IN_UNMARKED_CLASS,
            Bound.OBJECT,
            "jspecify-nullmarked");
    assertThat(row.source().text()).doesNotContain("@NullMarked\npublic class Test");
    assertThat(row.source().text())
        .contains("    @NullMarked class L { String run() { return p; } } return new L().run();\n");
  }

  @Test
  public void theSameSeedGivesTheSameProgramsAndAnotherSeedOtherOnes() {
    assertThat(CombinationGenerator.generate(1)).isEqualTo(ROWS);
    assertThat(CombinationGenerator.generate(2)).isNotEqualTo(ROWS);
  }
}
