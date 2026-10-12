package com.uber.nullaway.generics;

import static com.google.common.truth.Truth.assertThat;
import static com.google.errorprone.BugPattern.SeverityLevel.SUGGESTION;
import static com.google.errorprone.matchers.Description.NO_MATCH;
import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;
import static com.uber.nullaway.generics.TypeSubstitutionUtils.hasNullableRoot;
import static com.uber.nullaway.generics.TypeSubstitutionUtils.removeNullableAnnotation;
import static com.uber.nullaway.generics.TypeSubstitutionUtils.restoreExplicitNullabilityAnnotations;
import static com.uber.nullaway.generics.TypeSubstitutionUtils.typeWithAnnot;

import com.google.errorprone.BugPattern;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.scanner.ErrorProneScanner;
import com.google.errorprone.scanner.ScannerSupplier;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Name;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.Test;

/** Compiler-backed proofs specific to the receiver-bound no-op guard and shared repair helper. */
public class ReceiverBoundRestorationTests {

  private static final Supplier<Type> NULLABLE_TYPE =
      Suppliers.typeFromString("org.jspecify.annotations.Nullable");
  private static final Supplier<Name> RECEIVER_NAME =
      VisitorState.memoize(state -> state.getName("receiver"));

  @Test
  public void unchangedBoundsKeepInstantiatedIdentity() {
    compile(
        """
        class Test<E> {
          Test<String> receiver;
          <T> T plain(T value) { return value; }
          <T extends @Nullable Object> T nullable(T value) { return value; }
          <T extends @NonNull Object> T nonNull(T value) { return value; }
          <T extends List<@Nullable String>> T nested(T value) { return value; }
          <T extends Comparable<T>> T recursive(T value) { return value; }
          <T extends Comparable<? super T>> T recursiveWildcard(T value) { return value; }
          <T, U extends T> U peer(T first, U value) { return value; }
          <T extends @Nullable Object, U extends @Nullable T>
              U annotatedPeer(@Nullable T first, U value) { return value; }
          <T extends Serializable & Comparable<T>> T interfaces(T value) { return value; }
          <T extends Number & Comparable<T>> T classFirst(T value) { return value; }
          <T extends @Nullable Serializable & @Nullable Comparable<T>> T nullableIntersection(T value) { return value; }
          <T extends @NonNull Serializable & @NonNull Comparable<T>> T nonNullIntersection(T value) { return value; }
          <T extends Serializable & Comparable<E>> T dependent(T value) { return value; }
        }
        """,
        ctx -> {
          Type.ForAll instantiated = ctx.memberType(ctx.receiver());
          assertThat(ctx.restoreBounds(instantiated)).isSameInstanceAs(instantiated);
        });
  }

  @Test
  public void changedReceiverBoundDetachesWithoutMutatingAnnotatedPeers() {
    compile(
        """
        class Test<E> {
          Test<@Nullable String> receiver;
          <T extends List<E>, U extends @Nullable T>
              U peer(@Nullable T first, U value) { return value; }
        }
        """,
        ctx -> {
          Type declaration = ctx.method().type;
          // Model annotation loss independently of which metadata the current javac retains.
          Type strippedReceiver =
              TYPE_METADATA_BUILDER.createClassType(
                  ctx.receiver(),
                  ctx.receiver().getEnclosingType(),
                  List.of(
                      removeNullableAnnotation(
                          ctx.receiver().getTypeArguments().head, ctx.config())));
          Type.ForAll instantiated = ctx.memberType(strippedReceiver);
          Map<Type.TypeVar, Type> bounds = new LinkedHashMap<>();
          for (Type type : List.of(declaration, instantiated)) {
            for (Type variable : ((Type.ForAll) type).tvars) {
              bounds.put((Type.TypeVar) variable, upper(variable));
            }
          }
          Type.TypeVar originalParameter = (Type.TypeVar) instantiated.asMethodType().argtypes.head;
          bounds.put(originalParameter, originalParameter.getUpperBound());
          Type.ForAll restored = ctx.restoreBounds(instantiated);
          assertThat(restored).isNotSameInstanceAs(instantiated);
          for (int i = 0; i < restored.tvars.size(); i++) {
            assertThat(restored.tvars.get(i)).isNotSameInstanceAs(instantiated.tvars.get(i));
            assertThat(restored.tvars.get(i).tsym).isSameInstanceAs(instantiated.tvars.get(i).tsym);
          }
          Type bound = upper(restored.tvars.head);
          assertThat(bound.tsym).isSameInstanceAs(upper(instantiated.tvars.head).tsym);
          assertThat(hasNullableRoot(bound.getTypeArguments().head, ctx.config())).isTrue();
          for (Type peer :
              List.of(upper(restored.tvars.get(1)), restored.asMethodType().argtypes.head)) {
            assertThat(upper(peer)).isSameInstanceAs(bound);
            assertThat(hasNullableRoot(peer, ctx.config())).isTrue();
          }
          assertThat(restored.asMethodType().argtypes.head).isNotSameInstanceAs(originalParameter);
          assertThat(ctx.method().type).isSameInstanceAs(declaration);
          bounds.forEach(
              (variable, original) ->
                  assertThat(variable.getUpperBound()).isSameInstanceAs(original));
        });
  }

