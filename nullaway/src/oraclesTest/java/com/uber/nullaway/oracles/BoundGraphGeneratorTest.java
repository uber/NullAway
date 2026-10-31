package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.uber.nullaway.oracles.BoundGraphGenerator.Family;
import com.uber.nullaway.oracles.BoundGraphGenerator.Nullness;
import com.uber.nullaway.oracles.BoundGraphGenerator.Shape;
import com.uber.nullaway.oracles.BoundGraphGenerator.Use;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Each shape declares two interfaces per level, bounded by the two of the level before it, and
 * closes the graph at level 0; every family is a program javac accepts and NullAway checks without
 * crashing, so that the growth oracle measures NullAway and not an invalid source.
 */
public class BoundGraphGeneratorTest {

  @Test
  public void aBackEdgeGraphBoundsLevelZeroByTheDeepestLevel() {
    Family family = new Family(Shape.BACK_EDGE, Nullness.NONE, Use.ASSIGNMENT);
    assertThat(family.source(2).text())
        .isEqualTo(
            """
            package com.uber.gen;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            interface A0<T extends A2<?> & B2<?>> {}
            interface B0<T extends B2<?>> {}
            interface A1<T extends A0<?> & B0<?>> {}
            interface B1<T extends A0<?> & B0<?>> {}
            interface A2<T extends A1<?> & B1<?>> {}
            interface B2<T extends A1<?> & B1<?>> {}
            @NullMarked
            abstract class Test {
            abstract A2<?> get(); void use(Test t) { A2<?> x = t.get(); }
            }
            """);
  }

  @Test
  public void aCyclicLeafGraphBoundsLevelZeroByItself() {
    Family family = new Family(Shape.CYCLIC_LEAF, Nullness.MIXED, Use.FIELD);
    assertThat(family.source(1).text())
        .isEqualTo(
            """
            package com.uber.gen;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            interface A0<T extends @Nullable A0<?> & B0<?>> {}
            interface B0<T extends B0<?>> {}
            interface A1<T extends @Nullable A0<?> & B0<?>> {}
            interface B1<T extends A0<?> & @Nullable B0<?>> {}
            @NullMarked
            abstract class Test {
            final A1<?> f; Test(A1<?> f) { this.f = f; } void use(Test t) { A1<?> x = t.f; }
            }
            """);
  }

  @Test
  public void aSubtypeGraphExtendsEachLevelOfABackEdgeGraphAndStartsFromItsDeepestLevel() {
    Family family = new Family(Shape.SUBTYPE, Nullness.ALL, Use.RETURN);
    assertThat(family.source(1).text())
        .isEqualTo(
            """
            package com.uber.gen;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            interface A0<T extends @Nullable A1<?> & @Nullable B1<?>> {}
            interface B0<T extends @Nullable B1<?>> {}
            interface A1<T extends @Nullable A0<?> & @Nullable B0<?>> {}
            interface B1<T extends @Nullable A0<?> & @Nullable B0<?>> {}
            interface C0<T extends @Nullable C1<?> & @Nullable D1<?>> extends A0<@Nullable T> {}
            interface D0<T extends @Nullable D1<?>> extends B0<@Nullable T> {}
            interface C1<T extends @Nullable C0<?> & @Nullable D0<?>> extends A1<@Nullable T> {}
            interface D1<T extends @Nullable C0<?> & @Nullable D0<?>> extends B1<@Nullable T> {}
            @NullMarked
            abstract class Test {
            abstract C1<?> get(); A1<?> use(Test t) { return t.get(); }
            }
            """);
  }

  @Test
  public void aDepthBelowOneIsRejected() {
    Family family = new Family(Shape.BACK_EDGE, Nullness.NONE, Use.ASSIGNMENT);
    assertThrows(IllegalArgumentException.class, () -> family.source(0));
  }

  /**
   * A finding and a known-findings entry are keyed by the name, so two families cannot share one.
   */
  @Test
  public void everyFamilyHasADistinctName() {
    assertThat(BoundGraphGenerator.families().stream().map(Family::name).toList())
        .containsNoDuplicates();
  }

  @Test
  public void everyFamilyCompilesAndRunsNullAwayAtDepthTwoUnderEachGrowthConfiguration() {
    Compiler compiler = new Compiler();
    List<String> failures = new ArrayList<>();
    for (String config : OracleRunner.GROWTH_CONFIGS) {
      for (Family family : BoundGraphGenerator.families()) {
        Compiler.Outcome outcome =
            compiler.runNullAway(List.of(family.source(2)), OracleRunner.CONFIGS.get(config), null);
        if (outcome.status() != Compiler.Status.OK) {
          failures.add(
              family.name()
                  + " under "
                  + config
                  + ": "
                  + outcome.status()
                  + " "
                  + outcome.detail());
        }
      }
    }
    assertThat(failures).isEmpty();
  }
}
