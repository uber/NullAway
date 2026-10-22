package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.LibraryModels.PolyNullLocation;
import com.uber.nullaway.LibraryModels.PolyNullLocation.Return;
import com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry;
import com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests PolyNull annotation application using types from an active javac compilation. */
@RunWith(JUnit4.class)
public class PolyNullInferenceTests {

  @Test
  public void returnOnlyAnnotationUpdateIsRetained() {
    CompilationTestHelper.newInstance(ReturnOnlyAnnotationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.Nullable;

            class Test {
              List<@Nullable Object> returnOnly(Object unused) {
                throw new AssertionError();
              }
            }
            """)
        .doTest();
  }

  /** Checks that changing only a modeled return location produces an updated method type. */
  @BugPattern(summary = "Checks return-only PolyNull annotation application", severity = SUGGESTION)
  public static final class ReturnOnlyAnnotationChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {

    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      if (!tree.getName().contentEquals("returnOnly")) {
        return NO_MATCH;
      }
      Type.MethodType original = ASTHelpers.getSymbol(tree).type.asMethodType();
      Type annotation = GenericsChecks.getSyntheticNonNullAnnotType(state);
      Type.MethodType updated =
          PolyNullInference.applyToMethodType(
              original,
              ImmutableSet.of(
                  new PolyNullLocation(
                      new Return(), ImmutableList.of(new TypePathEntry(Kind.TYPE_ARGUMENT, 0)))),
              annotation);
      assertThat(updated.getReturnType().getTypeArguments().head.getAnnotationMirrors().head.type)
          .isSameInstanceAs(annotation);
      assertThat(updated.getParameterTypes().head)
          .isSameInstanceAs(original.getParameterTypes().head);
      return NO_MATCH;
    }
  }
}