  @Test
  public void repairIsIdempotentWithoutLosingSyntheticProvenanceOrImplicitCaptureBounds() {
    compile(
        """
        class Test<E> {
          @Marker Test<?> receiver;
          <T> void probe() {}
        }
        """,
        ctx -> {
          Config config = ctx.config();
          Type target = ctx.receiver();
          Type.CapturedType capture =
              (Type.CapturedType) ctx.state().getTypes().capture(target).getTypeArguments().head;
          Type originalUpper = capture.getUpperBound();
          Type originalWildcard = capture.wildcard;
          Type originalFormalUpper = capture.wildcard.bound.getUpperBound();
          Type nullable = GenericsChecks.getSyntheticNullableAnnotType(ctx.state());
          Type nonNull = GenericsChecks.getSyntheticNonNullAnnotType(ctx.state());
          for (boolean nullableSource : List.of(true, false)) {
            Type annotation = nullableSource ? nullable : nonNull;
            Type source = typeWithAnnot(target, annotation);
            Type updated = restoreExplicitNullabilityAnnotations(source, target, config);
            assertThat(updated).isNotSameInstanceAs(target);
            assertThat(restoreExplicitNullabilityAnnotations(source, updated, config))
                .isSameInstanceAs(updated);
            assertThat(restoreExplicitNullabilityAnnotations(target, updated, config))
                .isSameInstanceAs(updated);
            assertThat(updated.getAnnotationMirrors().size())
                .isEqualTo(target.getAnnotationMirrors().size() + 1);
            assertThat(
                    Nullness.hasNonNullAnnotation(updated.getAnnotationMirrors().stream(), config))
                .isEqualTo(!nullableSource);
            if (nullableSource) {
              Type explicit = typeWithAnnot(source, NULLABLE_TYPE.get(ctx.state()));
              assertThat(restoreExplicitNullabilityAnnotations(source, explicit, config))
                  .isNotSameInstanceAs(explicit);
            }
            Type captureSource = typeWithAnnot(ctx.state().getSymtab().objectType, annotation);
            Type.CapturedType repaired =
                (Type.CapturedType)
                    restoreExplicitNullabilityAnnotations(captureSource, capture, config);
            assertThat(restoreExplicitNullabilityAnnotations(captureSource, repaired, config))
                .isSameInstanceAs(repaired);
            assertThat(repaired.getAnnotationMirrors()).isEmpty();
            assertThat(hasNullableRoot(repaired.wildcard.bound.getUpperBound(), config))
                .isEqualTo(nullableSource);
            assertThat(
                    Nullness.hasNonNullAnnotation(
                        repaired.getUpperBound().getAnnotationMirrors().stream(), config))
                .isEqualTo(!nullableSource);
          }
          assertThat(capture.getUpperBound()).isSameInstanceAs(originalUpper);
          assertThat(capture.wildcard).isSameInstanceAs(originalWildcard);
          assertThat(capture.wildcard.bound.getUpperBound()).isSameInstanceAs(originalFormalUpper);
        });
  }

  /** Runs the supplied assertions on each generic method in a compiler-attributed fixture. */
  private void compile(String source, Consumer<Context> assertions) {
    CompilationTestHelper.newInstance(
            ScannerSupplier.fromScanner(new ErrorProneScanner(new MethodAssertions(assertions))),
            getClass())
        .addSourceLines(
            "Test.java",
            "import java.util.List;",
            "import java.io.Serializable;",
            "import java.lang.annotation.ElementType;",
            "import java.lang.annotation.Target;",
            "import org.jspecify.annotations.Nullable;",
            "import org.checkerframework.checker.nullness.qual.NonNull;",
            "@Target(ElementType.TYPE_USE) @interface Marker {}",
            source)
        .doTest();
  }

  /** Adapts per-test assertions to an active javac compilation without scenario branching. */
  @BugPattern(summary = "Checks receiver bound restoration identity", severity = SUGGESTION)
  public static final class MethodAssertions extends BugChecker
      implements BugChecker.MethodTreeMatcher {
    private final Consumer<Context> assertions;

    private MethodAssertions(Consumer<Context> assertions) {
      this.assertions = assertions;
    }

    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
      Symbol.MethodSymbol method = ASTHelpers.getSymbol(tree);
      if (!(method.type instanceof Type.ForAll)) {
        return NO_MATCH;
      }
      Config config =
          new NullAway(
                  ErrorProneFlags.builder()
                      .putFlag("NullAway:AnnotatedPackages", "com.uber")
                      .putFlag("NullAway:JSpecifyMode", "true")
                      .putFlag("NullAway:JSpecifyExperimental", "true")
                      .build())
              .getConfig();
      Type receiver = method.owner.members().findFirst(RECEIVER_NAME.get(state)).type;
      assertions.accept(new Context(state, method, receiver, config));
      return NO_MATCH;
    }
  }

  /** Compiler context shared by the identity, isolation, and idempotence assertions. */
  private record Context(
      VisitorState state, Symbol.MethodSymbol method, Type receiver, Config config) {
    /** Computes the annotated signature supplied to the receiver-bound repair stage. */
    private Type.ForAll memberType(Type receiver) {
      return (Type.ForAll)
          restoreExplicitNullabilityAnnotations(
              method.type, state.getTypes().memberType(receiver, method), config);
    }

    /** Invokes the private stage only to assert identity of the exact supplied ForAll. */
    private Type.ForAll restoreBounds(Type.ForAll instantiated) {
      try {
        Method restoration =
            TypeSubstitutionUtils.class.getDeclaredMethod(
                "restoreReceiverInstantiatedMethodBounds",
                Types.class,
                Type.class,
                Symbol.class,
                Type.class,
                Type.class,
                Config.class);
        restoration.setAccessible(true);
        return (Type.ForAll)
            restoration.invoke(
                null, state.getTypes(), receiver, method, method.type, instantiated, config);
      } catch (ReflectiveOperationException exception) {
        throw new LinkageError("Could not exercise receiver-bound restoration", exception);
      }
    }
  }

  /** Reads a method variable's upper bound, including annotated peer occurrences. */
  private static Type upper(Type variable) {
    return ((Type.TypeVar) variable).getUpperBound();
  }
}
