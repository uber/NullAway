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
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import org.junit.Test;

/** Compiler-backed coverage for batched matching without losing occurrences or caller symbols. */
public class BatchedTypeSubstitutionTests {

  private static final ThreadLocal<Integer> ASSERTIONS = ThreadLocal.withInitial(() -> 0);

  @Test
  public void batchedMatchingRetainsAnnotatedOccurrencesAndIntersectionBounds() {
    ASSERTIONS.set(0);
    try {
      CompilationTestHelper.newInstance(BatchedSubstitutionChecker.class, getClass())
          .setArgs(
              JSpecifyJavacConfig.withJSpecifyModeArgs(
                  List.of("-XepOpt:NullAway:AnnotatedPackages=com.uber")))
          .addSourceLines(
              "Test.java",
              """
              package com.uber;
              import java.util.Map;
              import org.jspecify.annotations.Nullable;
              class Test<A extends @Nullable Object, B extends A> {
                interface First<T extends @Nullable Object> {}
                interface Second<T extends @Nullable Object> {}
                <C extends First<@Nullable A> & Second<B>>
                    void fixture(Map<A, @Nullable A> values, B value, C symbolic) {}
              }
              """)
          .doTest();
      assertEquals(1, ASSERTIONS.get().intValue());
    } finally {
      ASSERTIONS.remove();
    }
  }

  /**
   * Runs assertions on real annotated type-variable occurrences and detached substitution results.
   */
  @BugPattern(
      summary = "Checks batched type-variable substitution",
      severity = BugPattern.SeverityLevel.SUGGESTION)
  public static final class BatchedSubstitutionChecker extends BugChecker
      implements BugChecker.MethodTreeMatcher {

    /**
     * Compares batched and singleton matching, then verifies substitution preserves source state.
     */
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      if (!tree.getName().contentEquals("fixture")) {
        return Description.NO_MATCH;
      }
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      Type.ForAll declaration = (Type.ForAll) method.type;
      Symbol.ClassSymbol owner = (Symbol.ClassSymbol) method.owner;
      Element firstSymbol = owner.getTypeParameters().get(0);
      Element secondSymbol = owner.getTypeParameters().get(1);
      Set<Element> symbols = new LinkedHashSet<>();
      symbols.add(firstSymbol);
      symbols.add(secondSymbol);
      symbols.add(method.getTypeParameters().get(0));
      TypeVarWithSymbolCollector batched = new TypeVarWithSymbolCollector(symbols);
      declaration.accept(batched, null);
      Set<Type.TypeVar> singletonMatches = new LinkedHashSet<>();
      for (Element symbol : symbols) {
        TypeVarWithSymbolCollector singleton = new TypeVarWithSymbolCollector(symbol);
        declaration.accept(singleton, null);
        singletonMatches.addAll(singleton.getMatches());
      }
      assertEquals(singletonMatches, batched.getMatches());
      Config config =
          new NullAway(
                  ErrorProneFlags.fromMap(
                      Map.of(
                          "NullAway:AnnotatedPackages", "com.uber",
                          "NullAway:JSpecifyMode", "true",
                          "NullAway:JSpecifyExperimental", "true")))
              .getConfig();
      assertTrue(
          batched.getMatches().stream()
              .anyMatch(
                  occurrence ->
                      occurrence.asElement().equals(firstSymbol)
                          && TypeSubstitutionUtils.hasNullableRoot(occurrence, config)));
      assertTrue(
          batched.getMatches().stream()
              .anyMatch(
                  occurrence ->
                      occurrence.asElement().equals(firstSymbol)
                          && !TypeSubstitutionUtils.hasNullableRoot(occurrence, config)));
      Type.TypeVar callerVariable = (Type.TypeVar) declaration.tvars.get(0);
      Type callerBound = callerVariable.getUpperBound();
      Map<Element, Type> replacements =
          Map.of(
              firstSymbol,
              state.getSymtab().stringType,
              secondSymbol,
              state.getSymtab().stringType);
      Type substituted =
          TypeSubstitutionUtils.substituteTypeVariables(
              declaration.qtype, replacements, state.getTypes(), config);
      Type map = substituted.getParameterTypes().get(0);
      assertSame(state.getSymtab().stringType.tsym, map.getTypeArguments().get(0).tsym);
      assertFalse(TypeSubstitutionUtils.hasNullableRoot(map.getTypeArguments().get(0), config));
      assertSame(state.getSymtab().stringType.tsym, map.getTypeArguments().get(1).tsym);
      assertTrue(TypeSubstitutionUtils.hasNullableRoot(map.getTypeArguments().get(1), config));
      // Matching variables in C's bounds must not replace C or mutate its declaration contract.
      assertSame(callerVariable, substituted.getParameterTypes().get(2));
      assertSame(callerBound, callerVariable.getUpperBound());
      assertSame(declaration, method.type);
      Type intersection =
          TypeSubstitutionUtils.substituteTypeVariables(
              callerBound, replacements, state.getTypes(), config);
      assertTrue(intersection instanceof Type.IntersectionClassType);
      Type.IntersectionClassType bounds = (Type.IntersectionClassType) intersection;
      Type firstArgument = bounds.getExplicitComponents().get(0).getTypeArguments().get(0);
      Type secondArgument = bounds.getExplicitComponents().get(1).getTypeArguments().get(0);
      assertSame(state.getSymtab().stringType.tsym, firstArgument.tsym);
      assertSame(state.getSymtab().stringType.tsym, secondArgument.tsym);
      assertTrue(TypeSubstitutionUtils.hasNullableRoot(firstArgument, config));
      assertFalse(TypeSubstitutionUtils.hasNullableRoot(secondArgument, config));
      assertSame(callerBound, callerVariable.getUpperBound());
      ASSERTIONS.set(ASSERTIONS.get() + 1);
      return Description.NO_MATCH;
    }
  }
}
