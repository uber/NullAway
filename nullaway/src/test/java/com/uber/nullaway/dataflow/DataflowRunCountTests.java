package com.uber.nullaway.dataflow;

import static com.google.common.truth.Truth.assertWithMessage;

import com.uber.nullaway.NullAwayTestsBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.junit.Test;

/**
 * Counts how often NullAway runs the nullness dataflow over one method or lambda while it compiles
 * a source. Each run after the first repeats work whose cost grows with the analyzed body.
 */
public class DataflowRunCountTests extends NullAwayTestsBase {

  @Test
  public void safeInitHelperRunsOnceForAllConstructorsAndFieldReads() {
    List<String> runs =
        runsWhileCompiling(
            """
            package com.uber;
            class Foo {
              Object f;
              Object g;
              Foo() {
                init();
                f.hashCode();
                f.toString();
                Object a = f;
                g = new Object();
              }
              Foo(int x) {
                init();
                f.hashCode();
                g = new Object();
              }
              private void init() {
                f = new Object();
              }
            }
            """);
    assertWithMessage("dataflow runs of init in %s", runs)
        .that(Collections.frequency(runs, "init"))
        .isEqualTo(1);
  }

  @Test
  public void ensuresNonNullMethodRunsAtMostTwice() {
    List<String> runs =
        runsWhileCompiling(
            """
            package com.uber;
            import com.uber.nullaway.annotations.EnsuresNonNull;
            import javax.annotation.Nullable;
            class Foo {
              @Nullable Object f;
              @EnsuresNonNull("f")
              void init() {
                Object x = f;
                if (x != null) {
                  x.hashCode();
                }
                f = new Object();
              }
            }
            """);
    assertWithMessage("dataflow runs of init in %s", runs)
        .that(Collections.frequency(runs, "init"))
        .isAtMost(2);
  }

  @Test
  public void blockLambdaFilterRunsOnceWhateverItsReturnCount() {
    List<String> runs =
        runsWhileCompiling(
            """
            package com.uber;
            import java.util.List;
            import java.util.stream.Collectors;
            import javax.annotation.Nullable;
            class Foo {
              @Nullable Object f;
              List<String> m(List<Foo> l) {
                return l.stream()
                    .filter(
                        p -> {
                          if (p.hashCode() == 0) {
                            return false;
                          }
                          if (p.hashCode() == 1) {
                            return p.f != null;
                          }
                          return p.f != null;
                        })
                    .map(q -> q.f.toString())
                    .collect(Collectors.toList());
              }
            }
            """);
    assertWithMessage("dataflow runs of the filter lambda in %s", runs)
        .that(Collections.frequency(runs, "lambda p"))
        .isEqualTo(1);
  }

  @Test
  public void anonymousPredicateFilterRunsOnceWhateverItsReturnCount() {
    List<String> runs =
        runsWhileCompiling(
            """
            package com.uber;
            import java.util.List;
            import java.util.function.Predicate;
            import java.util.stream.Collectors;
            import javax.annotation.Nullable;
            class Foo {
              @Nullable Object f;
              List<String> m(List<Foo> l) {
                return l.stream()
                    .filter(
                        new Predicate<Foo>() {
                          @Override
                          public boolean test(Foo p) {
                            if (p.hashCode() == 0) {
                              return false;
                            }
                            if (p.hashCode() == 1) {
                              return p.f != null;
                            }
                            return p.f != null;
                          }
                        })
                    .map(q -> q.f.toString())
                    .collect(Collectors.toList());
              }
            }
            """);
    assertWithMessage("dataflow runs of the filter method in %s", runs)
        .that(Collections.frequency(runs, "test"))
        .isEqualTo(1);
  }

  /**
   * Compiles {@code source} as {@code Foo.java}, expecting no diagnostics, and returns one entry
   * per dataflow run: the method name for a method, {@code lambda <first parameter>} for a lambda,
   * and {@code initializer} for an initializer.
   */
  private List<String> runsWhileCompiling(String source) {
    List<String> runs = Collections.synchronizedList(new ArrayList<>());
    RunOnceForwardAnalysisImpl.runListener = ast -> runs.add(label(ast));
    try {
      defaultCompilationHelper.addSourceLines("Foo.java", source).doTest();
    } finally {
      RunOnceForwardAnalysisImpl.runListener = null;
    }
    return runs;
  }

  private static String label(UnderlyingAST ast) {
    if (ast instanceof UnderlyingAST.CFGMethod method) {
      return method.getMethod().getName().toString();
    }
    if (ast instanceof UnderlyingAST.CFGLambda lambda) {
      return "lambda " + lambda.getLambdaTree().getParameters().get(0).getName();
    }
    return "initializer";
  }
}
