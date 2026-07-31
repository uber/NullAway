package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class TypeSubstitutionUtilsTests {

  @Test
  public void replaceUnboundedWildcardUpperBoundDoesNotMutateFormalTypeVariable() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T> {
              Test<?> typeVarField;
            }
            """)
        .doTest();
  }

  @Test
  public void replaceCapturedTypeWildcardReturnsDetachedCapture() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T> {
              Test<?> capturedTypeField;
            }
            """)
        .doTest();
  }

  @Test
  public void cloneTypeWithMetadataReturnsDetachedMutableTypes() {
    CompilationTestHelper.newInstance(TypeCopyIsolationChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            class Test<T> {
              Test<?> typeVarMetadataField;
              Test<?> capturedTypeMetadataField;
            }
            """)
        .doTest();
  }

  /** Checker that exercises the mutable javac types used by the replacement helpers. */
  @BugPattern(summary = "Checks that copied javac types are detached", severity = SUGGESTION)
  public static final class TypeCopyIsolationChecker extends BugChecker
      implements BugChecker.VariableTreeMatcher {

    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
      String fieldName = tree.getName().toString();
      if (!fieldName.equals("typeVarField")
          && !fieldName.equals("capturedTypeField")
          && !fieldName.equals("typeVarMetadataField")
          && !fieldName.equals("capturedTypeMetadataField")) {
        return NO_MATCH;
      }
      Type.ClassType fieldType = (Type.ClassType) ASTHelpers.getType(tree);
      Type.WildcardType sourceWildcard = (Type.WildcardType) fieldType.getTypeArguments().head;
      Type.TypeVar formalTypeVariable = (Type.TypeVar) fieldType.tsym.type.getTypeArguments().head;
      Type originalUpperBound = formalTypeVariable.getUpperBound();
      Type updatedUpperBound =
          TypeSubstitutionUtils.typeWithAnnot(
              originalUpperBound, GenericsChecks.getSyntheticNullableAnnotType(state));
      Type.WildcardType unboundedWildcard =
          new Type.WildcardType(
              sourceWildcard.type, BoundKind.UNBOUND, sourceWildcard.tsym, formalTypeVariable);

      if (fieldName.equals("typeVarField")) {
        Type.WildcardType updatedWildcard =
            TypeSubstitutionUtils.replaceUnboundedWildcardUpperBound(
                unboundedWildcard, updatedUpperBound);
        assertThat(formalTypeVariable.getUpperBound()).isSameInstanceAs(originalUpperBound);
        assertThat(updatedWildcard).isNotSameInstanceAs(unboundedWildcard);
        assertThat(updatedWildcard.bound).isNotSameInstanceAs(formalTypeVariable);
        assertThat(updatedWildcard.bound.tsym).isSameInstanceAs(formalTypeVariable.tsym);
        assertThat(updatedWildcard.bound.lower).isSameInstanceAs(formalTypeVariable.lower);
        assertThat(updatedWildcard.bound.getUpperBound()).isSameInstanceAs(updatedUpperBound);
      } else if (fieldName.equals("typeVarMetadataField")) {
        Type.TypeVar updatedTypeVariable =
            (Type.TypeVar)
                TypeSubstitutionUtils.typeWithAnnot(
                    formalTypeVariable, GenericsChecks.getSyntheticNullableAnnotType(state));
        updatedTypeVariable.setUpperBound(updatedUpperBound);
        assertThat(formalTypeVariable.getUpperBound()).isSameInstanceAs(originalUpperBound);
        assertThat(updatedTypeVariable.getUpperBound()).isSameInstanceAs(updatedUpperBound);
        assertThat(updatedTypeVariable.baseType()).isSameInstanceAs(formalTypeVariable.baseType());
        assertThat(formalTypeVariable.getAnnotationMirrors()).isEmpty();
        assertThat(updatedTypeVariable.getAnnotationMirrors()).isNotEmpty();
      } else {
        Symbol owner = ASTHelpers.getSymbol(tree);
        Type.CapturedType capturedType =
            new Type.CapturedType(
                formalTypeVariable.tsym.name,
                owner,
                originalUpperBound,
                state.getSymtab().botType,
                unboundedWildcard);
        if (fieldName.equals("capturedTypeField")) {
          Type.WildcardType replacementWildcard =
              new Type.WildcardType(updatedUpperBound, BoundKind.EXTENDS, sourceWildcard.tsym);
          Type.CapturedType updatedCapture =
              TypeSubstitutionUtils.replaceCapturedTypeWildcard(capturedType, replacementWildcard);
          assertThat(updatedCapture).isNotSameInstanceAs(capturedType);
          assertThat(updatedCapture.tsym).isSameInstanceAs(capturedType.tsym);
          assertThat(updatedCapture.lower).isSameInstanceAs(capturedType.lower);
          assertThat(capturedType.wildcard).isSameInstanceAs(unboundedWildcard);
          assertThat(updatedCapture.wildcard).isSameInstanceAs(replacementWildcard);

          // A javac metadata clone delegates this setter to the original capture. Mutating the
          // replacement therefore proves that it is a genuinely detached object, not such a clone.
          updatedCapture.setUpperBound(updatedUpperBound);
          assertThat(capturedType.getUpperBound()).isSameInstanceAs(originalUpperBound);
          assertThat(updatedCapture.getUpperBound()).isSameInstanceAs(updatedUpperBound);
        } else {
          Type.CapturedType updatedCapture =
              (Type.CapturedType)
                  TypeSubstitutionUtils.typeWithAnnot(
                      capturedType, GenericsChecks.getSyntheticNullableAnnotType(state));
          updatedCapture.setUpperBound(updatedUpperBound);
          assertThat(capturedType.getUpperBound()).isSameInstanceAs(originalUpperBound);
          assertThat(updatedCapture.getUpperBound()).isSameInstanceAs(updatedUpperBound);
          assertThat(updatedCapture.baseType()).isSameInstanceAs(capturedType.baseType());
          assertThat(capturedType.getAnnotationMirrors()).isEmpty();
          assertThat(updatedCapture.getAnnotationMirrors()).isNotEmpty();
        }
      }
      return NO_MATCH;
    }
  }
}
