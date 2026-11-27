package com.uber.nullaway.jspecify.mismatchmessage;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.io.IOException;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A {@code fix:} line comes with an Error Prone fix that rewrites the type the named declaration
 * writes, where that declaration is in the compilation unit being analyzed and writes its type out.
 */
public class FixTests {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private BugCheckerRefactoringTestHelper makeHelper() {
    return BugCheckerRefactoringTestHelper.newInstance(NullAway.class, getClass())
        .setArgs(
            ImmutableList.copyOf(
                JSpecifyJavacConfig.withJSpecifyModeArgs(
                    List.of(
                        "-d",
                        temporaryFolder.getRoot().getAbsolutePath(),
                        "-XepOpt:NullAway:OnlyNullMarked=true"))));
  }

  @Test
  public void theTargetTypeFixImportsTheNullableAnnotationItAdds() throws IOException {
    makeHelper()
        .addInputLines(
            "Lib.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Lib {
              static List<@Nullable String> get() {
                throw new RuntimeException();
              }
            }
            """)
        .expectUnchanged()
        .addInputLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              void test() {
                List<String> l = Lib.get();
                l.hashCode();
              }
            }
            """)
        .addOutputLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              void test() {
                List<@Nullable String> l = Lib.get();
                l.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void theSourceFixKeepsANullableAnnotationWrittenAmongTheModifiers() throws IOException {
    makeHelper()
        .addInputLines(
            "Test.java",
            """
            import java.util.Collection;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(Collection<? extends Object> c) {}
              void test(@Nullable List<?> l) {
                if (l != null) {
                  take(l);
                }
              }
            }
            """)
        .addOutputLines(
            "Test.java",
            """
            import java.util.Collection;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void take(Collection<? extends Object> c) {}
              void test(@Nullable List<? extends Object> l) {
                if (l != null) {
                  take(l);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noEditForADeclarationInAnotherCompilationUnit() throws IOException {
    makeHelper()
        .addInputLines(
            "Lib.java",
            """
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Lib {
              static List<?> field = new ArrayList<>();
            }
            """)
        .expectUnchanged()
        .addInputLines(
            "Test.java",
            """
            import java.util.Collection;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              static void take(Collection<? extends Object> c) {}
              void test() {
                take(Lib.field);
              }
            }
            """)
        .expectUnchanged()
        .doTest();
  }

  @Test
  public void noEditForADeclarationWithAnInferredType() throws IOException {
    makeHelper()
        .addInputLines(
            "Test.java",
            """
            import java.util.Collection;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              static void take(Collection<? extends Object> c) {}
              static List<?> make() {
                throw new RuntimeException();
              }
              void test() {
                var l = make();
                take(l);
              }
            }
            """)
        .expectUnchanged()
        .doTest();
  }

  @Test
  public void theFixPutsTheAnnotationBeforeTheLastSegmentOfAQualifiedName() throws IOException {
    makeHelper()
        .addInputLines(
            "Lib.java",
            """
            import java.util.List;
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Lib {
              static List<Map.@Nullable Entry<String, String>> entries() {
                throw new RuntimeException();
              }
              static java.util.List<java.util.@Nullable List<String>> lists() {
                throw new RuntimeException();
              }
            }
            """)
        .expectUnchanged()
        .addInputLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class List<T> {}
              void test() {
                java.util.List<Map.Entry<String, String>> nested = Lib.entries();
                java.util.List<java.util.List<String>> clashing = Lib.lists();
              }
            }
            """)
        .addOutputLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class List<T> {}
              void test() {
                java.util.List<Map.@Nullable Entry<String, String>> nested = Lib.entries();
                java.util.List<java.util.@Nullable List<String>> clashing = Lib.lists();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noEditForAVarargsParameterOrADeclarationOfSeveralVariables() throws IOException {
    makeHelper()
        .addInputLines(
            "Test.java",
            """
            import java.util.ArrayList;
            import java.util.Collection;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              static void take(Collection<? extends Object> c) {}
              static void takeArray(Collection<? extends Object>[] c) {}
              void varargs(List<?>... lists) {
                takeArray(lists);
              }
              void several() {
                List<?> first = new ArrayList<>(), second = new ArrayList<>();
                take(first);
                take(second);
              }
            }
            """)
        .expectUnchanged()
        .doTest();
  }
}
