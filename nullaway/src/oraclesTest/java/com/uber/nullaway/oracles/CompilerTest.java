package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.sun.source.tree.MethodTree;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A run ends as a crash when the checker throws, as invalid when javac rejects the source, and
 * otherwise carries the checker's warnings with the unit and line they are on.
 */
public class CompilerTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final List<String> LEGACY = List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber");

  /** Throws from every method it visits, standing in for a NullAway crash. */
  @BugPattern(summary = "Throws on every method", severity = BugPattern.SeverityLevel.WARNING)
  public static class ThrowingChecker extends BugChecker implements BugChecker.MethodTreeMatcher {
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      throw new IllegalStateException("thrown by ThrowingChecker");
    }
  }

  private static Source dereference(String parameter) {
    return new Source(
        "com/uber/A.java",
        """
        package com.uber;
        import javax.annotation.Nullable;
        class A {
          int length(%s String s) {
            return s.length();
          }
        }
        """
            .formatted(parameter));
  }

  @Test
  public void aCheckerExceptionIsACrashWithItsStackTrace() {
    Compiler.Outcome outcome =
        new Compiler(ThrowingChecker.class).runNullAway(List.of(dereference("")), List.of(), null);
    assertWithMessage("detail: %s", outcome.detail())
        .that(outcome.status())
        .isEqualTo(Compiler.Status.CRASH);
    assertThat(outcome.detail()).contains("thrown by ThrowingChecker");
    assertThat(outcome.detail()).contains("CompilerTest$ThrowingChecker.matchMethod(");
  }

  @Test
  public void aSourceJavacRejectsIsInvalid() {
    Source source = new Source("com/uber/B.java", "package com.uber; class B { int x = \"s\"; }");
    Compiler.Outcome outcome = new Compiler().runNullAway(List.of(source), LEGACY, null);
    assertWithMessage("detail: %s", outcome.detail())
        .that(outcome.status())
        .isEqualTo(Compiler.Status.INVALID);
  }

  @Test
  public void aNullableDereferenceIsAWarningOnItsLine() {
    Compiler.Outcome outcome =
        new Compiler().runNullAway(List.of(dereference("@Nullable")), LEGACY, null);
    assertWithMessage("detail: %s", outcome.detail())
        .that(outcome.status())
        .isEqualTo(Compiler.Status.OK);
    assertThat(outcome.warnings())
        .containsExactly(
            new Compiler.Warning(
                "com/uber/A.java", 5, "[NullAway] dereferenced expression 's' is @Nullable"));
  }

  @Test
  public void aNonNullDereferenceIsNoWarning() {
    Compiler.Outcome outcome = new Compiler().runNullAway(List.of(dereference("")), LEGACY, null);
    assertWithMessage("detail: %s", outcome.detail())
        .that(outcome.status())
        .isEqualTo(Compiler.Status.OK);
    assertThat(outcome.warnings()).isEmpty();
  }

  @Test
  public void aUnitCompilesAgainstClassFilesOfAnotherUnit() throws Exception {
    Compiler compiler = new Compiler();
    Path classes = temporaryFolder.newFolder().toPath();
    boolean compiled =
        compiler.compileWithoutNullAway(
            List.of(new Source("com/uber/Lib.java", "package com.uber; public class Lib {}")),
            classes);
    assertWithMessage("compileWithoutNullAway(Lib)").that(compiled).isTrue();

    Source client = new Source("com/uber/C.java", "package com.uber; class C { Lib lib; }");
    Compiler.Outcome outcome = compiler.runNullAway(List.of(client), LEGACY, classes);
    assertWithMessage("detail: %s", outcome.detail())
        .that(outcome.status())
        .isEqualTo(Compiler.Status.OK);
  }

  @Test
  public void everyWarningPastJavacsDefaultLimitOfAHundredIsKept() {
    // One method per warning: after one dereference, NullAway treats the parameter as non-null.
    String methods =
        IntStream.range(0, 150)
            .mapToObj(i -> "  int f" + i + "(@Nullable String s) { return s.length(); }\n")
            .collect(Collectors.joining());
    Source source =
        new Source(
            "com/uber/D.java",
            "package com.uber;\nimport javax.annotation.Nullable;\nclass D {\n" + methods + "}\n");
    Compiler.Outcome outcome = new Compiler().runNullAway(List.of(source), LEGACY, null);
    assertWithMessage("detail: %s", outcome.detail()).that(outcome.warnings()).hasSize(150);
  }
}
