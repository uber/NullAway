package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.ARRAY_ELEMENT;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.TYPE_ARGUMENT;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.WILDCARD_BOUND;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.LibraryModels.PolyNullLocation;
import com.uber.nullaway.LibraryModels.PolyNullLocation.Parameter;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry;
import java.util.Map;
import org.junit.Test;

/** Tests that an override mask exempts only the nullness at the modeled location. */
public class OverrideNullnessMaskTests {

  @Test
  public void maskedComparisonsPreserveUnmodeledNestedNullness() {
    CompilationTestHelper.newInstance(MaskChecker.class, getClass())
        .addSourceLines(
            "Test.java",
            """
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.function.Consumer;
            import java.util.function.Supplier;
            import org.jspecify.annotations.Nullable;
            import org.jspecify.annotations.NonNull;

            @Target(ElementType.TYPE_USE)
            @interface Unrelated {}

            class Flipped<A, B> extends ArrayList<B> {}

            class Test {
              // BUG: Diagnostic contains: compatible=true, reverse=true
              void argument(List<Object> base, List<@Nullable Object> override) {}

              // BUG: Diagnostic contains: compatible=false, reverse=false
              void argumentPreservesNested(
                  List<List<Object>> base, List<@Nullable List<@Nullable Object>> override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=true
              void array(Object[] base, @Nullable Object[] override) {}

              // BUG: Diagnostic contains: compatible=false, reverse=false
              void arrayPreservesNested(List<Object>[] base, List<@Nullable Object>[] override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=true
              void invariantArray(List<Object[]> base, List<@Nullable Object[]> override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=true
              void wildcard(Supplier<? extends Object> base,
                  Supplier<? extends @Nullable Object> override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=true
              void wildcardUnbounded(Supplier<?> base,
                  Supplier<? extends @Nullable Object> override) {}

              // BUG: Diagnostic contains: compatible=false, reverse=false
              void wildcardPreservesNested(Supplier<? extends List<Object>> base,
                  Supplier<? extends @Nullable List<@Nullable Object>> override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=true
              void lower(Consumer<? super Object> base,
                  Consumer<? super @Nullable Object> override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=true
              void covariant(List<Object> base, ArrayList<@Nullable Object> override) {}

              // BUG: Diagnostic contains: compatible=false, reverse=true
              void unmodeledArray(Object[] base, @Nullable Object[] override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=false
              void unmodeledArrayReverse(@Nullable Object[] base, Object[] override) {}

              // BUG: Diagnostic contains: compatible=false, reverse=true
              void unmodeledWildcard(Supplier<? extends Object> base,
                  Supplier<? extends @Nullable Object> override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=false
              void unmodeledWildcardReverse(Supplier<? extends @Nullable Object> base,
                  Supplier<? extends Object> override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=true
              void unrelatedRestored(List<@Unrelated Object> base,
                  List<@Nullable Object> override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=true
              void nonNullRestored(List<@NonNull Object> base, List<@Nullable Object> override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=true
              void restored(List<Object> base, List<@Nullable Object> override) {}

              // BUG: Diagnostic contains: compatible=true, reverse=true
              List<@Nullable Object> captured(List<Object> base, List<?> override) {
                return null;
              }

              // BUG: Diagnostic contains: compatible=true, reverse=true
              void substituted(Flipped<String, Object> base, List<@Nullable Object> override) {}
            }
            """)
        .doTest();
  }

  /** Compares the two declared parameter types after masking a location on the first one. */
  @BugPattern(summary = "Checks override nullness masks", severity = SUGGESTION)
  public static final class MaskChecker extends BugChecker implements BugChecker.MethodTreeMatcher {

    private final NullAway analysis =
        new NullAway(
            ErrorProneFlags.fromMap(
                Map.of(
                    "NullAway:OnlyNullMarked", "true",
                    "NullAway:JSpecifyMode", "true",
                    "NullAway:HandleWildcardGenerics", "true")));

    /** Applies the fixture's mask and reports compatibility in both comparison directions. */
    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      if (tree.getParameters().size() != 2) {
        return NO_MATCH;
      }
      String name = tree.getName().toString();
      ImmutableList<TypePathEntry> path;
      if (name.startsWith("array")) {
        path = ImmutableList.of(new TypePathEntry(ARRAY_ELEMENT, -1));
      } else if (name.startsWith("invariantArray")) {
        path =
            ImmutableList.of(
                new TypePathEntry(TYPE_ARGUMENT, 0), new TypePathEntry(ARRAY_ELEMENT, -1));
      } else if (name.startsWith("wildcard") || name.startsWith("lower")) {
        path =
            ImmutableList.of(
                new TypePathEntry(TYPE_ARGUMENT, 0),
                new TypePathEntry(WILDCARD_BOUND, name.startsWith("lower") ? 1 : 0));
      } else {
        path =
            ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, name.equals("substituted") ? 1 : 0));
      }
      Type.MethodType declared = ASTHelpers.getSymbol(tree).type.asMethodType();
      Type.MethodType masked =
          OverrideNullnessMask.apply(
              declared,
              ImmutableSet.of(
                  new PolyNullLocation(new Parameter(name.startsWith("unmodeled") ? 99 : 0), path)),
              state);
      Type base = masked.getParameterTypes().get(0);
      Type override = masked.getParameterTypes().get(1);
      if (name.equals("wildcardUnbounded")) {
        Type upperBound =
            GenericsUtils.effectiveWildcardUpperBound(
                base.getTypeArguments().head, state, analysis.getConfig(), analysis.getHandler());
        assertThat(OverrideNullnessMask.isMasked(upperBound)).isTrue();
      }
      if (name.equals("nonNullRestored") || name.equals("unrelatedRestored")) {
        base =
            TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                declared.getParameterTypes().get(0), declared.getParameterTypes().get(1),
                state.getTypes(), analysis.getConfig());
        override = declared.getParameterTypes().get(name.equals("nonNullRestored") ? 0 : 1);
      } else if (name.equals("restored")) {
        base =
            TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                base, declared.getParameterTypes().get(0), state.getTypes(), analysis.getConfig());
      } else if (name.equals("captured")) {
        base =
            TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations(
                base, state.getTypes().capture(override), state.getTypes(), analysis.getConfig());
        Type.CapturedType capture = (Type.CapturedType) base.getTypeArguments().head;
        assertThat(OverrideNullnessMask.ignoresNullness(capture.getUpperBound(), declared.restype))
            .isTrue();
        override = declared.getReturnType();
      }
      GenericsChecks checks = analysis.getGenericsChecks();
      boolean compatible = checks.subtypeParameterNullability(base, override, state);
      boolean reverse = checks.subtypeParameterNullability(override, base, state);
      return buildDescription(tree)
          .setMessage("compatible=" + compatible + ", reverse=" + reverse)
          .build();
    }
  }
}
