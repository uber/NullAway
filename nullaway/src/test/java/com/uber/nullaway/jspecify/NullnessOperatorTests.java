package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Fails when NullAway changes how it treats a value whose type is a type variable, or a wildcard
 * captured against one.
 *
 * <p>Such a value carries the JSpecify {@code NO_CHANGE} nullness operator. Wherever the same type
 * variable {@code T}, or a capture of {@code ? super T}, is expected, it is accepted: as an
 * argument, a return, a field or array store, and a field initializer. An argument is not accepted
 * where a library model requires a non-null one. A type variable that the value's type reaches only
 * through a {@code @Nullable} bound does not accept it. It stays unsafe to dereference while that
 * type variable has a nullable upper bound. An explicit {@code @Nullable}, declared on the member
 * or supplied by a library model, overrides that and is reported at every sink.
 *
 * <p>The tests that expect a diagnostic pin where the value stays nullable: a dereference,
 * unboxing, a target of a concrete type such as {@code Object}, a {@code @NonNull T} target or
 * bound, a parameter a library model makes non-null, a {@code null} assigned over it, a
 * {@code @Contract} or {@code @EnsuresNonNull} postcondition, and an explicit {@code @Nullable}.
 * They fail if the value is treated as non-null to let it reach a {@code T} sink.
 *
 * <p>The ignored test describes the defect that remains from issue #1727: a dereference of a value
 * typed by a type variable with a nullable upper bound goes unreported. It involves no wildcard.
 *
 * @see <a href="https://jspecify.dev/docs/spec/#nullness-operator">JSpecify: nullness operator</a>
 */
public class NullnessOperatorTests extends NullAwayTestsBase {

  @Test
  public void nullableMemberThroughWildcardIsRejectedAtReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface NBox<R extends @Nullable Object> {
                @Nullable R get();
              }
              static <T extends @Nullable Object> T get(NBox<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableMemberThroughWildcardIsRejectedAtArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface NBox<R extends @Nullable Object> {
                @Nullable R get();
              }
              static <T extends @Nullable Object> void sink(T value) {}
              static <T extends @Nullable Object> void pass(NBox<? extends T> box) {
                // BUG: Diagnostic contains: passing @Nullable parameter
                Test.<T>sink(box.get());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableMemberThroughWildcardIsRejectedAtFieldAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              interface NBox<R extends @Nullable Object> {
                @Nullable R get();
              }
              private T current;
              // Initializes through Box<T>, so the only report is the @Nullable read in update().
              Test(Box<T> initial) {
                current = initial.get();
              }
              void update(NBox<? extends T> box) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                current = box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableMemberThroughTypeVariableIsRejectedAtReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface NBox<R extends @Nullable Object> {
                @Nullable R get();
              }
              static <T extends @Nullable Object> T get(NBox<T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void modeledNullableThroughWildcardIsRejectedAtReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T get(Map<String, ? extends T> map) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return map.get("key");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void modeledNullableThroughWildcardIsRejectedAtArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> void sink(T value) {}
              static <T extends @Nullable Object> void pass(Map<String, ? extends T> map) {
                // BUG: Diagnostic contains: passing @Nullable parameter
                Test.<T>sink(map.get("key"));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void modeledNullableThroughTypeVariableIsRejectedAtReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T get(Map<String, T> map) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return map.get("key");
              }
            }
            """)
        .doTest();
  }

  /**
   * Repeats {@link WildcardTests#wildcardCaptureReturnWithTypeVariableUpperBound()} with the type
   * variable declared on the method rather than on the enclosing class, which reaches a different
   * substitution path.
   */
  @Test
  public void wildcardResultIsNotDereferenceable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void dereference(Box<? extends T> box) {
                // BUG: Diagnostic contains: dereferenced expression 'box.get()' is @Nullable
                box.get().hashCode();
              }
            }
            """)
        .doTest();
  }

