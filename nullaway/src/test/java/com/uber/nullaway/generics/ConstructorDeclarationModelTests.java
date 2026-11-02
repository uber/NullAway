package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.scanner.ScannerSupplier;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.ListBuffer;
import com.uber.nullaway.LibraryModels.PolyNullLocation;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/** Constructor models must receive the declaration selected by the ordinary checking path. */
public class ConstructorDeclarationModelTests {
  @Test
  public void modelsUseDeclarationRatherThanAnonymousForwarder() {
    CompilationTestHelper.newInstance(
            ScannerSupplier.fromBugCheckerClasses(DeclarationChecker.class), getClass())
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of("-XepOpt:NullAway:OnlyNullMarked=true")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Modeled { Modeled(List<Object> values) {} }
              static class ModeledGeneric {
                <T> ModeledGeneric(T first, List<Object> values) {}
              }
              static class Plain { Plain(List<Object> values) {} }
              static <T> T echo(T value) { return value; }
              void ordinary(List<@Nullable Object> values) { new Modeled(values); }
              void anonymous(List<@Nullable Object> values) { new Modeled(values) {}; }
              void explicitConstructorWitness(List<@Nullable Object> values) {
                new <String> ModeledGeneric("value", values);
              }
              void anonymousExplicitWitness(List<@Nullable Object> values) {
                new <String> ModeledGeneric("value", values) {};
              }
              void ordinaryModelStillChecksNestedTypes(List<Object> values) {
                // BUG: Diagnostic contains: incompatible types
                new Modeled(values);
              }
              void anonymousModelStillChecksNestedTypes(List<Object> values) {
                // BUG: Diagnostic contains: incompatible types
                new Modeled(values) {};
              }
              void unmodeledConstructorIsUnchanged(List<Object> values) { new Plain(values); }
              void genericMethodModelIsUnchanged() { Test.<Object>echo("value"); }
            }
            """)
        .doTest();
  }

  /**
   * Invokes checking with an independent declaration; the name also enables NullAway's ordinary
   * compatibility descriptions in this isolated checker.
   */
  @SuppressWarnings("BugPatternNaming") // Internal compatibility reports use NullAway's name.
  @BugPattern(
      name = "NullAway",
      summary = "Checks constructor model symbol identity",
      severity = SUGGESTION)
  public static final class DeclarationChecker extends BugChecker
      implements BugChecker.NewClassTreeMatcher, BugChecker.MethodInvocationTreeMatcher {
    /** Checks both the selected symbol and the application of its nested-parameter model. */
    @Override
    public Description matchNewClass(NewClassTree tree, VisitorState state) {
      Type ownerType = ASTHelpers.getType(tree.getIdentifier());
      if (ownerType == null
          || !(ownerType.tsym instanceof Symbol.ClassSymbol owner)
          || !(owner.getSimpleName().toString().startsWith("Modeled")
              || owner.getSimpleName().contentEquals("Plain"))) {
        return NO_MATCH;
      }
      Symbol.MethodSymbol declaration = null;
      for (Symbol member : owner.members().getSymbols()) {
        if (member instanceof Symbol.MethodSymbol method && method.isConstructor()) {
          assertThat(declaration).isNull();
          declaration = method;
        }
      }
      assertThat(declaration).isNotNull();
      if (tree.getClassBody() != null) {
        assertThat(ASTHelpers.getSymbol(tree)).isNotSameInstanceAs(declaration);
      } else {
        assertThat(ASTHelpers.getSymbol(tree)).isSameInstanceAs(declaration);
      }
      NullAway analysis = new NullAway(state.errorProneOptions().getFlags());
      RecordingHandler handler = new RecordingHandler(declaration);
      GenericsChecks checks = new GenericsChecks(analysis, analysis.getConfig(), handler);
      checks.compareGenericTypeParameterNullabilityForCall(declaration, tree, state);
      assertThat(handler.calls).isGreaterThan(0);
      return NO_MATCH;
    }

    /** Keeps generic-method substitution and nullable-return modeling on their original path. */
    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      if (method == null || !method.getSimpleName().contentEquals("echo")) {
        return NO_MATCH;
      }
      NullAway analysis = new NullAway(state.errorProneOptions().getFlags());
      RecordingHandler handler = new RecordingHandler(method);
      GenericsChecks checks = new GenericsChecks(analysis, analysis.getConfig(), handler);
      assertThat(
              checks.getGenericReturnNullnessAtInvocation(
                  method, tree, state.getPath(), state, false))
          .isEqualTo(Nullness.NULLABLE);
      assertThat(handler.calls).isGreaterThan(0);
      return NO_MATCH;
    }
  }

  /** Fails on a forwarding symbol even when its erased parameter types happen to match. */
  private static final class RecordingHandler implements Handler {
    private final Symbol.MethodSymbol expected;
    private int calls;

    RecordingHandler(Symbol.MethodSymbol expected) {
      this.expected = expected;
    }

    /** Records identity before returning a declaration-keyed model used by actual checking. */
    @Override
    public Type.MethodType onOverrideMethodType(
        Symbol.MethodSymbol method,
        Type.MethodType type,
        VisitorState state,
        @Nullable MethodInvocationTree invocation) {
      calls++;
      assertThat(method).isSameInstanceAs(expected);
      if (!method.isConstructor()) {
        assertThat(invocation).isNotNull();
        return new Type.MethodType(
            type.argtypes,
            TypeSubstitutionUtils.typeWithAnnot(
                type.restype, GenericsChecks.getSyntheticNullableAnnotType(state)),
            type.thrown,
            type.tsym);
      }
      assertThat(invocation).isNull();
      if (!method.owner.getSimpleName().toString().startsWith("Modeled")) {
        return type;
      }
      ListBuffer<Type> parameters = new ListBuffer<>();
      for (Type parameter : type.argtypes) {
        if (parameter instanceof Type.ClassType clazz && !clazz.getTypeArguments().isEmpty()) {
          Type argument =
              TypeSubstitutionUtils.typeWithAnnot(
                  clazz.getTypeArguments().head,
                  GenericsChecks.getSyntheticNullableAnnotType(state));
          parameter =
              TypeMetadataBuilder.TYPE_METADATA_BUILDER.createClassType(
                  clazz, clazz.getEnclosingType(), com.sun.tools.javac.util.List.of(argument));
        }
        parameters.append(parameter);
      }
      return new Type.MethodType(parameters.toList(), type.restype, type.thrown, type.tsym);
    }

    /** NewClass checking must never consult invocation-only polymorphic-nullness models. */
    @Override
    public ImmutableSet<PolyNullLocation> onGetPolyNullLocations(
        Symbol.MethodSymbol method, VisitorState state) {
      assertThat(method.isConstructor()).isFalse();
      return ImmutableSet.of();
    }
  }
}
