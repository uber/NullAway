package com.uber.nullaway.generics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Compiler-backed proofs of array annotation dimensions, independent of the legacy call repair. */
public class CapturedArrayInferenceTests {

  private static final ThreadLocal<Integer> ASSERTIONS = ThreadLocal.withInitial(() -> 0);

  @Test
  public void nullableArrayRootDoesNotBecomeNullableCapturedComponent() {
    checkFixture("E @Nullable []");
  }

  @Test
  public void nullableCapturedComponentIsRetained() {
    checkFixture("@Nullable E []");
  }

  @Test
  public void nullableArrayRootAndCapturedComponentAreIndependent() {
    checkFixture("@Nullable E @Nullable []");
  }

  /** Compiles a wildcard receiver's real generic array field and checks its inferred signature. */
  private void checkFixture(String fieldType) {
    ASSERTIONS.set(0);
    try {
      CompilationTestHelper.newInstance(CapturedArrayChecker.class, getClass())
          .setArgs(
              JSpecifyJavacConfig.withJSpecifyModeArgs(
                  List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
          .addSourceLines(
              "Test.java",
              "package com.uber;",
              "import org.jspecify.annotations.Nullable;",
              "class Test {",
              "  static class Buffer<E> { " + fieldType + " producerBuffer; }",
              "  static <T> T requireNonNull(@Nullable T value) { throw new AssertionError(); }",
              "  void test(Buffer<?> buffer) {",
              "    Object[] elements = requireNonNull(buffer.producerBuffer);",
              "  }",
              "}")
          .doTest();
      assertEquals(1, ASSERTIONS.get().intValue());
    } finally {
      ASSERTIONS.remove();
    }
  }

  /**
   * Exercises substitution and the diagnostic overlay without consulting actual-argument repair.
   */
  @BugPattern(
      summary = "Checks captured array inference dimensions",
      severity = BugPattern.SeverityLevel.SUGGESTION)
  public static final class CapturedArrayChecker extends BugChecker
      implements BugChecker.MethodInvocationTreeMatcher {

    /** Checks both Java substitution and the overlay of an attributed or misplaced signature. */
    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      if (!method.getSimpleName().contentEquals("requireNonNull")) {
        return Description.NO_MATCH;
      }
      Config config =
          new NullAway(
                  ErrorProneFlags.fromMap(
                      Map.of(
                          "NullAway:AnnotatedPackages", "com.uber",
                          "NullAway:JSpecifyMode", "true",
                          "NullAway:JSpecifyExperimental", "true")))
              .getConfig();
      Type.MethodType declared = ((Type.ForAll) method.type).qtype.asMethodType();
      Type.MethodType attributed = ASTHelpers.getType(tree.getMethodSelect()).asMethodType();
      Type.ArrayType field = (Type.ArrayType) ASTHelpers.getSymbol(tree.getArguments().get(0)).type;
      // Match getTreeType's field handling: javac's expression type can omit the declaration's
      // component annotation after capture conversion of the wildcard receiver.
      Type.ArrayType actual =
          (Type.ArrayType)
              TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                  field, ASTHelpers.getType(tree.getArguments().get(0)), config);
      Type.CapturedType component = (Type.CapturedType) actual.elemtype;
      assertEquals(
          TypeSubstitutionUtils.hasNullableRoot(field, config),
          TypeSubstitutionUtils.hasNullableRoot(actual, config));
      assertEquals(
          TypeSubstitutionUtils.hasNullableRoot(field.elemtype, config),
          TypeSubstitutionUtils.hasNullableRoot(component, config));
      Type upperBound = component.getUpperBound();
      Type.WildcardType wildcard = component.wildcard;
      Type inferred =
          TypeSubstitutionUtils.typeWithAnnot(
              actual, GenericsChecks.getSyntheticNonNullAnnotType(state));
      Map<Element, Type> inferredTypes = Map.of(method.getTypeParameters().get(0), inferred);
      Type.MethodType substituted =
          (Type.MethodType)
              TypeSubstitutionUtils.substituteTypeVariables(
                  declared, inferredTypes, state.getTypes(), config);
      Type.MethodType overlaid =
          TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
              attributed, declared, inferredTypes, state, config);
      checkSignature(substituted, actual, config);
      checkSignature(overlaid, actual, config);
      checkCaptureState(component, ((Type.ArrayType) substituted.argtypes.head).elemtype);
      checkCaptureState(component, ((Type.ArrayType) substituted.restype).elemtype);
      checkCaptureState(
          ((Type.ArrayType) attributed.argtypes.head).elemtype,
          ((Type.ArrayType) overlaid.argtypes.head).elemtype);
      checkCaptureState(
          ((Type.ArrayType) attributed.restype).elemtype,
          ((Type.ArrayType) overlaid.restype).elemtype);
      // Model the dropped/misplaced component metadata from the cleanup failure. The real
      // field supplies the authoritative inferred array; both views retain the same capture.
      Type wrongComponent =
          TypeSubstitutionUtils.typeWithAnnot(
              component,
              TypeSubstitutionUtils.hasNullableRoot(component, config)
                  ? GenericsChecks.getSyntheticNonNullAnnotType(state)
                  : GenericsChecks.getSyntheticNullableAnnotType(state));
      Type.ArrayType misplaced =
          TypeMetadataBuilder.TYPE_METADATA_BUILDER.createArrayType(actual, wrongComponent);
      Type.MethodType misplacedSignature =
          new Type.MethodType(
              com.sun.tools.javac.util.List.of(misplaced),
              misplaced,
              attributed.thrown,
              attributed.tsym);
      Type.MethodType corrected =
          TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
              misplacedSignature, declared, inferredTypes, state, config);
      checkSignature(corrected, actual, config);
      checkCaptureState(wrongComponent, ((Type.ArrayType) corrected.argtypes.head).elemtype);
      checkCaptureState(wrongComponent, ((Type.ArrayType) corrected.restype).elemtype);
      // A distinct capture of the same wildcard does not establish positional alignment.
      MemberSelectTree fieldAccess = (MemberSelectTree) tree.getArguments().get(0);
      Type.CapturedType differentCapture =
          (Type.CapturedType)
              state
                  .getTypes()
                  .capture(ASTHelpers.getType(fieldAccess.getExpression()))
                  .getTypeArguments()
                  .head;
      assertFalse(differentCapture.tsym.equals(component.tsym));
      Type.ArrayType unrelated =
          TypeMetadataBuilder.TYPE_METADATA_BUILDER.createArrayType(actual, differentCapture);
      Type.MethodType unaligned =
          TypeSubstitutionUtils.updateMethodTypeWithInferredNullability(
              misplacedSignature,
              declared,
              Map.of(method.getTypeParameters().get(0), unrelated),
              state,
              config);
      assertSame(wrongComponent, ((Type.ArrayType) unaligned.argtypes.head).elemtype);
      assertSame(wrongComponent, ((Type.ArrayType) unaligned.restype).elemtype);
      assertSame(upperBound, component.getUpperBound());
      assertSame(wildcard, component.wildcard);
      ASSERTIONS.set(ASSERTIONS.get() + 1);
      return Description.NO_MATCH;
    }

    /**
     * Verifies that an annotation overlay leaves the target's capture identity and bounds intact.
     */
    private static void checkCaptureState(Type original, Type updated) {
      Type.CapturedType expected = (Type.CapturedType) original;
      Type.CapturedType capture = (Type.CapturedType) updated;
      assertSame(expected.tsym, capture.tsym);
      assertSame(expected.baseType(), capture.baseType());
      assertSame(expected.lower, capture.lower);
      assertSame(expected.wildcard, capture.wildcard);
      assertSame(expected.getUpperBound(), capture.getUpperBound());
    }

    /** Verifies the parameter projection changes only the array root, not its capture or shape. */
    private static void checkSignature(
        Type.MethodType signature, Type.ArrayType actual, Config config) {
      Type.ArrayType parameter = (Type.ArrayType) signature.argtypes.head;
      Type.ArrayType result = (Type.ArrayType) signature.restype;
      assertTrue(TypeSubstitutionUtils.hasNullableRoot(parameter, config));
      assertFalse(TypeSubstitutionUtils.hasNullableRoot(result, config));
      assertEquals(
          TypeSubstitutionUtils.hasNullableRoot(actual.elemtype, config),
          TypeSubstitutionUtils.hasNullableRoot(parameter.elemtype, config));
      assertEquals(
          TypeSubstitutionUtils.hasNullableRoot(actual.elemtype, config),
          TypeSubstitutionUtils.hasNullableRoot(result.elemtype, config));
      assertSame(actual.elemtype.baseType(), parameter.elemtype.baseType());
      assertSame(actual.elemtype.baseType(), result.elemtype.baseType());
      assertSame(actual.elemtype.tsym, parameter.elemtype.tsym);
      assertSame(actual.elemtype.tsym, result.elemtype.tsym);
    }
  }
}