  /**
   * A value of type {@code T} that a generic call returns or a field of type {@code T} holds is
   * non-null, like a parameter of type {@code T}, even where a wildcard result went into it. The
   * dereferences that expect no report here are the false negative of issue #1727 that {@link
   * #issue1727NullableBoundDereferenceFalseNegative()} describes, and they expect reports once it
   * is fixed. A generic call that returns its {@code T} argument also passes as non-null where
   * {@code Object} is expected.
   */
  @Test
  public void typeVariableValueTakenFromWildcardResultIsNonNull() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              interface Sink<E extends @Nullable Object> {
                void put(E e);
              }
              static <E extends @Nullable Object> E identity(E e) {
                return e;
              }
              T field;
              Test(T initial) {
                field = initial;
              }
              void dereferenceDirectly(Box<? extends T> box) {
                // BUG: Diagnostic contains: dereferenced expression 'box.get()' is @Nullable
                box.get().hashCode();
              }
              void dereferenceThroughIdentity(Box<? extends T> box) {
                identity(box.get()).hashCode();
              }
              void dereferenceLocalThroughIdentity(Box<? extends T> box) {
                T value = box.get();
                identity(value).hashCode();
              }
              void dereferenceFieldAfterStore(Box<? extends T> box) {
                field = box.get();
                field.hashCode();
              }
              Object returnDirectlyAsObject(Box<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
              Object returnThroughIdentityAsObject(Box<? extends T> box) {
                return identity(box.get());
              }
              void passThroughIdentityAsObject(Box<? extends T> box, Sink<Object> sink) {
                sink.put(identity(box.get()));
              }
              void dereferenceObjectLocalFromIdentity(Box<? extends T> box) {
                Object value = identity(box.get());
                value.hashCode();
              }
              Object objectField = new Object();
              Object[] objects = new Object[1];
              void storeThroughIdentityAsObject(Box<? extends T> box) {
                objectField = identity(box.get());
                objects[0] = identity(box.get());
              }
              void storeDirectlyAsObject(Box<? extends T> box) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                objectField = box.get();
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                objects[0] = box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noChangeValueIsNotUnboxable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <N extends @Nullable Integer> int unboxWildcardResult(Box<? extends N> box) {
                // BUG: Diagnostic contains: unboxing of a @Nullable expression
                return box.get();
              }
              static <N extends @Nullable Integer> int unboxLoopVariable(List<N> list) {
                int sum = 0;
                for (N value : list) {
                  // BUG: Diagnostic contains: unboxing of a @Nullable expression
                  sum += value;
                }
                return sum;
              }
              static <N extends Integer> int unboxNonNullBoundWildcardResult(Box<? extends N> box) {
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nonNullTypeVariableReadsIgnoreNullableUpperBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }

              static <T extends @Nullable Object> void wildcard(Box<? extends @NonNull T> box) {
                // Reading the wildcard bound must preserve T's explicit non-null projection.
                box.get().hashCode();
              }

              static <T extends @Nullable Object> void loop(Iterable<@NonNull T> values) {
                for (var value : values) {
                  // Enhanced-for reads must also honor @NonNull before following T's bound.
                  value.hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableWildcardBoundIsRejectedAtReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> T get(Box<? extends @Nullable T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
            }
            """)
        .doTest();
  }

  /**
   * A location of type {@code T} rejects a wildcard read when its type is {@code @NonNull T}, as
   * written or as a supertype's type argument supplies it, or when its declaration carries a
   * declaration annotation such as JSR-305 {@code @Nonnull}. The location may be a field, a return,
   * a parameter, or an element of a {@code T[]} or {@code T[][]}, reached through an explicit,
   * implicit, type-variable, captured, or anonymous-class receiver.
   */
  @Test
  public void nonNullTargetRejectsWildcardResult() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import javax.annotation.Nonnull;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              T plainField;
              @NonNull T typeUseField;
              @Nonnull T declarationField;
              // BUG: Diagnostic contains: guarantee @NonNull fields 'typeUseField' (line 11), 'declarationField' (line 12), 'plainArray' (line 42), 'typeUseArray' (line 43), 'plainNestedArray' (line 56), 'typeUseNestedArray' (line 57) are
              Test(Box<? extends T> box) {
                plainField = box.get();
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                typeUseField = box.get();
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                declarationField = box.get();
              }
              T plainReturn(Box<? extends T> box) {
                return box.get();
              }
              @NonNull T typeUseReturn(Box<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
              @Nonnull T declarationReturn(Box<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
              static <E extends @Nullable Object> void plainSink(E value) {}
              static <E extends @Nullable Object> void typeUseSink(@NonNull E value) {}
              static <E extends @Nullable Object> void declarationSink(@Nonnull E value) {}
              void pass(Box<? extends T> box) {
                Test.<T>plainSink(box.get());
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                Test.<T>typeUseSink(box.get());
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                Test.<T>declarationSink(box.get());
              }
              T[] plainArray;
              @NonNull T[] typeUseArray;
              void storeInArrays(Box<? extends T> box, @NonNull T[] typeUseArrayParameter) {
                plainArray[0] = box.get();
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                typeUseArray[0] = box.get();
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                this.typeUseArray[0] = box.get();
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                typeUseArrayParameter[0] = box.get();
                @NonNull T[] typeUseArrayLocal = typeUseArrayParameter;
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                typeUseArrayLocal[0] = box.get();
              }
              T[][] plainNestedArray;
              @NonNull T[][] typeUseNestedArray;
              void storeInNestedArrays(Box<? extends T> box, @NonNull T[][] typeUseNestedParameter) {
                plainNestedArray[0][0] = box.get();
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                typeUseNestedArray[0][0] = box.get();
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                (typeUseNestedArray[0])[0] = box.get();
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                typeUseNestedParameter[0][0] = box.get();
                @NonNull T[][] typeUseNestedLocal = typeUseNestedParameter;
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                typeUseNestedLocal[0][0] = box.get();
              }
              static class ArrayHolder<R extends @Nullable Object> {
                R[] inheritedArray = newArray();
                @NonNull R[] inheritedTypeUseArray = newArray();
                static <A extends @Nullable Object> A[] newArray() {
                  throw new UnsupportedOperationException();
                }
              }
              static class ArraySubclass<S extends @Nullable Object> extends ArrayHolder<S> {
                void storeInInheritedArrays(Box<? extends S> box) {
                  inheritedArray[0] = box.get();
                  this.inheritedArray[0] = box.get();
                  // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                  inheritedTypeUseArray[0] = box.get();
                }
              }
              static class Holder<H extends @Nullable Object> {
                H field;
                H[] array;
                Holder(H field, H[] array) {
                  this.field = field;
                  this.array = array;
                }
                void set(H value) {}
                @SafeVarargs
                final void setAll(H... values) {}
                static <M extends @Nullable Object> Holder<M> make(M field, M[] array) {
                  return new Holder<>(field, array);
                }
              }
              static class NonNullHolder<S extends @Nullable Object> extends Holder<@NonNull S> {
                NonNullHolder(@NonNull S field, @NonNull S[] array) {
                  super(field, array);
                }
                void storeThroughInheritedMembers(Box<? extends S> box) {
                  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                  field = box.get();
                  // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                  array[0] = box.get();
                  // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                  set(box.get());
                  // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                  setAll(box.get());
                }
              }
              static class PlainHolder<S extends @Nullable Object> extends Holder<S> {
                PlainHolder(S field, S[] array) {
                  super(field, array);
                }
                void storeThroughInheritedMembers(Box<? extends S> box) {
                  field = box.get();
                  array[0] = box.get();
                  set(box.get());
                  setAll(box.get());
                }
              }
              void storeThroughSupertypeArguments(
                  NonNullHolder<T> nonNullHolder, PlainHolder<T> plainHolder, Box<? extends T> box) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                nonNullHolder.field = box.get();
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                nonNullHolder.array[0] = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                nonNullHolder.set(box.get());
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                nonNullHolder.setAll(box.get());
                plainHolder.field = box.get();
                plainHolder.array[0] = box.get();
                plainHolder.set(box.get());
                plainHolder.setAll(box.get());
              }
              <N extends NonNullHolder<T>, P extends PlainHolder<T>> void storeThroughTypeVariableReceiver(
                  N nonNullHolder, P plainHolder, Box<? extends T> box) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                nonNullHolder.field = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                nonNullHolder.set(box.get());
                plainHolder.field = box.get();
                plainHolder.set(box.get());
              }
              void storeThroughCapturedReceiver(
                  Box<? extends NonNullHolder<T>> nonNullHolders,
                  Box<? extends PlainHolder<T>> plainHolders,
                  Box<? extends T> box) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                nonNullHolders.get().field = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                nonNullHolders.get().set(box.get());
                plainHolders.get().field = box.get();
                plainHolders.get().set(box.get());
              }
              void storeInAnonymousSubclass(@NonNull T value, @NonNull T[] values) {
                new Holder<@NonNull T>(value, values) {
                  void store(Box<? extends T> box) {
                    // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                    field = box.get();
                    // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                    set(box.get());
                  }
                };
                new Holder<T>(value, values) {
                  void store(Box<? extends T> box) {
                    field = box.get();
                    set(box.get());
                  }
                };
              }
              void storeThroughConstructorArgument(Box<? extends T> box, @NonNull T[] values) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                new Holder<@NonNull T>(box.get(), values);
                new Holder<T>(box.get(), values);
                Holder<T> inferred = new Holder<>(box.get(), values);
                new Holder<>(box.get(), values);
              }
              void storeThroughVarReceiver(@NonNull T value, @NonNull T[] values, Box<? extends T> box) {
                var nonNullHolder = new Holder<@NonNull T>(value, values);
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                nonNullHolder.field = box.get();
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                nonNullHolder.array[0] = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                nonNullHolder.set(box.get());
                var plainHolder = new Holder<T>(value, values);
                plainHolder.field = box.get();
                plainHolder.array[0] = box.get();
                plainHolder.set(box.get());
              }
              void storeThroughGenericCallReceiver(
                  @NonNull T value, @NonNull T[] values, Box<? extends T> box) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                Holder.<@NonNull T>make(value, values).field = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                Holder.<@NonNull T>make(value, values).set(box.get());
                Holder.<T>make(value, values).field = box.get();
                Holder.<T>make(value, values).set(box.get());
              }
              static class Outer<O extends @Nullable Object> {
                class Inner<I extends @Nullable Object> {
                  Inner(O value, I other) {}
                }
              }
              static class NonNullOuter<X extends @Nullable Object> extends Outer<@NonNull X> {}
              void constructInnerThroughQualifier(
                  Outer<@NonNull T> nonNullOuter,
                  NonNullOuter<T> nonNullSubOuter,
                  Outer<T> plainOuter,
                  T other,
                  Box<? extends T> box) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                nonNullSubOuter.new Inner<>(box.get(), other);
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                nonNullOuter.new Inner<>(box.get(), other);
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                nonNullOuter.new Inner<T>(box.get(), other);
                plainOuter.new Inner<>(box.get(), other);
                plainOuter.new Inner<T>(box.get(), other);
              }
              void storeThroughInferredCallReceiver(
                  @NonNull T value, @NonNull T[] values, Box<? extends T> box) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                Holder.make(value, values).field = box.get();
                // BUG: Diagnostic contains: Writing @Nullable expression into array with @NonNull contents
                Holder.make(value, values).array[0] = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                Holder.make(value, values).set(box.get());
              }
            }
            """)
        .doTest();
  }

  /**
   * Repeats the nullable extends-bound case of {@link WildcardTests#wildcardCaptureLocals()} with a
   * wildcard bounded by a type variable rather than by {@code @Nullable Object}.
   */
  @Test
  public void localHoldingWildcardResultIsNotDereferenceable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void dereference(Box<? extends T> box) {
                T value = box.get();
                // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                value.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullAssignmentInvalidatesWildcardLocal() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void sink(T value) {}
              static <T extends @Nullable Object> void pass(Box<? extends T> box) {
                T value = box.get();
                value = null;
                // Assigning null replaces the no-change value, so the local no longer satisfies T.
                // BUG: Diagnostic contains: passing @Nullable parameter
                Test.<T>sink(value);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void objectLocalHoldingWildcardResultIsRejectedAtObjectParameter() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static void consume(Object value) {}
              static <T extends @Nullable Object> void pass(Box<? extends T> box) {
                Object value = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                consume(value);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardResultIsAcceptedByReceiverSubstitutedParameter() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Sink<E extends @Nullable Object> {
                void accept(E e);
              }
              static <U extends @Nullable Object> void pass(List<? extends U> list, Sink<U> sink) {
                sink.accept(list.get(0));
              }
              interface MultiSink<E extends @Nullable Object> {
                void acceptAll(E... es);
              }
              static <U extends @Nullable Object> void passToVarargs(
                  List<? extends U> list, MultiSink<U> sink) {
                sink.acceptAll(list.get(0));
              }
              static <U extends @Nullable Object> void passToVarargsAfterAnotherArgument(
                  U first, List<? extends U> list, MultiSink<U> sink) {
                sink.acceptAll(first, list.get(0));
              }
              static <U extends @Nullable Object> void passToObjectVarargs(
                  List<? extends U> list, MultiSink<Object> sink) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'list.get(0)'
                sink.acceptAll(list.get(0));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void libraryModelNonNullParameterRejectsWildcardResult() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              interface Fn<F extends @Nullable Object> {
                String apply(F f);
              }
              static <T extends @Nullable Object> void modeledNonNull(
                  Box<? extends T> box, com.google.common.base.Function<T, String> f) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                f.apply(box.get());
              }
              static <T extends @Nullable Object> void unmodeled(Box<? extends T> box, Fn<T> f) {
                f.apply(box.get());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void uncheckedCastDoesNotMakeWildcardResultAcceptable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void sink(T value) {}
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object> void pass(Box<? extends T> box) {
                Object value = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter '(T) value'
                Test.<T>sink((T) value);
              }
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object> void passConditional(
                  boolean flag, Box<? extends T> box) {
                T value = (T) (flag ? box.get() : "default");
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                Test.<T>sink(value);
              }
            }
            """)
        .doTest();
  }

  /**
   * Inside {@code if (value == null)} a wildcard read is null, and a location of its type variable
   * still accepts it. Generic method inference takes {@code @Nullable T} from it, so a call such as
   * {@code identity(value)} returns a nullable value, which a location of type {@code T} rejects.
   * An explicit type argument {@code <T>} gives the call the type {@code T}.
   */
  @Test
  public void nullTestedWildcardLocalIsNullExceptWhereItsTypeVariableIsExpected() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void sink(T value) {}
              static void objectSink(Object value) {}
              static <X extends @Nullable Object> X identity(X x) {
                return x;
              }
              interface Sink<E extends @Nullable Object> {
                void accept(E e);
              }
              static <T extends @Nullable Object> void dereferenceWhenNonNull(Box<? extends T> box) {
                T value = box.get();
                if (value != null) {
                  value.hashCode();
                }
              }
              static <T extends @Nullable Object> void passWhenNull(Box<? extends T> box) {
                T value = box.get();
                if (value == null) {
                  Test.<T>sink(value);
                }
              }
              static <T extends @Nullable Object> void dereferenceWhenNull(Box<? extends T> box) {
                T value = box.get();
                if (value == null) {
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.hashCode();
                }
              }
              static <T extends @Nullable Object> void passAsObjectWhenNull(Box<? extends T> box) {
                T value = box.get();
                if (value == null) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                  objectSink(value);
                }
              }
              static <T extends @Nullable Object> T returnWhenNull(Box<? extends T> box) {
                T value = box.get();
                if (value == null) {
                  return value;
                }
                return value;
              }
              static <T extends @Nullable Object> T returnAfterEmptyNullCheck(Box<? extends T> box) {
                T value = box.get();
                if (value == null) {}
                return value;
              }
              static <T extends @Nullable Object> T returnAfterDereferenceWhenNonNull(
                  Box<? extends T> box) {
                T value = box.get();
                if (value != null) {
                  value.hashCode();
                }
                return value;
              }
              static <T extends @Nullable Object> Object returnAsObjectAfterNullCheck(
                  Box<? extends T> box) {
                T value = box.get();
                if (value == null) {}
                // BUG: Diagnostic contains: returning @Nullable expression
                return value;
              }
              static <T extends @Nullable Object> void dereferenceIdentityWhenNull(
                  Box<? extends T> box) {
                T value = box.get();
                if (value == null) {
                  // BUG: Diagnostic contains: dereferenced expression 'identity(value)' is @Nullable
                  identity(value).hashCode();
                }
              }
              static <T extends @Nullable Object> void dereferenceExplicitIdentityWhenNull(
                  Box<? extends T> box) {
                T value = box.get();
                if (value == null) {
                  Test.<T>identity(value).hashCode();
                }
              }
              static <T extends @Nullable Object> void passIdentityToTypeVariableWhenNull(
                  Box<? extends T> box, Sink<T> sink) {
                T value = box.get();
                if (value == null) {
                  sink.accept(value);
                  // BUG: Diagnostic contains: passing @Nullable parameter 'identity(value)'
                  sink.accept(identity(value));
                }
              }
              static <T extends @Nullable Object> Object returnIdentityAsObjectWhenNull(
                  Box<? extends T> box) {
                T value = box.get();
                if (value == null) {
                  // BUG: Diagnostic contains: type variable X constrained to be both @NonNull and @Nullable
                  return identity(value);
                }
                return new Object();
              }
              static <T extends @Nullable Object> void dereferenceAfterIdentityWhenNull(
                  Box<? extends T> box) {
                T value = box.get();
                if (value == null) {
                  value = identity(value);
                }
                // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                value.hashCode();
              }
              static <T extends @Nullable Object> void dereferenceLoopElementAfterIdentityWhenNull(
                  List<T> list) {
                for (T element : list) {
                  if (element == null) {
                    element = identity(element);
                  }
                  // BUG: Diagnostic contains: dereferenced expression 'element' is @Nullable
                  element.hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void contractRejectsWildcardResult() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:CheckContracts=true")))
        .addSourceLines(
            "Test.java",
            """
            import org.jetbrains.annotations.Contract;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              @Contract("null, _ -> !null")
              static <T extends @Nullable Object> @Nullable T get(
                  @Nullable Object key, Box<? extends T> box) {
                // BUG: Diagnostic contains: Method get has @Contract
                return box.get();
              }
              @Contract("null, _ -> !null")
              static <T extends @Nullable Object> @Nullable T fromParameter(
                  @Nullable Object key, T value) {
                return value;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardResultIsAcceptedByTypeVariableFieldInitializer() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <S extends @Nullable Object> Box<? extends S> box() {
                throw new UnsupportedOperationException();
              }
              private final T current = Test.<T>box().get();
              // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
              private final Object object = Test.<T>box().get();
            }
            """)
        .doTest();
  }

  @Test
  public void assignmentOfWildcardResultHasTheTargetType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static class Holder<H extends @Nullable Object> {
                H field;
                Holder(H field) {
                  this.field = field;
                }
              }
              static <U extends @Nullable Object> Holder<U> holder() {
                throw new UnsupportedOperationException();
              }
              static <T extends @Nullable Object> T throughLocalReceiver(
                  Holder<T> holder, Box<? extends T> box) {
                return (holder.field = box.get());
              }
              static <T extends @Nullable Object> T throughGenericCallReceiver(Box<? extends T> box) {
                return (Test.<T>holder().field = box.get());
              }
              static <T extends @Nullable Object> Object throughGenericCallReceiverAsObject(
                  Box<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return (Test.<T>holder().field = box.get());
              }
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object> T throughCastArray(Object array, Box<? extends T> box) {
                return (((T[]) array)[0] = box.get());
              }
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object> T throughObjectArray(
                  Object[] array, Box<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return (T) (array[0] = box.get());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardResultInfersTypeArgumentFromItsLocalType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <E extends @Nullable Object> Box<E> of(E e) {
                throw new UnsupportedOperationException();
              }
              static <T extends @Nullable Object> Box<T> typeVariableLocal(Box<? extends T> box) {
                T value = box.get();
                return of(value);
              }
              static <E extends @Nullable Object> E identity(E e) {
                return e;
              }
              static <T extends @Nullable Object> T directArgument(Box<? extends T> box) {
                return identity(box.get());
              }
              static <T extends @Nullable Object> T localArgument(Box<? extends T> box) {
                T value = box.get();
                return identity(value);
              }
              static <T extends @Nullable Object> Box<T> directBoxed(Box<? extends T> box) {
                return of(box.get());
              }
              static <T extends @Nullable Object> Box<Object> objectLocal(Box<? extends T> box) {
                Object value = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                return of(value);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardResultIsAcceptedByLowerBoundedWildcardParameter() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void typeVariableBound(
                  Box<? extends T> box, Function<? super T, String> f) {
                f.apply(box.get());
              }
              static <T extends @Nullable Object> void nonNullBound(
                  Box<? extends T> box, Function<? super @NonNull T, String> f) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                f.apply(box.get());
              }
              static <T extends @Nullable Object> void objectBound(
                  Box<? extends T> box, Function<? super Object, String> f) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                f.apply(box.get());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardResultPassedToGenericMethodFollowsItsBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <X> void nonNullSink(X value) {}
              static <X extends @Nullable Object> void nullableSink(X value) {}
              static <T extends @Nullable Object> void directToNonNullBound(Box<? extends T> box) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                nonNullSink(box.get());
              }
              static <T extends @Nullable Object> void localToNonNullBound(Box<? extends T> box) {
                T value = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                nonNullSink(value);
              }
              static <T extends @Nullable Object> void directToNullableBound(Box<? extends T> box) {
                nullableSink(box.get());
              }
              static <T extends @Nullable Object> void localToNullableBound(Box<? extends T> box) {
                T value = box.get();
                nullableSink(value);
              }
              @SafeVarargs
              static <X> void nonNullSinkAll(X... values) {}
              @SafeVarargs
              static <X extends @Nullable Object> void nullableSinkAll(X... values) {}
              static <T extends @Nullable Object> void directToNonNullVarargs(Box<? extends T> box) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                nonNullSinkAll(box.get());
              }
              static <T extends @Nullable Object> void explicitTypeArgumentToNonNullVarargs(
                  Box<? extends T> box) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                Test.<T>nonNullSinkAll(box.get());
              }
              static <T extends @Nullable Object> void elementToNonNullVarargs(List<? extends T> list) {
                for (T value : list) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                  nonNullSinkAll(value);
                }
              }
              static <T extends @Nullable Object> void laterArgumentToNonNullVarargs(
                  Object first, Box<? extends T> box) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                nonNullSinkAll(first, box.get());
              }
              static <T extends @Nullable Object> void directToNullableVarargs(Box<? extends T> box) {
                nullableSinkAll(box.get());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardResultIsRejectedByObjectField() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static class Holder<H extends @Nullable Object> {
                H field;
                Holder(H field) {
                  this.field = field;
                }
              }
              private Object field = new Object();
              private static Object staticField = new Object();
              void store(Box<? extends T> box) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                field = box.get();
              }
              void storeStatic(Box<? extends T> box) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                staticField = box.get();
              }
              @SuppressWarnings("rawtypes")
              void storeThroughRawReceiver(Holder raw, Box<? extends T> box) {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                raw.field = box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardResultDoesNotEnsureNonNullField() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.nullaway.annotations.EnsuresNonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              private @Nullable T field;
              @EnsuresNonNull("field")
              // BUG: Diagnostic contains: fails to ensure the following fields are non-null
              void init(Box<? extends T> box) {
                field = box.get();
              }
              @EnsuresNonNull("field")
              void initFromParameter(T value) {
                field = value;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void typeVariableResultIsAccepted() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> T get(Box<T> box) {
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nonNullBoundWildcardResultIsAccepted() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T> T get(Box<? extends T> box) {
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void concreteBoundWildcardResultIsAccepted() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static String get(Box<? extends String> box) {
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedWildcardResultIsAccepted() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> Box<T> get(Box<? extends Box<T>> box) {
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1727WildcardReturnFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> T unwrap(Box<? extends T> box) {
                return box.get();
              }
              static <T extends @Nullable Object> Object unwrapAsObject(Box<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1727WildcardLocalArgumentFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void sink(T value) {}
              static void objectSink(Object value) {}
              static <T extends @Nullable Object> void pass(Box<? extends T> box) {
                T value = box.get();
                Test.<T>sink(value);
              }
              static <T extends @Nullable Object> void passAsObject(Box<? extends T> box) {
                T value = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                objectSink(value);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1727EnhancedForLoopFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Repro.java",
            """
            import java.util.Collection;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Repro<E extends @Nullable Object> {
              boolean add(E element) {
                return false;
              }

              boolean addAll(Collection<? extends E> elements) {
                boolean changed = false;
                for (E element : elements) {
                  if (add(element)) {
                    changed = true;
                  }
                }
                return changed;
              }

              boolean addObject(Object element) {
                return false;
              }

              boolean addAllAsObjects(Collection<? extends E> elements) {
                boolean changed = false;
                for (E element : elements) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 'element'
                  if (addObject(element)) {
                    changed = true;
                  }
                }
                return changed;
              }
            }
            """)
        .doTest();
  }

  /**
   * With {@code HandleWildcardGenerics} on, the element of an enhanced-for loop over a {@code
   * List<T>} is a {@code NO_CHANGE} value, as a read through {@code ? extends T} is.
   */
  @Test
  public void enhancedForElementOfTypeVariableIsAcceptedWhereItsTypeVariableIsExpected() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              T field;
              T unassigned;
              Object objectField = new Object();
              // BUG: Diagnostic contains: does not guarantee @NonNull field 'unassigned'
              Test(List<T> list) {
                for (T element : list) {
                  field = element;
                  return;
                }
                throw new IllegalArgumentException();
              }
              T returnElement(List<T> list) {
                for (T element : list) {
                  return element;
                }
                throw new IllegalArgumentException();
              }
              Object returnElementAsObject(List<T> list) {
                for (T element : list) {
                  // BUG: Diagnostic contains: returning @Nullable expression
                  return element;
                }
                throw new IllegalArgumentException();
              }
              void storeElement(List<T> list) {
                for (T element : list) {
                  field = element;
                }
              }
              void storeElementAsObject(List<T> list) {
                for (T element : list) {
                  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                  objectField = element;
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1799EnhancedForLoopFunctionArgumentFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.LinkedHashMap;
            import java.util.Map;
            import java.util.function.Function;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              <E extends @Nullable Object, K, V extends @Nullable Object> Map<K, V> toMap(
                  Iterable<E> elems, Function<E, K> toKey, Function<E, V> toValue) {
                Map<K, V> map = new LinkedHashMap<>();
                for (E e : elems) {
                  map.put(toKey.apply(e), toValue.apply(e));
                }
                return map;
              }
              <E extends @Nullable Object> void applyToObjects(
                  Iterable<E> elems, Function<Object, Object> function) {
                for (E e : elems) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 'e'
                  function.apply(e);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1727WildcardDirectArgumentFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void sink(T value) {}
              static void objectSink(Object value) {}
              static <T extends @Nullable Object> void pass(Box<? extends T> box) {
                Test.<T>sink(box.get());
              }
              static <T extends @Nullable Object> void passAsObject(Box<? extends T> box) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                objectSink(box.get());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1727WildcardFieldAssignmentFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              private final Box<? extends T> box;
              private T current;
              private Object last = new Object();
              // Neither assignment is reported, nor is the field reported as uninitialized.
              Test(Box<? extends T> box) {
                this.box = box;
                current = box.get();
              }
              void advance() {
                current = box.get();
              }
              void advanceAsObject() {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                last = box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1727IteratorFieldAssignmentFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Iterator;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test<T extends @Nullable Object> {
              private final Iterator<? extends T> iterator;
              private T current;
              private Object last = new Object();
              // Neither assignment is reported, nor is the field reported as uninitialized.
              Test(Iterator<? extends T> iterator) {
                this.iterator = iterator;
                current = iterator.next();
              }
              void advance() {
                current = iterator.next();
              }
              void advanceAsObject() {
                // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                last = iterator.next();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1727AggregateFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Fn2<A extends @Nullable Object, S, R extends @Nullable Object> {
                R apply(A accumulator, S element);
              }
              static <S, A extends @Nullable Object> A aggregate(
                  Iterable<S> source, A seed, Fn2<A, S, ? extends A> function) {
                A result = seed;
                for (S element : source) {
                  result = function.apply(result, element);
                }
                return result;
              }
              static <S, A extends @Nullable Object> Object aggregateFirstAsObject(
                  S element, A seed, Fn2<A, S, ? extends A> function) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return function.apply(seed, element);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1727LambdaReturnFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> Box<T> wrap(Box<? extends T> box) {
                return () -> box.get();
              }
              static <T extends @Nullable Object> Box<T> wrapByReference(Box<? extends T> box) {
                return box::get;
              }
              static <T extends @Nullable Object> Box<Object> wrapAsObject(Box<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return () -> box.get();
              }
              interface NonNullBox<S extends @Nullable Object> extends Box<@NonNull S> {}
              static <T extends @Nullable Object> NonNullBox<T> wrapAsNonNullSubtype(Box<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return () -> box.get();
              }
              static <U extends @Nullable Object> void take(Box<@NonNull U> b) {}
              static <U extends @Nullable Object> void takePlain(Box<U> b) {}
              @SafeVarargs
              static <U extends @Nullable Object> void takeAll(Box<@NonNull U>... boxes) {}
              static class Taker<V extends @Nullable Object> {
                Taker() {}
                Taker(Box<@NonNull V> b) {}
                void take(Box<@NonNull V> b) {}
              }
              static class NonNullTaker<W extends @Nullable Object> extends Taker<@NonNull W> {
                void takePlain(Box<W> b) {}
              }
              static <T extends @Nullable Object> void passToParameter(
                  Box<? extends T> box, Taker<T> taker, NonNullTaker<T> nonNullTaker) {
                // BUG: Diagnostic contains: returning @Nullable expression
                Test.<T>take(() -> box.get());
                // BUG: Diagnostic contains: returning @Nullable expression
                taker.take(() -> box.get());
                // BUG: Diagnostic contains: returning @Nullable expression
                nonNullTaker.take(() -> box.get());
                // BUG: Diagnostic contains: returning @Nullable expression
                take(() -> box.get());
                Test.<T>takePlain(() -> box.get());
                nonNullTaker.takePlain(() -> box.get());
              }
              static <T extends @Nullable Object> void passInOtherForms(Box<? extends T> box) {
                Test.<T>take(
                    () -> {
                      // BUG: Diagnostic contains: returning @Nullable expression
                      return box.get();
                    });
                // BUG: Diagnostic contains: returning @Nullable expression
                Test.<T>take((() -> box.get()));
                // BUG: Diagnostic contains: returning @Nullable expression
                Test.<T>takeAll(() -> box.get());
                // BUG: Diagnostic contains: returning @Nullable expression
                new Taker<T>(() -> box.get());
              }
              static class Holder<V extends @Nullable Object> {
                Box<@NonNull V> nonNullField = () -> { throw new RuntimeException(); };
                Box<V> plainField = () -> { throw new RuntimeException(); };
              }
              static <T extends @Nullable Object> void passWhereTargetIsUnknown(
                  boolean flag, Box<? extends T> box, Holder<T> holder) {
                // BUG: Diagnostic contains: returning @Nullable expression
                Test.<T>take(flag ? () -> box.get() : () -> null);
                // BUG: Diagnostic contains: returning @Nullable expression
                new Taker<T>(() -> box.get()) {};
                // BUG: Diagnostic contains: returning @Nullable expression
                holder.nonNullField = () -> box.get();
                // A conditional or an assignment hides the target's annotations, so a lambda there
                // is checked as if its return type excluded null
                // BUG: Diagnostic contains: returning @Nullable expression
                holder.plainField = () -> box.get();
                Box<T> local = () -> box.get();
                // BUG: Diagnostic contains: returning @Nullable expression
                Box<@NonNull T> nonNullLocal = () -> box.get();
              }
              static <T extends @Nullable Object> Box<@NonNull T> returnNonNullBox(Box<? extends T> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return () -> box.get();
              }
              interface Maker<M extends @Nullable Object> {
                Box<@NonNull M> make();
              }
              static <M extends @Nullable Object> void takeMaker(Maker<M> maker) {}
              static <T extends @Nullable Object> void returnFromEnclosingLambda(Box<? extends T> box) {
                Maker<T> maker =
                    () -> {
                      // BUG: Diagnostic contains: returning @Nullable expression
                      return () -> box.get();
                    };
                Test.<T>takeMaker(
                    () -> {
                      // BUG: Diagnostic contains: returning @Nullable expression
                      return () -> box.get();
                    });
              }
              static <T extends @Nullable Object> Box<? extends T> returnNull() {
                // BUG: Diagnostic contains: returning @Nullable expression
                return () -> null;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1727ArrayStoreFalsePositive() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <T extends @Nullable Object> void store(
                  T[] values, Box<? extends T> box) {
                values[0] = box.get();
              }
              static <T extends @Nullable Object> void storeIntoObjectArray(
                  Object[] values, Box<? extends T> box) {
                // BUG: Diagnostic contains: Writing @Nullable expression into array
                values[0] = box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noChangeValueReachesTypeVariableOnlyThroughBoundsWithoutNullable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              interface Sink<E extends @Nullable Object> {
                void accept(E e);
              }
              static <T extends @Nullable Object, U extends T> void unannotatedBound(
                  Box<? extends U> box, Sink<T> sink) {
                sink.accept(box.get());
              }
              static <T extends @Nullable Object, U extends T> void dereferenceThroughUnannotatedBound(
                  Box<? extends U> box) {
                // BUG: Diagnostic contains: dereferenced expression 'box.get()' is @Nullable
                box.get().hashCode();
              }
              static <T, U extends T> void dereferenceThroughNonNullBounds(Box<? extends U> box) {
                box.get().hashCode();
              }
              static <T extends @Nullable Object, U extends @Nullable T> void argument(
                  Box<? extends U> box, Sink<T> sink) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'box.get()'
                sink.accept(box.get());
              }
              static <T, U extends @Nullable T> T returned(Box<? extends U> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return box.get();
              }
              static <T, U extends @Nullable T> T returnedThroughCast(Box<? extends U> box) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return (T) box.get();
              }
              static <T, U extends @Nullable T> void arrayStore(T[] array, Box<? extends U> box) {
                // BUG: Diagnostic contains: Writing @Nullable expression into array
                array[0] = box.get();
              }
              static <T, U extends T> T returnedThroughUnannotatedBound(Box<? extends U> box) {
                return box.get();
              }
              static <T, U extends T> T returnedThroughCastAndUnannotatedBound(Box<? extends U> box) {
                return (T) box.get();
              }
              static <T, U extends T> void arrayStoreThroughUnannotatedBound(
                  T[] array, Box<? extends U> box) {
                array[0] = box.get();
              }
              static <T extends @Nullable Object, U extends @Nullable T> void enhancedFor(
                  List<U> list, Sink<T> sink) {
                for (U element : list) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 'element'
                  sink.accept(element);
                }
              }
              static <T extends @Nullable Object, U extends @Nullable T> void throughLocal(
                  Box<? extends U> box, Sink<T> sink) {
                T value = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                sink.accept(value);
              }
              static class Holder<T> {
                T field;
                Holder(T field) {
                  this.field = field;
                }
                <U extends @Nullable T> void set(Box<? extends U> box) {
                  // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                  this.field = box.get();
                }
              }
            }
            """)
        .doTest();
  }

