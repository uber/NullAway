package com.uber.nullaway;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.bugpatterns.BugChecker.VariableTreeMatcher;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.generics.TypeSubstitutionUtils;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;

public class TypeSubstitutionUtilsTest {

  @Test
  public void restoreNestedAnnotationFromCapturedWildcardBound() {
    CompilationTestHelper.newInstance(NestedAnnotationRestorationChecker.class, getClass())
        .setArgs("-XDaddTypeAnnotationsToSymbol=true")
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static class Holder<T extends @Nullable Object> {
                T get() {
                  throw new RuntimeException();
                }
              }
              static void test(Holder<? extends Box<@Nullable String>> holder) {
                // BUG: Diagnostic contains: nested nullability is restored
                Box<String> box = holder.get();
              }
            }
            """)
        .doTest();
  }

  @BugPattern(
      summary = "Checks that nested nullability is restored from a captured wildcard bound",
      severity = BugPattern.SeverityLevel.ERROR)
  public static final class NestedAnnotationRestorationChecker extends BugChecker
      implements VariableTreeMatcher {

    private static final Config CONFIG =
        new ErrorProneCLIFlagsConfig(
            ErrorProneFlags.fromMap(
                Map.of(
                    "NullAway:OnlyNullMarked", "true",
                    "NullAway:JSpecifyMode", "true")));

    /** Reports a match when restoration preserves the nullable nested type argument. */
    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      if (tree.getInitializer() == null) {
        return Description.NO_MATCH;
      }
      Type originalType = ASTHelpers.getType(tree.getInitializer());
      Type newType = ASTHelpers.getType(tree.getType());
      if (!(originalType instanceof Type.CapturedType) || !(newType instanceof Type.ClassType)) {
        return Description.NO_MATCH;
      }
      Type restoredType =
          TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
              originalType, newType, CONFIG, Collections.emptyMap());
      if (restoredType.getTypeArguments().size() != 1) {
        return Description.NO_MATCH;
      }
      Type nestedType = restoredType.getTypeArguments().get(0);
      return Nullness.hasNullableAnnotation(nestedType.getAnnotationMirrors().stream(), CONFIG)
          ? describeMatch(tree)
          : Description.NO_MATCH;
    }
  }
}