  /**
   * A cast and an equality test move a value to a variable or expression of another static type,
   * and the value keeps {@code NO_CHANGE} only where its type reaches the new type through bounds
   * without {@code @Nullable}. {@code Map.put} stores a {@code NO_CHANGE} value as nullable
   * whatever the value type of the map, so {@code throughMap} expects a report where a {@code T} is
   * expected.
   */
  @Test
  public void noChangeValueMovedToAnotherTypeKeepsItOnlyWhereTheTypeReachesIt() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              interface Sink<E extends @Nullable Object> {
                void accept(E e);
              }
              static <T extends @Nullable Object, S extends @Nullable Object> void equalToOtherType(
                  Box<? extends T> box, @Nullable S s, Sink<S> sink) {
                if (s == box.get()) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 's'
                  sink.accept(s);
                }
              }
              static <T extends @Nullable Object> void equalToSameType(
                  Box<? extends T> box, @Nullable T t, Sink<T> sink) {
                if (t == box.get()) {
                  sink.accept(t);
                }
              }
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object, S extends T> void castToOtherTypeOnLeft(
                  Box<? extends T> box, @Nullable S s, Sink<S> sink) {
                if ((T) s == box.get()) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 's'
                  sink.accept(s);
                }
              }
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object, S extends T> void castToOtherTypeOnRight(
                  Box<? extends T> box, @Nullable S s, Sink<S> sink) {
                if (box.get() == (T) s) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 's'
                  sink.accept(s);
                }
              }
              static <T extends @Nullable Object, S extends T> void castToOwnTypeBound(
                  Box<? extends S> box, @Nullable S s, Sink<S> sink) {
                if ((T) s == box.get()) {
                  sink.accept(s);
                }
              }
              static <T extends @Nullable Object, S extends T> void castOnValueSide(
                  Box<? extends S> box, @Nullable T t, Sink<T> sink) {
                if (t == (T) box.get()) {
                  sink.accept(t);
                }
              }
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object, S extends T> void downcast(
                  Box<? extends T> box, Sink<S> sink) {
                S s = (S) box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 's'
                sink.accept(s);
              }
              static <T extends @Nullable Object, S extends T> void upcast(
                  Box<? extends S> box, Sink<T> sink) {
                T t = (T) box.get();
                sink.accept(t);
              }
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object, S extends T> void downcastAtSink(
                  Box<? extends T> box, Sink<S> sink) {
                // BUG: Diagnostic contains: passing @Nullable parameter '(S) box.get()'
                sink.accept((S) box.get());
              }
              static <T extends @Nullable Object, S extends T> void upcastAtSink(
                  Box<? extends S> box, Sink<T> sink) {
                sink.accept((T) box.get());
              }
              static <K, T extends @Nullable Object> void throughMap(
                  Map<K, T> map, K key, Box<? extends T> box, Sink<T> sink) {
                map.put(key, box.get());
                // BUG: Diagnostic contains: passing @Nullable parameter 'map.get(key)'
                sink.accept(map.get(key));
              }
              static <K, T extends @Nullable Object> void throughMapFromParameter(
                  Map<K, T> map, K key, T value, Sink<T> sink) {
                map.put(key, value);
                sink.accept(map.get(key));
              }
            }
            """)
        .doTest();
  }

  /**
   * Each class has a field that only a {@code NO_CHANGE} or {@code NULL_NO_CHANGE} value
   * initializes, which is not reported, and a field that nothing initializes before it is read or
   * the initialization ends, which is.
   */
  @Test
  public void noChangeValueInitializesFieldOfItsTypeVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import com.uber.nullaway.annotations.Initializer;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              static <R extends @Nullable Object> Box<R> makeBox() {
                throw new UnsupportedOperationException();
              }
              static class ReadInConstructor<T extends @Nullable Object> {
                T current;
                T later;
                ReadInConstructor(Box<? extends T> box) {
                  current = box.get();
                  T read = current;
                  // BUG: Diagnostic contains: read of @NonNull field 'later' before initialization
                  T early = later;
                  later = box.get();
                }
              }
              static class AssignedInInitializerMethod<T extends @Nullable Object> {
                T current;
                T unassigned;
                @Initializer
                // BUG: Diagnostic contains: does not guarantee @NonNull field 'unassigned'
                void init(Box<? extends T> box) {
                  current = box.get();
                }
              }
              static class AssignedInInitializerBlock<T extends @Nullable Object> {
                final Box<? extends T> box = makeBox();
                T current;
                {
                  current = box.get();
                }
                T copy = current;
                // BUG: Diagnostic contains: read of @NonNull field 'later' before initialization
                T copyOfLater = this.later;
                T later;
                {
                  later = box.get();
                }
              }
              static class AssignedInHelper<T extends @Nullable Object> {
                T current;
                T unassigned;
                // BUG: Diagnostic contains: does not guarantee @NonNull field 'unassigned'
                AssignedInHelper(Box<? extends T> box) {
                  assign(box);
                }
                private void assign(Box<? extends T> box) {
                  current = box.get();
                }
              }
              static class AssignedNullTestedValue<T extends @Nullable Object> {
                T current;
                T unassigned;
                // BUG: Diagnostic contains: does not guarantee @NonNull field 'unassigned'
                AssignedNullTestedValue(Box<? extends T> box) {
                  T value = box.get();
                  if (value != null) {
                    throw new IllegalArgumentException();
                  }
                  current = value;
                }
              }
            }
            """)
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1727")
  @Test
  public void issue1727NullableBoundDereferenceFalseNegative() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable Object> {
                int dereference(T t) {
                  // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                  return t.hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void noChangeValueOnPathReachedThroughAnotherTypeIsNullable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              interface Sink<E extends @Nullable Object> {
                void accept(E e);
              }
              static class Holder<R extends @Nullable Object> {
                @Nullable R field;
              }
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object, S extends T> void equalThroughCastReceiver(
                  Holder<S> holder, Box<? extends T> box, Sink<S> sink) {
                if (((Holder<T>) (Object) holder).field == box.get()) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 'holder.field'
                  sink.accept(holder.field);
                }
              }
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object, S extends T> void assignedThroughCastReceiver(
                  Holder<S> holder, Box<? extends T> box, Sink<S> sink, Sink<T> tSink) {
                ((Holder<T>) (Object) holder).field = box.get();
                // BUG: Diagnostic contains: passing @Nullable parameter 'holder.field'
                sink.accept(holder.field);
                // BUG: Diagnostic contains: passing @Nullable parameter '((Holder<T>) (Object) holder).field'
                tSink.accept(((Holder<T>) (Object) holder).field);
              }
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object, S extends T> void equalOnRightThroughCastReceiver(
                  Holder<S> holder, Box<? extends T> box, Sink<S> sink) {
                if (box.get() == ((Holder<T>) (Object) holder).field) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 'holder.field'
                  sink.accept(holder.field);
                }
              }
              static class ArrayHolder<R extends @Nullable Object> {
                @Nullable R[] array;
                ArrayHolder(@Nullable R[] array) {
                  this.array = array;
                }
              }
              @SuppressWarnings("unchecked")
              static <T extends @Nullable Object, S extends T> void equalThroughCastArrayHolder(
                  ArrayHolder<S> holder, Box<? extends T> box, Sink<S> sink) {
                if (((ArrayHolder<T>) (Object) holder).array[0] == box.get()) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 'holder.array[0]'
                  sink.accept(holder.array[0]);
                }
              }
              static <T extends @Nullable Object> void equalThroughPlainArrayHolder(
                  ArrayHolder<T> holder, Box<? extends T> box, Sink<T> sink) {
                if (holder.array[0] == box.get()) {
                  sink.accept(holder.array[0]);
                }
              }
              interface Getter {
                <U extends @Nullable Object> @Nullable U get();
              }
              static <T extends @Nullable Object, S extends T> void equalThroughGenericMethod(
                  Getter getter, Box<? extends T> box, Sink<S> sink, Sink<T> tSink) {
                if (getter.<T>get() == box.get()) {
                  // BUG: Diagnostic contains: passing @Nullable parameter 'getter.<S>get()'
                  sink.accept(getter.<S>get());
                  // BUG: Diagnostic contains: passing @Nullable parameter 'getter.<T>get()'
                  tSink.accept(getter.<T>get());
                }
              }
              interface PlainGetter<R extends @Nullable Object> {
                @Nullable R get();
              }
              static <T extends @Nullable Object> void equalThroughNonGenericMethod(
                  PlainGetter<T> getter, Box<? extends T> box, Sink<T> sink) {
                if (getter.get() == box.get()) {
                  sink.accept(getter.get());
                }
              }
              static <T extends @Nullable Object> void equalThroughPlainReceiver(
                  Holder<T> holder, Box<? extends T> box, Sink<T> sink) {
                if (holder.field == box.get()) {
                  sink.accept(holder.field);
                }
              }
              static <T extends @Nullable Object> void assignedThroughPlainReceiver(
                  Holder<T> holder, Box<? extends T> box, Sink<T> sink) {
                holder.field = box.get();
                sink.accept(holder.field);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayElementEqualToNoChangeValueMayBeNull() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Box<R extends @Nullable Object> {
                R get();
              }
              interface Sink<E extends @Nullable Object> {
                void accept(E e);
              }
              static <T extends @Nullable Object> void dereference(
                  @Nullable T[] array, Box<? extends T> box) {
                if (array[0] == box.get()) {
                  // BUG: Diagnostic contains: dereferenced expression 'array[0]' is @Nullable
                  array[0].toString();
                }
              }
              static <T extends @Nullable Object> void dereferenceLocal(
                  @Nullable T[] array, Box<? extends T> box) {
                if (array[0] == box.get()) {
                  T value = array[0];
                  // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                  value.hashCode();
                }
              }
              static <T extends @Nullable Object> void dereferenceLocalWithoutEqualityTest(
                  @Nullable T[] array) {
                T value = array[0];
                // BUG: Diagnostic contains: dereferenced expression 'value' is @Nullable
                value.hashCode();
              }
              static <T extends @Nullable Object> void passToTypeVariable(
                  @Nullable T[] array, Box<? extends T> box, Sink<T> sink) {
                if (array[0] == box.get()) {
                  sink.accept(array[0]);
                }
              }
              static <T extends @Nullable Object> void equalToNullableValue(
                  @Nullable T[] array, @Nullable T value) {
                if (array[0] == value) {
                  // BUG: Diagnostic contains: dereferenced expression 'array[0]' is @Nullable
                  array[0].toString();
                }
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
