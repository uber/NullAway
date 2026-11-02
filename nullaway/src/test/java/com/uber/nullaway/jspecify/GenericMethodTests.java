package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Ignore;
import org.junit.Test;

public class GenericMethodTests extends NullAwayTestsBase {

  @Test
  public void fullTypeSubstitutionFromContext() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> Box<T> empty() { return new Box<T>(); }
              static void acceptsNested(Box<Box<@Nullable String>> box) {}
              static void acceptsFromContext() {
                acceptsNested(empty());
                Box<Box<@Nullable String>> local = empty();
                acceptsNested(local);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void fullTypeSubstitutionMergesCovariantArrayEvidence() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> Box<T> combine(T first, T second) {
                return new Box<T>();
              }
              static void acceptsNullable(Box<@Nullable String[]> box) {}
              static void acceptsNonNull(Box<String[]> box) {}
              static void acceptsWidened(Box<@Nullable Object[]> box) {}
              static void mergesBothOrders(String[] nonNull, @Nullable String[] nullable) {
                var first = combine(nonNull, nullable);
                var second = combine(nullable, nonNull);
                acceptsNullable(first);
                acceptsNullable(second);
                // BUG: Diagnostic contains: cannot be converted
                acceptsNonNull(first);
                // BUG: Diagnostic contains: cannot be converted
                acceptsNonNull(second);
              }
              static void preservesNonNullElements(String[] first, String[] second) {
                var result = combine(first, second);
                acceptsNonNull(result);
                // BUG: Diagnostic contains: cannot be converted
                acceptsNullable(result);
              }
              static void widensNominalComponents(String[] first, @Nullable Object[] second) {
                var result = combine(first, second);
                acceptsWidened(result);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void fullTypeSubstitutionPreservesOccurrenceOverrides() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> @Nullable T maybe(T value) { return value; }
              static void acceptsNested(Box<@Nullable String> box) {}
              static void retainsNullableRoot(Box<@Nullable String> box) {
                var result = maybe(box);
                // BUG: Diagnostic contains: passing @Nullable parameter
                acceptsNested(result);
                if (result != null) {
                  acceptsNested(result);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void fullTypeSubstitutionsArePerCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> T id(T value) { return value; }
              static void acceptsNullable(Box<@Nullable String> box) {}
              static void acceptsNonNull(Box<String> box) {}
              static void preservesNestedNullness(Box<@Nullable String> nullable, Box<String> nonNull) {
                var first = id(nullable);
                var second = id(nonNull);
                acceptsNullable(first);
                acceptsNonNull(second);
                // BUG: Diagnostic contains: cannot be converted
                acceptsNonNull(first);
                // BUG: Diagnostic contains: cannot be converted
                acceptsNullable(second);
              }
              static void nestedCalls(Box<@Nullable String> box) {
                var result = id(id(box));
                acceptsNullable(result);
                // BUG: Diagnostic contains: cannot be converted
                acceptsNonNull(result);
              }
            }
            """)
        .doTest();
  }

  // Exercises GenericsUtils.upperBoundIsNullable's method-model lookup and propagation of inferred
  // nullable results through a generic call. This is an integration control, not a distinguishing
  // test for typeWithAnnot's intersection branch.
  @Test
  public void modeledNullableIntersectionBound() {
    makeHelper()
        .addSourceLines(
            "ModeledIntersectionBounds.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class ModeledIntersectionBounds {
              interface A { void a(); }
              interface B { void b(); }
              static class Both implements A, B {
                public void a() {}
                public void b() {}
              }
              // The test library model permits nullable instantiations of T.
              static <T extends A & B> T identity(T value) { return value; }
              void test(Both value, @Nullable Both nullableValue) {
                var result = identity(value);
                result.a();
                result.b();
                // BUG: Diagnostic contains: dereferenced expression
                identity(nullableValue).a();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullCheckedIntersectionInstantiationIsNonNull() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface I {}
              interface J {}
              static final class A implements I, J {}
              static final class B implements I, J {}
              static <E extends @Nullable Object> E first(E a, E b) { return a; }
              static <E extends @Nullable Object> E id(E value) { return value; }

              static void rejectsNullableIntersection(@Nullable A a, B b) {
                var value = first(a, b);
                // BUG: Diagnostic contains: dereferenced expression
                id(value).hashCode();
              }

              static void acceptsNullCheckedIntersection(@Nullable A a, B b) {
                var value = first(a, b);
                if (value != null) {
                  id(value).hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void sourceWrittenAllNullableIntersectionBounds() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              interface A { void a(); }
              interface B { void b(); }
              static class Both implements A, B {
                public void a() {}
                public void b() {}
              }
              static class Box1<T extends @Nullable A & @Nullable B> {
                T value;
                Box1(T value) { this.value = value; }
              }
              static <T extends @Nullable A & @Nullable B> T id1(T value) { return value; }
              static void acceptsNullableAB(@Nullable Both value) {
                Box1<@Nullable Both> box = new Box1<@Nullable Both>(value);
                Test.<@Nullable Both>id1(value);
                // BUG: Diagnostic contains: dereferenced expression
                Test.<@Nullable Both>id1(value).a();
                var result = id1(value);
                // BUG: Diagnostic contains: dereferenced expression
                result.a();
                id1(new Both()).a();
              }

              static class Box2<T extends @Nullable B & @Nullable A> {
                T value;
                Box2(T value) { this.value = value; }
              }
              static <T extends @Nullable B & @Nullable A> T id2(T value) { return value; }
              static void acceptsNullableBA(@Nullable Both value) {
                Box2<@Nullable Both> box = new Box2<@Nullable Both>(value);
                Test.<@Nullable Both>id2(value);
                // BUG: Diagnostic contains: dereferenced expression
                Test.<@Nullable Both>id2(value).a();
                var result = id2(value);
                // BUG: Diagnostic contains: dereferenced expression
                result.a();
                id2(new Both()).a();
              }

              static class Box3<T extends @Nullable Object & @Nullable A & @Nullable B> {
                T value;
                Box3(T value) { this.value = value; }
              }
              static <T extends @Nullable Object & @Nullable A & @Nullable B> T id3(T value) { return value; }
              static void acceptsNullableObjectAB(@Nullable Both value) {
                Box3<@Nullable Both> box = new Box3<@Nullable Both>(value);
                Test.<@Nullable Both>id3(value);
                // BUG: Diagnostic contains: dereferenced expression
                Test.<@Nullable Both>id3(value).a();
                var result = id3(value);
                // BUG: Diagnostic contains: dereferenced expression
                result.a();
                id3(new Both()).a();
              }

              static class Box4<T extends @Nullable Object & @Nullable B & @Nullable A> {
                T value;
                Box4(T value) { this.value = value; }
              }
              static <T extends @Nullable Object & @Nullable B & @Nullable A> T id4(T value) { return value; }
              static void acceptsNullableObjectBA(@Nullable Both value) {
                Box4<@Nullable Both> box = new Box4<@Nullable Both>(value);
                Test.<@Nullable Both>id4(value);
                // BUG: Diagnostic contains: dereferenced expression
                Test.<@Nullable Both>id4(value).a();
                var result = id4(value);
                // BUG: Diagnostic contains: dereferenced expression
                result.a();
                id4(new Both()).a();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void sourceWrittenMixedNullnessIntersectionBounds() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              interface A { void a(); }
              interface B { void b(); }
              static class Both implements A, B {
                public void a() {}
                public void b() {}
              }
              static class Box1<T extends A & @Nullable B> {}
              static <T extends A & @Nullable B> T id1(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithDefaultNonNullAAndNullableB(Box1<@Nullable Both> box) {}
              static void rejectsNullableWithDefaultNonNullAAndNullableB(@Nullable Both value) {
                Box1<Both> box = new Box1<Both>();
                id1(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id1(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id1(new Both());
              }

              static class Box2<T extends @Nullable A & B> {}
              static <T extends @Nullable A & B> T id2(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithNullableAAndDefaultNonNullB(Box2<@Nullable Both> box) {}
              static void rejectsNullableWithNullableAAndDefaultNonNullB(@Nullable Both value) {
                Box2<Both> box = new Box2<Both>();
                id2(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id2(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id2(new Both());
              }

              static class Box3<T extends B & @Nullable A> {}
              static <T extends B & @Nullable A> T id3(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithDefaultNonNullBAndNullableA(Box3<@Nullable Both> box) {}
              static void rejectsNullableWithDefaultNonNullBAndNullableA(@Nullable Both value) {
                Box3<Both> box = new Box3<Both>();
                id3(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id3(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id3(new Both());
              }

              static class Box4<T extends @Nullable B & A> {}
              static <T extends @Nullable B & A> T id4(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithNullableBAndDefaultNonNullA(Box4<@Nullable Both> box) {}
              static void rejectsNullableWithNullableBAndDefaultNonNullA(@Nullable Both value) {
                Box4<Both> box = new Box4<Both>();
                id4(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id4(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id4(new Both());
              }

              static class Box5<T extends @NonNull A & @Nullable B> {}
              static <T extends @NonNull A & @Nullable B> T id5(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithExplicitNonNullAAndNullableB(Box5<@Nullable Both> box) {}
              static void rejectsNullableWithExplicitNonNullAAndNullableB(@Nullable Both value) {
                Box5<Both> box = new Box5<Both>();
                id5(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id5(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id5(new Both());
              }

              static class Box6<T extends @Nullable A & @NonNull B> {}
              static <T extends @Nullable A & @NonNull B> T id6(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithNullableAAndExplicitNonNullB(Box6<@Nullable Both> box) {}
              static void rejectsNullableWithNullableAAndExplicitNonNullB(@Nullable Both value) {
                Box6<Both> box = new Box6<Both>();
                id6(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id6(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id6(new Both());
              }

              static class Box7<T extends @NonNull B & @Nullable A> {}
              static <T extends @NonNull B & @Nullable A> T id7(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithExplicitNonNullBAndNullableA(Box7<@Nullable Both> box) {}
              static void rejectsNullableWithExplicitNonNullBAndNullableA(@Nullable Both value) {
                Box7<Both> box = new Box7<Both>();
                id7(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id7(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id7(new Both());
              }

              static class Box8<T extends @Nullable B & @NonNull A> {}
              static <T extends @Nullable B & @NonNull A> T id8(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithNullableBAndExplicitNonNullA(Box8<@Nullable Both> box) {}
              static void rejectsNullableWithNullableBAndExplicitNonNullA(@Nullable Both value) {
                Box8<Both> box = new Box8<Both>();
                id8(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id8(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id8(new Both());
              }

              static class Box9<T extends Object & @Nullable A & @Nullable B> {}
              static <T extends Object & @Nullable A & @Nullable B> T id9(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithDefaultNonNullObjectAB(Box9<@Nullable Both> box) {}
              static void rejectsNullableWithDefaultNonNullObjectAB(@Nullable Both value) {
                Box9<Both> box = new Box9<Both>();
                id9(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id9(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id9(new Both());
              }

              static class Box10<T extends Object & @Nullable B & @Nullable A> {}
              static <T extends Object & @Nullable B & @Nullable A> T id10(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithDefaultNonNullObjectBA(Box10<@Nullable Both> box) {}
              static void rejectsNullableWithDefaultNonNullObjectBA(@Nullable Both value) {
                Box10<Both> box = new Box10<Both>();
                id10(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id10(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id10(new Both());
              }

              static class Box11<T extends @NonNull Object & @Nullable A & @Nullable B> {}
              static <T extends @NonNull Object & @Nullable A & @Nullable B> T id11(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithExplicitNonNullObjectAB(Box11<@Nullable Both> box) {}
              static void rejectsNullableWithExplicitNonNullObjectAB(@Nullable Both value) {
                Box11<Both> box = new Box11<Both>();
                id11(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id11(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id11(new Both());
              }

              static class Box12<T extends @NonNull Object & @Nullable B & @Nullable A> {}
              static <T extends @NonNull Object & @Nullable B & @Nullable A> T id12(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithExplicitNonNullObjectBA(Box12<@Nullable Both> box) {}
              static void rejectsNullableWithExplicitNonNullObjectBA(@Nullable Both value) {
                Box12<Both> box = new Box12<Both>();
                id12(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id12(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id12(new Both());
              }

              static class Box13<T extends @Nullable Object & A & @Nullable B> {}
              static <T extends @Nullable Object & A & @Nullable B> T id13(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithNullableObjectAndDefaultNonNullA(Box13<@Nullable Both> box) {}
              static void rejectsNullableWithNullableObjectAndDefaultNonNullA(@Nullable Both value) {
                Box13<Both> box = new Box13<Both>();
                id13(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id13(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id13(new Both());
              }

              static class Box14<T extends @Nullable Object & @Nullable A & B> {}
              static <T extends @Nullable Object & @Nullable A & B> T id14(T value) {
                value.a();
                return value;
              }
              // BUG: Diagnostic contains: cannot be @Nullable
              void rejectsNullableClassArgumentWithNullableObjectAndDefaultNonNullB(Box14<@Nullable Both> box) {}
              static void rejectsNullableWithNullableObjectAndDefaultNonNullB(@Nullable Both value) {
                Box14<Both> box = new Box14<Both>();
                id14(new Both()).a();
                // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                id14(value);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Both>id14(new Both());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideSourceWrittenIntersectionBounds() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              interface A { void a(); }
              interface B { void b(); }
              interface Parent1<X> {
                <T extends @Nullable A & @Nullable B> void nullableAB(T value);
                <T extends @NonNull A & @Nullable B> void nonNullAB(T value);
                <T extends A & @Nullable B> void defaultNonNullAB(T value);
              }
              abstract static class Matching1 implements Parent1<String> {
                @Override
                public <T extends @Nullable A & @Nullable B> void nullableAB(T value) {
                  if (value != null) {
                    value.a();
                  }
                }
                @Override
                public <T extends @NonNull A & @Nullable B> void nonNullAB(T value) { value.a(); }
                @Override
                public <T extends A & @Nullable B> void defaultNonNullAB(T value) { value.a(); }
              }
              abstract static class Mismatching1 implements Parent1<String> {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a non-null upper bound
                public <T extends @NonNull A & @Nullable B> void nullableAB(T value) {}
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends @Nullable A & @Nullable B> void nonNullAB(T value) {}
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends @Nullable A & @Nullable B> void defaultNonNullAB(T value) {}
              }

              interface Parent2<X> {
                <T extends @Nullable B & @Nullable A> void nullableBA(T value);
                <T extends @NonNull B & @Nullable A> void nonNullBA(T value);
                <T extends B & @Nullable A> void defaultNonNullBA(T value);
              }
              abstract static class Matching2 implements Parent2<String> {
                @Override
                public <T extends @Nullable B & @Nullable A> void nullableBA(T value) {
                  if (value != null) {
                    value.a();
                  }
                }
                @Override
                public <T extends @NonNull B & @Nullable A> void nonNullBA(T value) { value.a(); }
                @Override
                public <T extends B & @Nullable A> void defaultNonNullBA(T value) { value.a(); }
              }
              abstract static class Mismatching2 implements Parent2<String> {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a non-null upper bound
                public <T extends @NonNull B & @Nullable A> void nullableBA(T value) {}
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends @Nullable B & @Nullable A> void nonNullBA(T value) {}
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends @Nullable B & @Nullable A> void defaultNonNullBA(T value) {}
              }

              interface Parent3<X> {
                <T extends @Nullable Object & @Nullable A & @Nullable B> void nullableObjectAB(T value);
                <T extends @NonNull Object & @Nullable A & @Nullable B> void nonNullObjectAB(T value);
                <T extends Object & @Nullable A & @Nullable B> void defaultNonNullObjectAB(T value);
              }
              abstract static class Matching3 implements Parent3<String> {
                @Override
                public <T extends @Nullable Object & @Nullable A & @Nullable B> void nullableObjectAB(T value) {
                  if (value != null) {
                    value.a();
                  }
                }
                @Override
                public <T extends @NonNull Object & @Nullable A & @Nullable B> void nonNullObjectAB(T value) { value.a(); }
                @Override
                public <T extends Object & @Nullable A & @Nullable B> void defaultNonNullObjectAB(T value) { value.a(); }
              }
              abstract static class Mismatching3 implements Parent3<String> {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a non-null upper bound
                public <T extends @NonNull Object & @Nullable A & @Nullable B> void nullableObjectAB(T value) {}
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends @Nullable Object & @Nullable A & @Nullable B> void nonNullObjectAB(T value) {}
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends @Nullable Object & @Nullable A & @Nullable B> void defaultNonNullObjectAB(T value) {}
              }

              interface Parent4<X> {
                <T extends @Nullable Object & @Nullable B & @Nullable A> void nullableObjectBA(T value);
                <T extends @NonNull Object & @Nullable B & @Nullable A> void nonNullObjectBA(T value);
                <T extends Object & @Nullable B & @Nullable A> void defaultNonNullObjectBA(T value);
              }
              abstract static class Matching4 implements Parent4<String> {
                @Override
                public <T extends @Nullable Object & @Nullable B & @Nullable A> void nullableObjectBA(T value) {
                  if (value != null) {
                    value.a();
                  }
                }
                @Override
                public <T extends @NonNull Object & @Nullable B & @Nullable A> void nonNullObjectBA(T value) { value.a(); }
                @Override
                public <T extends Object & @Nullable B & @Nullable A> void defaultNonNullObjectBA(T value) { value.a(); }
              }
              abstract static class Mismatching4 implements Parent4<String> {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a non-null upper bound
                public <T extends @NonNull Object & @Nullable B & @Nullable A> void nullableObjectBA(T value) {}
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends @Nullable Object & @Nullable B & @Nullable A> void nonNullObjectBA(T value) {}
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends @Nullable Object & @Nullable B & @Nullable A> void defaultNonNullObjectBA(T value) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void intersectionComponentAnnotationsThroughGenericMethodCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              static class Base {}
              interface A<X extends @Nullable Object> { X a(); }
              interface B<X extends @Nullable Object> { X b(); }
              static <T extends A<@Nullable String> & B<String>> T id1(T value) { return value; }
              static <T extends A<@Nullable String> & B<String>> void preservesAnnotationsWithAB(T value) {
                id1(value).b().length();
                // BUG: Diagnostic contains: dereferenced expression
                id1(value).a().length();
                A<@Nullable String> nullableA = id1(value);
                B<String> nonNullB = id1(value);
                // BUG: Diagnostic contains: incompatible types:
                A<String> nonNullA = id1(value);
                // BUG: Diagnostic contains: incompatible types:
                B<@Nullable String> nullableB = id1(value);
              }

              static <T extends B<String> & A<@Nullable String>> T id2(T value) { return value; }
              static <T extends B<String> & A<@Nullable String>> void preservesAnnotationsWithBA(T value) {
                id2(value).b().length();
                // BUG: Diagnostic contains: dereferenced expression
                id2(value).a().length();
                A<@Nullable String> nullableA = id2(value);
                B<String> nonNullB = id2(value);
                // BUG: Diagnostic contains: incompatible types:
                A<String> nonNullA = id2(value);
                // BUG: Diagnostic contains: incompatible types:
                B<@Nullable String> nullableB = id2(value);
              }

              static <T extends Base & A<@Nullable String> & B<String>> T id3(T value) { return value; }
              static <T extends Base & A<@Nullable String> & B<String>> void preservesAnnotationsWithBaseAB(T value) {
                id3(value).b().length();
                // BUG: Diagnostic contains: dereferenced expression
                id3(value).a().length();
                A<@Nullable String> nullableA = id3(value);
                B<String> nonNullB = id3(value);
                // BUG: Diagnostic contains: incompatible types:
                A<String> nonNullA = id3(value);
                // BUG: Diagnostic contains: incompatible types:
                B<@Nullable String> nullableB = id3(value);
              }

              static <T extends Base & B<String> & A<@Nullable String>> T id4(T value) { return value; }
              static <T extends Base & B<String> & A<@Nullable String>> void preservesAnnotationsWithBaseBA(T value) {
                id4(value).b().length();
                // BUG: Diagnostic contains: dereferenced expression
                id4(value).a().length();
                A<@Nullable String> nullableA = id4(value);
                B<String> nonNullB = id4(value);
                // BUG: Diagnostic contains: incompatible types:
                A<String> nonNullA = id4(value);
                // BUG: Diagnostic contains: incompatible types:
                B<@Nullable String> nullableB = id4(value);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void intersectionComponentAnnotationsThroughGenericClassCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              static class Base {}
              interface A<X> { X a(); }
              interface B<X> { X b(); }
              static class Box1<T extends A<List<@Nullable String>> & B<List<String>>> {
                final T value;
                Box1(T value) { this.value = value; }
                T get() { return value; }
              }
              static <T extends A<List<@Nullable String>> & B<List<String>>> void preservesAnnotationsWithAB(T value) {
                Box1<T> box = new Box1<T>(value);
                box.get().b().get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                box.get().a().get(0).length();
                A<List<@Nullable String>> nullableA = box.get();
                B<List<String>> nonNullB = box.get();
                // BUG: Diagnostic contains: incompatible types:
                A<List<String>> nonNullA = box.get();
                // BUG: Diagnostic contains: incompatible types:
                B<List<@Nullable String>> nullableB = box.get();
              }

              static class Box2<T extends B<List<String>> & A<List<@Nullable String>>> {
                final T value;
                Box2(T value) { this.value = value; }
                T get() { return value; }
              }
              static <T extends B<List<String>> & A<List<@Nullable String>>> void preservesAnnotationsWithBA(T value) {
                Box2<T> box = new Box2<T>(value);
                box.get().b().get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                box.get().a().get(0).length();
                A<List<@Nullable String>> nullableA = box.get();
                B<List<String>> nonNullB = box.get();
                // BUG: Diagnostic contains: incompatible types:
                A<List<String>> nonNullA = box.get();
                // BUG: Diagnostic contains: incompatible types:
                B<List<@Nullable String>> nullableB = box.get();
              }

              static class Box3<T extends Base & A<List<@Nullable String>> & B<List<String>>> {
                final T value;
                Box3(T value) { this.value = value; }
                T get() { return value; }
              }
              static <T extends Base & A<List<@Nullable String>> & B<List<String>>> void preservesAnnotationsWithBaseAB(T value) {
                Box3<T> box = new Box3<T>(value);
                box.get().b().get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                box.get().a().get(0).length();
                A<List<@Nullable String>> nullableA = box.get();
                B<List<String>> nonNullB = box.get();
                // BUG: Diagnostic contains: incompatible types:
                A<List<String>> nonNullA = box.get();
                // BUG: Diagnostic contains: incompatible types:
                B<List<@Nullable String>> nullableB = box.get();
              }

              static class Box4<T extends Base & B<List<String>> & A<List<@Nullable String>>> {
                final T value;
                Box4(T value) { this.value = value; }
                T get() { return value; }
              }
              static <T extends Base & B<List<String>> & A<List<@Nullable String>>> void preservesAnnotationsWithBaseBA(T value) {
                Box4<T> box = new Box4<T>(value);
                box.get().b().get(0).length();
                // BUG: Diagnostic contains: dereferenced expression
                box.get().a().get(0).length();
                A<List<@Nullable String>> nullableA = box.get();
                B<List<String>> nonNullB = box.get();
                // BUG: Diagnostic contains: incompatible types:
                A<List<String>> nonNullA = box.get();
                // BUG: Diagnostic contains: incompatible types:
                B<List<@Nullable String>> nullableB = box.get();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void modeledNullableDependentBoundKeepsPeerNonNull() {
    makeHelper()
        .addSourceLines(
            "NestedAnnots.java",
            """
            package com.uber.lib.unannotated;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class NestedAnnots<T extends U, U> {
              // The test library model permits nullable T, but U remains non-null.
              NestedAnnots(T first, U second) {}
              static void nullableModeledArgumentIsAllowed() {
                new NestedAnnots<>(null, new Object());
              }
              static void unmodeledPeerRemainsNonNull() {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null'
                new NestedAnnots<>(null, null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericNonNullIdentityFunction() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              static <T> T nonNullIdentity(T t) {
                return t;
              }
              static void test() {
                // legal
                nonNullIdentity(new Object()).toString();
                // BUG: Diagnostic contains: passing @Nullable parameter 'null'
                nonNullIdentity(null);
                // BUG: Diagnostic contains: Type argument cannot be @Nullable
                Test.<@Nullable Object>nonNullIdentity(new Object());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericNullAllowingIdentityFunction() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              static <T extends @Nullable Object> T identity(T t) {
                return t;
              }
              static void test() {
                // legal
                identity(new Object()).toString();
                // also legal
                Test.<@Nullable Object>identity(null);
                // BUG: Diagnostic contains: dereferenced expression
                Test.<@Nullable Object>identity(null).toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void multipleTypeVariablesMethodCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import org.jspecify.annotations.NonNull;
            class Test {
                public static <T extends @Nullable Object, U> void twoTypeVars(T first, U second) {}
                static void test() {
                   // legal
                   Test.<@NonNull Object, Object>twoTypeVars(new Object(), new Object());
                   // legal
                   Test.<@Nullable Object, Object>twoTypeVars(null, new Object());
                   // BUG: Diagnostic contains: Type argument cannot be @Nullable
                   Test.<@Nullable Object, @Nullable Object>twoTypeVars(null, null);
                }
            }
            """)
        .doTest();
  }

  @Test
  public void genericInstanceMethods() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
                 abstract class Foo<T extends @Nullable String, S> {
                   public abstract <U> T test1(U u);
                   public abstract <U> S test2(U u);
                   public abstract <U extends @Nullable Object> void test3(U u);
                 }
                 public void run(Foo<@Nullable String, Character> f) {
                   String s = f.<Integer>test1(3);
                   // BUG: Diagnostic contains: dereferenced expression
                   s.toString();
                   Character c = f.<Integer>test2(3);
                   // legal, Type S is @NonNull
                   c.toString();
                   // BUG: Diagnostic contains: passing @Nullable parameter 'null'
                   f.<Integer>test3(null);
                   f.<@Nullable Integer>test3(null);
                 }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredGenericInstanceMethodReturnTypeUsesReceiverType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Function;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              record Holder<T extends @Nullable Object>(T value) {}
              record Box<V extends @Nullable Object>(V value) {
                <E extends Exception> Holder<V> getOrThrow(
                    Function<? super Exception, E> exceptionTransformer) throws E {
                  return new Holder<>(value);
                }
              }
              static <T extends @Nullable Object> Box<T> box(T value) {
                return new Box<>(value);
              }
              static void test() {
                var holder = box(null).getOrThrow(RuntimeException::new);
                // BUG: Diagnostic contains: dereferenced expression 'holder.value()' is @Nullable
                holder.value().hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test(timeout = 60_000)
  public void longChainOfGenericInstanceMethodCalls() {
    // Every link calls two generic methods, each on a receiver whose type must itself be inferred.
    // The time to check such a chain once grew exponentially with its length.
    String chain =
        String.join(
            "",
            Collections.nCopies(
                24, "\n        .path(\"field\").entity(String.class).isEqualTo(\"value\")"));
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              interface Traversable {
                Path path(String path);
              }
              interface Path extends Traversable {
                <D> Entity<D, ?> entity(Class<D> entityType);
              }
              interface Entity<D, S extends Entity<D, S>> extends Traversable {
                <T extends S> T isEqualTo(@Nullable Object expected);
              }
              static void test(Traversable response) {
                response%s
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null'
                    .path(null);
              }
            }
            """
                .formatted(chain))
        .doTest();
  }

  @Test
  public void genericMethodAndVoidType() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              static class Foo {
                <C extends @Nullable Object> void foo(C c, Visitor<C> visitor) {
                  visitor.visit(this, c);
                }
              }
              static abstract class Visitor<C extends @Nullable Object> {
                abstract void visit(Foo foo, C c);
              }
              static class MyVisitor extends Visitor<@Nullable Void> {
                @Override
                void visit(Foo foo, @Nullable Void c) {}
              }
              static void test(Foo f) {
                // this is safe
                f.<@Nullable Void>foo(null, new MyVisitor());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericMethodAndVoidTypeWithInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              static class Foo {
                <C extends @Nullable Object> void foo(C c, Visitor<C> visitor) {
                  visitor.visit(this, c);
                }
              }
              static abstract class Visitor<C extends @Nullable Object> {
                abstract void visit(Foo foo, C c);
              }
              static class MyVisitor extends Visitor<@Nullable Void> {
                @Override
                void visit(Foo foo, @Nullable Void c) {}
              }
              static void test(Foo f) {
                // this is safe
                f.foo(null, new MyVisitor());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericInferenceOnAssignments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
                class Test {
                  static class Foo<T extends @Nullable Object> {
                    Foo(T t) {}
                    static <U extends @Nullable Object> Foo<U> makeNull(U u) {
                      return new Foo<>(u);
                    }
                    static <U> Foo<U> makeNonNull(U u) {
                      return new Foo<>(u);
                    }
                  }
                  static void testLocalAssign() {
                    // legal
                    Foo<@Nullable Object> f1 = Foo.makeNull(null);
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    Foo<Object> f2 = Foo.makeNull(null);
                    Foo<@Nullable Object> f3 = Foo.makeNull(new Object());
                    Foo<Object> f4 = Foo.makeNull(new Object());
                    // ILLEGAL: U does not have a @Nullable upper bound
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    Foo<@Nullable Object> f5 = Foo.makeNonNull(null);
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    Foo<Object> f6 = Foo.makeNonNull(null);
                    // BUG: Diagnostic contains: incompatible types:
                    Foo<@Nullable Object> f7 = Foo.makeNonNull(new Object());
                    Foo<Object> f8 = Foo.makeNonNull(new Object());
                  }
                }
            """)
        .doTest();
  }

  @Test
  public void genericInferenceOnAssignmentAfterDeclaration() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
                class Test {
                  static class Foo<T extends @Nullable Object> {
                    Foo(T t) {}
                    static <U extends @Nullable Object> Foo<U> makeNull(U u) {
                      return new Foo<>(u);
                    }
                    static <U> Foo<U> makeNonNull(U u) {
                      return new Foo<>(u);
                    }
                  }
                  static void testAssignAfterDeclaration() {
                    // legal
                    Foo<@Nullable Object> f1; f1 = Foo.makeNull(null);
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    Foo<Object> f2; f2 = Foo.makeNull(null);
                  }
                }
            """)
        .doTest();
  }

  @Test
  public void multipleParametersOneNested() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
                static class Foo<T extends @Nullable Object> {
                    Foo(T t) {}
                    static <U extends @Nullable Object> Foo<U> create(U u, Foo<U> other) {
                        return new Foo<>(u);
                    }
                    static void test(Foo<@Nullable Object> f1, Foo<Object> f2) {
                        // no error expected
                        Foo<@Nullable Object> result = Foo.create(null, f1);
                        // BUG: Diagnostic contains: incompatible types: Foo<Object> cannot be converted to Foo<@Nullable Object>
                        Foo<@Nullable Object> result2 = Foo.create(null, f2);
                    }
                }
            }
            """)
        .doTest();
  }

  @Test
  public void genericInferenceOnAssignmentsMultipleParams() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              class Foo<T extends @Nullable Object> {
                Foo(T t) {}
                public <U extends @Nullable Object> Foo<U> make(U u, @Nullable String s) {
                  return new Foo<>(u);
                }
              }
              static class Bar<S extends @Nullable Object, Z extends @Nullable Object> {
                Bar(S s, Z z) {}
                static <U extends @Nullable Object, B extends @Nullable Object> Bar<U, B> make(U u, B b) {
                  return new Bar<>(u, b);
                }
              }
              static class Baz<S, Z> {
                Baz(S s, Z z) {}
                static <U, B> Baz<U, B> make(U u, B b) {
                  return new Baz<>(u, b);
                }
              }
              public void run(Foo<@Nullable String> foo) {
                // legal
                Foo<@Nullable Object> f1 = foo.make(null, new String());
                Foo<@Nullable Object> f2 = foo.make(null, null);
                Foo<Object> f3 = foo.make(new Object(), null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Foo<Object> f4 = foo.make(null, null);
                // legal
                Bar<@Nullable Object, Object> b1 = Bar.make(null, new Object());
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Bar<@Nullable Object, Object> b2 = Bar.make(null, null);
                Bar<@Nullable Object, @Nullable Object> b3 = Bar.make(null, null);
                Bar<Object, @Nullable Object> b4 = Bar.make(new Object(), null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Bar<Object, @Nullable Object> b5 = Bar.make(null, null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Bar<Object, Object> b6 = Bar.make(null, null);
                // legal
                Baz<String, Object> baz1 = Baz.make(new String(), new Object());
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Baz<String, Object> baz2 = Baz.make(null, new Object());
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Baz<String, Object> baz3 = Baz.make(new String(), null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Baz<String, Object> baz4 = Baz.make(null, null);
                // BUG: Diagnostic contains: Generic type parameter cannot be @Nullable
                Baz<@Nullable String, Object> baz5 = Baz.make(new String(), new Object());
                // BUG: Diagnostic contains: Generic type parameter cannot be @Nullable
                Baz<String, @Nullable Object> baz6 = Baz.make(new String(), new Object());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericsInferenceOnAssignmentsWithGenericClasses() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import java.util.ArrayList;
            class Test {
              abstract class Foo<K extends @Nullable Object, V> {
                abstract <U, R> Foo<U,ArrayList<R>> nonNullTest();
                abstract <U extends @Nullable Object, R extends @Nullable Object> Foo<U,ArrayList<R>> nullTest();
              }
              static void test(Foo<Void, Void> f) {
                Foo<Integer, ArrayList<String>> fooNonNull_1 = f.nonNullTest();
                // BUG: Diagnostic contains: incompatible types:
                Foo<Integer, ArrayList<@Nullable String>> fooNonNull_2 = f.nonNullTest();
                // BUG: Diagnostic contains: incompatible types:
                Foo<@Nullable Integer, ArrayList<String>> fooNonNull_3 = f.nonNullTest();
                Foo<Integer, ArrayList<String>> fooNull_1 = f.nullTest();
                Foo<Integer, ArrayList<@Nullable String>> fooNull_2 = f.nullTest();
                Foo<@Nullable Integer, ArrayList<String>> fooNull_3 = f.nullTest();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void genericInferenceOnAssignmentsWithArrays() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
                class Test {
                  static class Foo<T extends @Nullable Object> {
                    Foo(T t) {}
                    static <U extends @Nullable Object> Foo<Foo<U>[]> test1Null(U u) {
                      return new Foo<>((Foo<U>[]) new Foo<?>[5]);
                    }
                    static <U> Foo<Foo<U>[]> test1Nonnull(U u) {
                      return new Foo<>((Foo<U>[]) new Foo<?>[5]);
                    }
                    static <U extends @Nullable Object> Foo<U>[] test2Null(U u) {
                      return (Foo<U>[]) new Foo<?>[5];
                    }
                    static <U> Foo<U>[] test2Nonnull(U u) {
                      return (Foo<U>[]) new Foo<?>[5];
                    }
                  }
                  static void testLocalAssign() {
                    Foo<Foo<Object>[]> f1 = Foo.test1Null(new Object());
                    Foo<Foo<@Nullable Object>[]> f2 = Foo.test1Null(new Object());
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    Foo<Foo<Object>[]> f3 = Foo.test1Null(null);
                    Foo<Foo<@Nullable Object>[]> f4 = Foo.test1Null(null);
                    Foo<Foo<Object>[]> f5 = Foo.test1Nonnull(new Object());
                    // BUG: Diagnostic contains: incompatible types:
                    Foo<Foo<@Nullable Object>[]> f6 = Foo.test1Nonnull(new Object());
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    Foo<Foo<Object>[]> f7 = Foo.test1Nonnull(null);
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    Foo<Foo<@Nullable Object>[]> f8 = Foo.test1Nonnull(null);
                    Foo<Object>[] f9 = Foo.test2Null(new Object());
                    Foo<@Nullable Object>[] f10 = Foo.test2Null(new Object());
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    Foo<Object>[] f11 = Foo.test2Null(null);
                    Foo<@Nullable Object>[] f12 = Foo.test2Null(null);
                    Foo<Object>[] f13 = Foo.test2Nonnull(new Object());
                    // BUG: Diagnostic contains: incompatible types:
                    Foo<@Nullable Object>[] f14 = Foo.test2Nonnull(new Object());
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    Foo<Object>[] f15 = Foo.test2Nonnull(null);
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    Foo<@Nullable Object>[] f16 = Foo.test2Nonnull(null);
                  }
                }
            """)
        .doTest();
  }

  @Test
  public void inferNestedNonNullUpperBound() {
    makeHelper()
        .addSourceLines(
            "TestCase.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            abstract class TestCase {
                static class Bar<T extends @Nullable Object> {}
                abstract <U> Bar<U> make(Bar<U> other);
                void test(Bar<Bar<String>> other) {
                    // BUG: Diagnostic contains: incompatible types: Bar<Bar<String>> cannot be converted to Bar<Bar<@Nullable String>>
                    Bar<Bar<@Nullable String>> unused = make(other);
                }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1035() {
    makeHelper()
        .addSourceLines(
            "Todo.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Todo {
                public static <T extends @Nullable Object> T foo(NullableSupplier<T> code) {
                    return code.get();
                }
                public static void main(String[] args) {
                    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                    Todo.<Object>foo(() -> null);
                    Todo.<@Nullable Object>foo(() -> null);
                }
                // this method should have no errors once we support inference for generic methods
                public static void requiresInferenceSupport() {
                    Todo.foo(() -> null);
                }
                @FunctionalInterface
                public interface NullableSupplier<T extends @Nullable Object> {
                    T get();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1138() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Foo {
                <T> Foo(T source) {
                }
                static <T> Foo createNoTypeArgs(T in) {
                    return new Foo(in);
                }
                static Foo createWithTypeArgNegative(String s) {
                    return new <String>Foo(s);
                }
                static Foo createWithTypeArgPositive() {
                    // BUG: Diagnostic contains: Type argument cannot be @Nullable, as method <T>Foo(T)'s type variable T is not @Nullable
                    return new <@Nullable String>Foo(null);
                }
            }
            """)
        .doTest();
  }

  @Test
  public void inferGenericConstructorTypeVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T> {
                <U extends @Nullable Object> Box(Supplier<? super U> supplier) {}
              }
              void test() {
                // no error: U should be inferred to be @Nullable
                Box<String> box = new Box<>(() -> null);
              }
              static class Box2<T> {
                <U extends Object> Box2(Supplier<? extends U> supplier) {}
              }
              void test2() {
                // BUG: Diagnostic contains: inference failure: type variable U is constrained to be @Nullable
                Box2<String> box = new Box2<>(() -> null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableAnnotOnMethodTypeVarUse() {
    makeHelper()
        .addSourceLines(
            "GenericMethod.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Function;
            public abstract class GenericMethod {
                abstract <V> @Nullable V foo(
                        Function<@Nullable V, @Nullable V> f);
                void testNegative(Function<@Nullable String, @Nullable String> f) {
                    this.<String>foo(f);
                }
                void testPositive(Function<String, String> f) {
                    // BUG: Diagnostic contains: incompatible types: Function<String, String> cannot be converted to
                    this.<String>foo(f);
                }
                void testPositive2(Function<@Nullable String, @Nullable String> f) {
                    // BUG: Diagnostic contains: dereferenced expression 'this.<String>foo(f)' is @Nullable
                    this.<String>foo(f).hashCode();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1176() {
    makeHelper()
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import java.util.Set;
            import java.util.concurrent.CompletableFuture;
            import java.util.concurrent.ConcurrentHashMap;
            class Foo {
                final Set<CompletableFuture<?>> f;
                public Foo() {
                    f = ConcurrentHashMap.newKeySet();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1178() {
    makeHelper()
        .addSourceLines(
            "SampleNullUnmarkedCall.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class SampleNullUnmarkedCall {
              @NullUnmarked
              static class Foo<T> {
                Foo(T t) {}
                static <U> Foo<U> id(U u) { return new Foo<>(u); }
              }
              static void test() {
                Foo<@Nullable Object> x = Foo.id(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void varargsOfGenericType() {
    makeHelper()
        .addSourceLines(
            "Varargs.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Varargs {
                static <T extends @Nullable Object> void foo(T... args) {
                }
                static void testNegative(@Nullable String s) {
                    Varargs.<@Nullable String>foo(s);
                }
                static void testPositive(@Nullable String s) {
                    // BUG: Diagnostic contains: passing @Nullable parameter
                    Varargs.<String>foo(s);
                }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableVarargsArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.Nullable;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
                <T extends Object> void varargsTest(T @Nullable... args) {}
                void f() {
                    String[] x = null;
                    this.<String>varargsTest(x);
                    this.<String>varargsTest((String[])null);
                }
            }
            """)
        .doTest();
  }

  @Test
  public void varargsConstructor() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.Nullable;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
                static class Foo {
                  <T> Foo(T @Nullable... args) {}
                }
                void testNegative() {
                    String[] x = null;
                    Foo f = new <String>Foo(x);
                    f = new <String>Foo((String[])null);
                }
                static class Bar {
                  <T> Bar(T... args) {}
                }
                void testPositive() {
                    String[] x = null;
                    // BUG: Diagnostic contains: passing @Nullable parameter
                    Bar b = new <String>Bar(x);
                    // BUG: Diagnostic contains: passing @Nullable parameter
                    b = new <String>Bar((String[])null);
                }
            }
            """)
        .doTest();
  }

  @Test
  public void genericInferenceOnReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
                class Test {
                  static class Foo<T extends @Nullable Object> {
                    Foo(T t) {}
                    static <U extends @Nullable Object> Foo<U> makeNull(U u) {
                      return new Foo<>(u);
                    }
                    static <U> Foo<U> makeNonNull(U u) {
                      return new Foo<>(u);
                    }
                  }
                  static Foo<@Nullable Object> makeNull() {
                    // legal
                    return Foo.makeNull(null);
                  }
                  static Foo<Object> makeNullInvalid() {
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    return Foo.makeNull(null);
                  }
                  static Foo<@Nullable Object> makeNonNullInvalid() {
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    return Foo.makeNonNull(null);
                  }
                  static Foo<@Nullable Object> makeNonNullInvalid2() {
                    // BUG: Diagnostic contains: incompatible types:
                    return Foo.makeNonNull(new Object());
                  }
                  static Foo<Object> makeNonNullValid() {
                    return Foo.makeNonNull(new Object());
                  }
                }
            """)
        .doTest();
  }

  @Test
  public void genericInferenceOnParameterPassing() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
                class Test {
                  static class Foo<T extends @Nullable Object> {
                    Foo(T t) {}
                    static <U extends @Nullable Object> Foo<U> makeNull(U u) {
                      return new Foo<>(u);
                    }
                    static <U> Foo<U> makeNonNull(U u) {
                      return new Foo<>(u);
                    }
                  }
                  static void handleFooNullable(Foo<@Nullable Object> f) {}
                  static void handleFooNonNull(Foo<Object> f) {}
                  static void testCalls() {
                    // legal
                    handleFooNullable(Foo.makeNull(null));
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    handleFooNonNull(Foo.makeNull(null));
                    handleFooNullable(Foo.makeNull(new Object()));
                    handleFooNonNull(Foo.makeNull(new Object()));
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    handleFooNullable(Foo.makeNonNull(null));
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    handleFooNonNull(Foo.makeNonNull(null));
                    // BUG: Diagnostic contains: incompatible types: Foo<Object>
                    handleFooNullable(Foo.makeNonNull(new Object()));
                    handleFooNonNull(Foo.makeNonNull(new Object()));
                  }
                  static void handleFooNullableVarargs(Foo<@Nullable Object>... args) {}
                  static void handleFooNonNullVarargs(Foo<Object>... f) {}
                  static void testVarargsCalls() {
                    // legal
                    handleFooNullableVarargs(Foo.makeNull(null));
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    handleFooNonNullVarargs(Foo.makeNull(null));
                    handleFooNullableVarargs(Foo.makeNull(new Object()));
                    handleFooNonNullVarargs(Foo.makeNull(new Object()));
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    handleFooNullableVarargs(Foo.makeNonNull(null));
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                    handleFooNonNullVarargs(Foo.makeNonNull(null));
                    // BUG: Diagnostic contains: incompatible types: Foo<Object>
                    handleFooNullableVarargs(Foo.makeNonNull(new Object()));
                    handleFooNonNullVarargs(Foo.makeNonNull(new Object()));
                  }
                }
            """)
        .doTest();
  }

  @Test
  public void genericMethodReturningTypeVariable() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              static class Foo<T extends @Nullable Object> {}
              static <T extends @Nullable Object> T returnTypeVariable(Foo<T> t) {
                throw new RuntimeException();
              }
              static void takesNullable(@Nullable String s) {}
              static void test() {
                // legal, with explicit types
                takesNullable(Test.<@Nullable String>returnTypeVariable(new Foo<@Nullable String>()));
                // legal, with inference
                takesNullable(returnTypeVariable(new Foo<@Nullable String>()));
                // also legal
                takesNullable(returnTypeVariable(new Foo<String>()));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void typeVarReturnNonNullUpperBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              static <T> T id(T t) {
                return t;
              }
              static void takesNullable(@Nullable String s) {}
              static void test() {
                // legal
                takesNullable(id("hi"));
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                takesNullable(id(null));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void firstOrDefaultSelfContained() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              public interface List<E extends @Nullable Object> { boolean isEmpty(); E get(int index); }
              public static class Collections {
                public static <T extends @Nullable Object> List<T> singletonList(T element) {
                  throw new UnsupportedOperationException();
                }
              }
              public static <U extends @Nullable Object> U firstOrDefault(List<U> list, U defaultValue) {
                return list.isEmpty() ? defaultValue : list.get(0);
              }
              static void use() {
                // should infer T -> @Nullable String
                String result = firstOrDefault(Collections.singletonList(null), "hello");
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
                // should infer T -> @NonNull String
                String result2 = firstOrDefault(Collections.singletonList("bye"), "hello");
                result2.hashCode();
                // should infer T -> @Nullable String (testing that inference is called from dataflow)
                String result3 = firstOrDefault(Collections.singletonList(null), "hello");
                // BUG: Diagnostic contains: dereferenced expression 'result3' is @Nullable
                result3.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void firstOrDefaultLocalVarParam() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              public interface List<E extends @Nullable Object> { boolean isEmpty(); E get(int index); }
              public static class Collections {
                public static <T extends @Nullable Object> List<T> singletonList(T element) {
                  throw new UnsupportedOperationException();
                }
              }
              public static <U extends @Nullable Object> U firstOrDefault(List<U> list, U defaultValue) {
                return list.isEmpty() ? defaultValue : list.get(0);
              }
              static void use() {
                String x = null;
                // should infer T -> @Nullable String
                String result = firstOrDefault(Collections.singletonList(x), "hello");
                // BUG: Diagnostic contains: dereferenced expression 'result' is @Nullable
                result.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void localsWithTypesFromDataflow() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
                static <T extends @Nullable Object> T id(T t) {
                    return t;
                }
                void testPositive() {
                    String s = null;
                    String t = id(s);
                    // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                    t.hashCode();
                }
                void testNegative() {
                    String s = "hello";
                    String t = id(s);
                    t.hashCode();
                }
                String field = "hello";
                void testField() {
                    String s = null;
                    // BUG: Diagnostic contains: inference failure: type variable T constrained to be both @NonNull and @Nullable
                    field = id(s);
                }
                @Nullable String field2 = null;
                void testField2() {
                    String s = null;
                    field2 = id(s);
                    // BUG: Diagnostic contains: dereferenced expression 'field2' is @Nullable
                    field2.hashCode();
                    s = "hello";
                    field2 = id(s);
                    field2.hashCode();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableTypeVarToNonNullField() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
                static <T extends @Nullable Object> @Nullable T f(T t) {
                    return null;
                }
                String field = "hello";
                void testField() {
                    // BUG: Diagnostic contains: assigning @Nullable expression to @NonNull field
                    field = f("hello");
                }
            }
            """)
        .doTest();
  }

  /**
   * Pins the diagnostics NullAway reports for a call involving an explicitly annotated
   * type-variable use (https://github.com/uber/NullAway/issues/1730). A line that expects no
   * diagnostic is checked in full here. For a line carrying a {@code // BUG: Diagnostic contains:}
   * comment, {@link
   * GenericInferenceErrorReportingTests#annotatedTypeVariableUseIsNotAnInferenceFailure()} is what
   * guards the absence of the redundant inference failure.
   */
  @Test
  public void nullableTypeVariableReturnDoesNotCauseInferenceFailure() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.jspecify.annotations.NonNull;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> @Nullable T id(T value) {
                return value;
              }
              static String nonNullTarget() {
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                return id("");
              }
              static @Nullable String nullableTarget() {
                return id("");
              }
              static String witness() {
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                return Test.<String>id("");
              }
              static String nullableArgument(@Nullable String value) {
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                return id(value);
              }
              static <T extends @Nullable Object> T nonNullParamId(@NonNull T value) {
                return value;
              }
              static void testNonNullParamWarn(@Nullable String value) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'value' where @NonNull is required
                nonNullParamId(value);
              }
            }
            """)
        .doTest();
  }

  /** Regression test for the {@code @Contract} case from issue 1730. */
  @Test
  public void nullableTypeVariableReturnWithContractDoesNotCauseInferenceFailure() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jetbrains.annotations.Contract;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              @Contract("_, !null -> !null")
              static <T extends @Nullable Object> @Nullable T first(
                  @Nullable T first, @Nullable T second) {
                return first != null ? first : second;
              }
              @Contract("_, !null -> !null")
              static @Nullable String firstString(
                  @Nullable String first, @Nullable String second) {
                return first != null ? first : second;
              }
              static String useGeneric(@Nullable String value) {
                return first(value, "");
              }
              static String useConcrete(@Nullable String value) {
                return firstString(value, "");
              }
              static String useWitness(@Nullable String value) {
                return Test.<String>first(value, "");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void dataflowAndLoops() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
                static <T extends @Nullable Object> T id(T t) {
                    return t;
                }
                void testLoop1() {
                    String s = "hello";
                    while (true) {
                        String t = id(s);
                        // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                        t.hashCode();
                        s = null;
                    }
                }
                void testLoop2() {
                    String t = "hello";
                    while (true) {
                        // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                        t.hashCode();
                        String s = null;
                        t = id(s);
                    }
                }
                void testLoop3() {
                    String t = "hello";
                    String s = "hello";
                    t.hashCode();
                    int i = 2;
                    while (i > 0) {
                        t = id(s);
                        s = null;
                        i--;
                    }
                    // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                    t.hashCode();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void otherExprsWithTypesFromDataflow() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
                static <T extends @Nullable Object> T id(T t) {
                    return t;
                }
                @Nullable String field;
                void testPositive() {
                    String t = id(field);
                    // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                    t.hashCode();
                }
                void testNegative() {
                    if (field != null) {
                        String t = id(field);
                        t.hashCode();
                    }
                }
                void testUnsupported() {
                    String s = "hello";
                    // dataflow can't prove the argument is non-null
                    String t = id(s == null ? null : s);
                    // BUG: Diagnostic contains: dereferenced expression 't' is @Nullable
                    t.hashCode();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void varargsInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
                static class Foo<T extends @Nullable Object> {}
                public static <U extends @Nullable Object> Foo<U> make(U... args) {
                  throw new RuntimeException();
                }
                static <V extends @Nullable String> V makeStr(V v) {
                  return v;
                }
                Foo<String> foo1 = make("hello", "world");
                Foo<@Nullable String> foo2 = make("hello", null, "world");
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Foo<String> foo3 = make("hello", null, "world");
                Foo<@Nullable String> foo4 = make("hello", "world");
                Foo<@Nullable String> foo5 = make("hello", "world", makeStr(null));
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Foo<String> foo6 = make("hello", "world", makeStr(null));
                // Inference from assignment context only (no args)
                Foo<String> foo7 = make();
                // And the nullable variant
                Foo<@Nullable String> foo8 = make();
            }
            """)
        .doTest();
  }

  @Test
  public void varargsInferencePassingArray() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
                static class Foo<T extends @Nullable Object> {}
                public static <U extends @Nullable Object> Foo<U> make(U... args) {
                  throw new RuntimeException();
                }
                Foo<String> foo1 = make(new String[] { "hello", "world" });
                Foo<@Nullable String> foo2 = make(new @Nullable String[] { "hello", null, "world" });
                // BUG: Diagnostic contains: incompatible types:
                Foo<String> foo3 = make(new @Nullable String[] { "hello", null, "world" });
                Foo<@Nullable String> foo4 = make(new String[] { "hello", "world" });
            }
            """)
        .doTest();
  }

  @Test
  public void inferenceWithFieldAssignment() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
                static <T extends @Nullable Object> T id(T t) {
                    return t;
                }
                String field = id("hello");
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                String field2 = id(null);
                @Nullable String field3 = id(null);
                @Nullable String field4 = id("hello");
            }
            """)
        .doTest();
  }

  @Test
  public void functionBoundRespectedDuringInference() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.function.Function;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T applyId(Function<T, T> f, T x) {
                return f.apply(x);
              }
              static void ok(Function<@Nullable String, @Nullable String> f) {
                String s = applyId(f, null);
              }
              static void bad(Function<String, String> f) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                String s = applyId(f, null);
              }
            }
            """)
        .doTest();
  }

  @Ignore("we need to respect upper bounds of class type variables during inference")
  @Test
  public void inferenceNonNullUpperBoundOnClassTypeVar() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test<T> {
              static <U extends @Nullable Object> Test<U> make(U u) {
                throw new RuntimeException();
              }
              static <V extends @Nullable Object> V unmake(Test<V> v) {
                throw new RuntimeException();
              }
              static void test() {
                // BUG: Diagnostic contains:
                String s = unmake(make(null));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void arrayCovariantSubtyping() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T f(T[] vals, T other) {
                return other;
              }
              String stringField = "hi";
              void test(String[] arr, @Nullable String[] arr2) {
                // legal, should infer T -> @Nullable String
                // we can pass String[] due to covariant subtyping
                String s1 = f(arr, null);
                // BUG: Diagnostic contains: dereferenced expression 's1' is @Nullable
                s1.hashCode();
                String s2 = f(arr, "hi");
                // legal
                s2.hashCode();
                // BUG: Diagnostic contains: incompatible types: @Nullable String []
                stringField = f(arr2, "hi");
              }
            }
            """)
        .doTest();
  }

  /**
   * A more complex example inspired by code from Spring. Testing that we properly distinguish
   * {@code T} from {@code @Nullable T}.
   */
  @Test
  public void rowMapperTest() {
    makeHelper()
        .addSourceLines(
            "TestRowMapper.java",
            """
            import java.util.List;
            import org.jspecify.annotations.*;
            @NullMarked
            class TestRowMapper {

              interface RowMapper<T extends @Nullable Object> {}

              static class SingleColumnRowMapper<T> implements RowMapper<@Nullable T> {
                public SingleColumnRowMapper(Class<T> requiredType) {}
              }

              protected <U> RowMapper<@Nullable U> getSingleColumnRowMapper(Class<U> requiredType) {
                return new SingleColumnRowMapper<>(requiredType);
              }

              public <V extends @Nullable Object> List<V> query(RowMapper<V> rowMapper) {
                throw new RuntimeException();
              }

              public <W> List<@Nullable W> queryForList(Class<W> elementType) {
                return query(getSingleColumnRowMapper(elementType));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void selfContainedOptional() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Test {
                static class Optional<T> {
                    public static <T> Optional<T> ofNullable(@Nullable T value) {
                        return new Optional<>();
                    }
                    public static <T> Optional<T> of(T value) {
                        throw new RuntimeException();
                    }
                }
                public static <U extends @Nullable Object> Optional<U> optionalResultNegative1(@Nullable U value) {
                    return Optional.ofNullable(value);
                }
                public static <U> Optional<U> optionalResultNegative2(@Nullable U value) {
                    return Optional.ofNullable(value);
                }
                public static <U extends @Nullable Object> Optional<U> optionalResultPositive1(@Nullable U value) {
                    // BUG: Diagnostic contains: inference failure: type variable T is constrained to be @Nullable, but its upper bound requires it to be @NonNull
                    return Optional.of(value);
                }
                // identical to above, testing the other error message
                public static <U extends @Nullable Object> Optional<U> optionalResultPositive2(@Nullable U value) {
                    // BUG: Diagnostic contains: passing @Nullable parameter 'value'
                    return Optional.of(value);
                }
            }
            """)
        .doTest();
  }

  /**
   * Tests that we properly handle an {@code AssignmentTree} that re-assigns a local variable (not
   * just {@code VariableTree} initializers).
   */
  @Test
  public void reassignLocal() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Test {
                private <R> @Nullable R make() {
                    return null;
                }
                void test() {
                    Object result = null;
                    result = make();
                }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1238() {
    makeHelper()
        .addSourceLines(
            "CreatorMediator.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class CreatorMediator {
                private final PropertyModel mCreatorModel = new PropertyModel();
                private void followClickHandler() {
                    // this is fine: byte[] is assignable to byte @Nullable []
                    WebFeedBridge.followFromId(
                            mCreatorModel.get(CreatorProperties.WEB_FEED_ID_KEY));
                }
                private static class PropertyModel {
                    <T extends @Nullable Object> T get(Key<T> key) {
                        throw new RuntimeException();
                    }
                }
                private static class CreatorProperties {
                    static final Key<byte[]> WEB_FEED_ID_KEY = makeKey();
                    private static Key<byte[]> makeKey() {
                        throw new RuntimeException();
                    }
                }
                private static class WebFeedBridge {
                    static void followFromId(
                            byte @Nullable [] webFeedId) {
                    }
                }
                interface Key<T> {
                }
            }
            """)
        .doTest();
  }

  /** various cases where dataflow analysis forces inference to run for a generic method call */
  @Test
  public void inferenceFromDataflow() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Test {
              static <U extends @Nullable Object> U id(U u) {
                return u;
              }
              static void testReceiver() {
                // to ensure that dataflow runs
                Object x = new Object(); x.toString();
                Object y = null;
                // BUG: Diagnostic contains: dereferenced expression 'id(y)' is @Nullable
                id(y).toString();
              }
              static Object testReturn() {
                // to ensure that dataflow runs
                Object x = new Object(); x.toString();
                Object y = null;
                // BUG: Diagnostic contains: passing @Nullable parameter 'y' where @NonNull is required
                return id(y);
              }
              static Object testReturnWithParens() {
                // to ensure that dataflow runs
                Object x = new Object(); x.toString();
                Object y = null;
                // BUG: Diagnostic contains: passing @Nullable parameter 'y' where @NonNull is required
                return (((id(y))));
              }
              static Object testReturnNested() {
                // to ensure that dataflow runs
                Object x = new Object(); x.toString();
                Object y = null;
                // BUG: Diagnostic contains: passing @Nullable parameter 'y' where @NonNull is required
                return id(id(y));
              }
              static void takesNonNull(Object o) {}
              static void testParam() {
                // to ensure that dataflow runs
                Object x = new Object(); x.toString();
                Object y = null;
                // BUG: Diagnostic contains: passing @Nullable parameter 'y' where @NonNull is required
                takesNonNull(id(y));
              }
            }
            """)
        .doTest();
  }

  /** This was reduced from some real code in JUnit */
  @Test
  public void capturedTypeAtNullUnmarkedBoundary() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            @NullMarked
            class Test {
              static class Foo<T> {}

              @NullUnmarked
              static class Box<T> {}

              @NullUnmarked
              static class Api {
                static Box<?> wildcard() {
                  return null;
                }

                static <T> Foo<T> convert(Box<T> box) {
                  return null;
                }
              }

              static Foo<?> getReturnType() {
                var returnType = Api.convert(Api.wildcard());
                // BUG: Diagnostic contains: incompatible types: Foo<capture of ?> cannot be converted to Foo<?> (target wildcard upper bound is Object; source wildcard upper bound is @Nullable Object; source wildcard is the type argument for type variable T of Box)
                return returnType;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void capturedTypeGetsDirectNullableAnnotationFromTypeVariableUse() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable Object> {}
              static class Holder<T extends @Nullable Object> {
                Foo<@Nullable T> value;

                Holder(Foo<@Nullable T> value) {
                  this.value = value;
                }
              }

              static Foo<? extends Object> test(Holder<?> holder) {
                // BUG: Diagnostic contains: incompatible types: Foo<@Nullable capture of ?> cannot be converted to Foo<? extends Object> (target wildcard upper bound is Object; source wildcard upper bound is @Nullable Object; source wildcard is the type argument for type variable T of Holder)
                return holder.value;
              }
            }
            """)
        .doTest();
  }

  /** Self-contained test for https://github.com/uber/NullAway/issues/1157. */
  @Test
  public void atomicReferenceFieldUpdaterSelfContained() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            @NullMarked
            public class Test {
                static class AtomicReferenceFieldUpdater<T, V extends @Nullable Object> {
                  public static <U,W extends @Nullable Object> AtomicReferenceFieldUpdater<U,W>
                    newUpdater(Class<U> tclass, Class<@NonNull W> vclass, String fieldName) { throw new RuntimeException(); }
                }
                static final AtomicReferenceFieldUpdater<Test, @Nullable Object> RESULT_UPDATER =
                        AtomicReferenceFieldUpdater.newUpdater(Test.class, Object.class, "result");
            }
            """)
        .doTest();
  }

  @Test
  public void atomicReferenceFieldUpdaterFromJDK() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
            @NullMarked
            public class Test {
                static final AtomicReferenceFieldUpdater<Test, @Nullable Object> RESULT_UPDATER =
                        AtomicReferenceFieldUpdater.newUpdater(Test.class, Object.class, "result");
            }
            """)
        .doTest();
  }

  @Test
  public void inferenceFromReceiverPassing() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Test {
              static class Foo<T extends @Nullable Object> {
                T get() {
                  throw new UnsupportedOperationException();
                }
              }
              static <U extends @Nullable Object> Foo<U> make(U u) {
                throw new RuntimeException();
              }
              static void test() {
                // BUG: Diagnostic contains: dereferenced expression
                make(null).get().toString();
                // Also with a parenthesized receiver
                // BUG: Diagnostic contains: dereferenced expression
                ((make(null))).get().toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void typeOfParameterWithInferredLambda() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.Map;
            @NullMarked
            class Test {
              interface MyFunc<T extends @Nullable Object, U extends @Nullable Object> {
                U apply(T t);
              }
              static Map<String, @Nullable String> bar(Map<String, @Nullable String> m) {
                return m;
              }
              static <T extends @Nullable String, U extends @Nullable String> void foo(
                  MyFunc<Map<T, U>, Map<T, U>> f) {}
              void test() {
                foo(map -> bar(map));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardAndArrayTypes() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import java.util.function.Function;
            @NullMarked
            class Test {
              public final <T, V> V map(Function<? super T, ? extends V> mapper) { throw new RuntimeException(); }
              public Integer foo(byte[] bytes) { return Integer.valueOf(3); }
              public void test() {
                map((byte[] bytes) -> foo(bytes));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferenceAndMismatchedTypeParamsForParameter() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              class Box<T extends @Nullable Object> {}
              <U extends @Nullable Object> void swapBoxes(Box<U> box1, Box<U> box2) {}
              void testPositive(Box<String> box1, Box<@Nullable String> box2) {
                // BUG: Diagnostic contains: incompatible types: Test.Box<@Nullable String> cannot be converted to Test.Box<String>
                swapBoxes(box1, box2);
              }
              void TestNegative(Box<@Nullable String> box1, Box<@Nullable String> box2) {
                swapBoxes(box1, box2);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void instanceGenericMethodWithMethodRefArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import java.util.List;
            import java.util.function.Consumer;
            @NullMarked
            class Test {
                    public <E extends Enum<E>> void visitEnum(String descriptor, String value, Consumer<E> consumer) {}
                    void test(String s1, String s2, List<Object> l) {
                        visitEnum(s1, s2, l::add);
                    }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1444() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.stream.Stream;
            @NullMarked
            public class Test {
                static class DefaultConfiguration {
                    final @Nullable Stream<? extends String> children;

                    DefaultConfiguration(Stream<? extends String> children) {
                        this.children = children;
                    }
                }
                final Stream<? extends String> children;
                Test(DefaultConfiguration configuration) {
                    this.children = notNull(configuration.children, "children must not be null");
                }
                public static <T> T notNull(@Nullable T object, String message) {
                    throw new RuntimeException("todo");
                }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1455() {
    makeHelper()
        .addSourceLines(
            "Foo.java",
            """
            import org.jspecify.annotations.Nullable;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Foo<OuterT> {
              interface Supplier<T extends @Nullable Object> {
                T get();
              }
              Supplier<@Nullable OuterT> sup = make();
              Supplier<@Nullable OuterT> make() {
                throw new RuntimeException();
              }
              <T extends Supplier<?>> T acceptSup(T supplier) {
                return supplier;
              }
              void test() {
                // Here, javac computes the formal parameter type as Supplier<OuterT>.
                // NullAway substitutes the inferred Supplier<@Nullable OuterT> into the declared
                // method type, matching the actual parameter, so we get no error.
                acceptSup(sup);
              }
              <T extends Supplier<?>> void acceptTwoSup(T supplier1, T supplier2) {
              }
              Supplier<OuterT> sup2 = make2();
              Supplier<OuterT> make2() {
                throw new RuntimeException();
              }
              // Tests that inference computes a consistent substitution for the type variables.
              void test2() {
                // BUG: Diagnostic contains: incompatible types: Supplier<OuterT> cannot be converted to Supplier<@Nullable OuterT>
                acceptTwoSup(sup, sup2);
                // BUG: Diagnostic contains: incompatible types: Supplier<@Nullable OuterT> cannot be converted to Supplier<OuterT>
                acceptTwoSup(sup2, sup);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1453() {
    makeHelper()
        .addSourceLines(
            "NullUtil.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class NullUtil {
              @SuppressWarnings("NullAway")
              public static <T> T assumeNonNull(@Nullable T object) {
                return object;
              }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import static com.uber.NullUtil.assumeNonNull;
            import java.util.concurrent.atomic.AtomicReference;
            @NullMarked
            class Test {
              private final AtomicReference<@Nullable List<String>> ref = new AtomicReference<>();
              void test() {
                for (String s: assumeNonNull(ref.get())) {
                  System.out.println(s);
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1493RequireNonNullElseWithJdkModels() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true",
                    "-XepOpt:NullAway:JSpecifyJDKModels=true")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Objects;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              String nonNullElse(@Nullable String nullableString, String defaultValue) {
                return Objects.requireNonNullElse(nullableString, defaultValue);
              }
            }
            """)
        .doTest();
  }

  /**
   * Without the JSpecify JDK models, {@code Objects.requireNonNullElse} is unannotated: the
   * {@code @Nullable} argument and the {@code @NonNull} return constrain its {@code T} both ways,
   * so the inference failure (https://github.com/uber/NullAway/issues/1493) is still reported. The
   * flags differ from those of {@link #issue1493RequireNonNullElseWithJdkModels()} only in {@code
   * JSpecifyJDKModels}, so the pair shows that the models remove the warning.
   */
  @Test
  public void issue1493RequireNonNullElseWithoutJdkModels() {
    makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:WarnOnGenericInferenceFailure=true",
                    "-XepOpt:NullAway:JSpecifyJDKModels=false")))
        .addSourceLines(
            "Test.java",
            """
            import java.util.Objects;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              String nonNullElse(@Nullable String nullableString, String defaultValue) {
                // BUG: Diagnostic contains: inference failure: type variable T constrained to be both @NonNull and @Nullable
                return Objects.requireNonNullElse(nullableString, defaultValue);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedReceivers() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Foo<T extends @Nullable Object> {
                static <U extends @Nullable Object> Foo<U> of(Foo<U> other) {
                  throw new RuntimeException();
                }
                Foo<T> or(Foo<T> other) { return this; }
              }
              // infer Foo<@Nullable String> as the type of the Foo.of() call via
              // its parameter, and use that type to determine the return type of
              // the or() call is Foo<@Nullable String>
              static Foo<@Nullable String> FOO =
                Foo.of(new Foo<@Nullable String>()).or(new Foo<@Nullable String>());

              // like the case above, but more deeply nested
              static Foo<@Nullable String> FOO2 =
                Foo.of(new Foo<@Nullable String>())
                  .or(new Foo<@Nullable String>())
                  .or(new Foo<@Nullable String>());

              // a true positive case
              // BUG: Diagnostic contains: incompatible types: Foo<@Nullable String> cannot be converted to Foo<String>
              static Foo<String> WRONG =
                Foo.of(new Foo<@Nullable String>()).or(new Foo<@Nullable String>());
            }
            """)
        .doTest();
  }

  @Test
  public void caffeineNestedArgToGenericMethod() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.Map;
            import java.util.concurrent.CompletableFuture;
            @NullMarked
            class Test {
              static interface Cache<K, V extends @Nullable Object> {
                Policy<K, @NonNull V> policy();
              }
              static interface Policy<K, V> {
                Map<K, CompletableFuture<V>> refreshes();
              }
              static <K,V> void m(@Nullable Map<K,V> map) {}
              void test(Cache<Integer, @Nullable Object> cache) {
                // javac computes the formal parameter type as @Nullable Map<Integer, CompletableFuture<@Nullable Object>>,
                // presumably based on the @Nullable Object type argument for cache.
                // NullAway determines the type of the actual parameter correctly as
                // Map<Integer, CompletableFuture<Object>> (due to the @NonNull annotation on V in the signature for policy).
                // Substituting the inferred types into the declared method type preserves this
                // nested nullability, so we don't report an error here.
                m(cache.policy().refreshes());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedGenericMethodSubstitutionPreservesTopLevelNullability() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.concurrent.CompletableFuture;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> void accept(Box<@Nullable T> box) {}
              void test(Box<CompletableFuture<Object>> box) {
                // BUG: Diagnostic contains: incompatible types: Box<CompletableFuture<Object>> cannot be converted to Box<@Nullable CompletableFuture<Object>>
                accept(box);
              }
            }
            """)
        .doTest();
  }

  /**
   * Regression test for <a href="https://github.com/uber/NullAway/issues/1512">issue 1512</a>:
   * overriding a method must not narrow a {@code @Nullable} method type-variable upper bound to a
   * non-null bound (and must not widen a non-null bound to {@code @Nullable}).
   */
  @Test
  public void overrideNarrowsNullableMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo {
                <T extends @Nullable Object> void bar(T arg);
              }
              static class Baz implements Foo {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a non-null upper bound
                public <T> void bar(T arg) {
                  arg.hashCode();
                }
              }
              static void use(Foo f) {
                f.<@Nullable String>bar(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideWidensNonNullMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo {
                <T> void bar(T arg);
              }
              static class Baz implements Foo {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends @Nullable Object> void bar(T arg) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overridePreservesNullableMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo {
                <T extends @Nullable Object> void bar(T arg);
              }
              static class Baz implements Foo {
                @Override
                public <T extends @Nullable Object> void bar(T arg) {
                  // legal override; arg may be null when T is instantiated as @Nullable
                  if (arg != null) {
                    arg.hashCode();
                  }
                }
              }
              static void use(Foo f) {
                f.<@Nullable String>bar(null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overridePreservesNonNullMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            @NullMarked
            class Test {
              interface Foo {
                <T> void bar(T arg);
              }
              static class Baz implements Foo {
                @Override
                public <T> void bar(T arg) {
                  arg.hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideNarrowsNullableMethodTypeVariableBoundOnReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo {
                <T extends @Nullable Object> T bar();
              }
              static class Baz implements Foo {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a non-null upper bound
                public <T> T bar() {
                  throw new RuntimeException();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overridePreservesSubstitutedNullableMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<X extends @Nullable Object> {
                <T extends X> void bar(T arg);
              }
              static class Baz implements Foo<@Nullable Object> {
                @Override
                public <T extends @Nullable Object> void bar(T arg) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overridePreservesSubstitutedNonNullMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<X extends @Nullable Object> {
                <T extends X> void bar(T arg);
              }
              // Instantiating X as non-null String means the method type-var bound is non-null;
              // overriding with <T extends String> must not be treated as a mismatch.
              static class Baz implements Foo<String> {
                @Override
                public <T extends String> void bar(T arg) {
                  arg.hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideNarrowsSubstitutedNullableMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<X extends @Nullable Object> {
                <T extends X> void bar(T arg);
              }
              static class Baz implements Foo<@Nullable Object> {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a non-null upper bound
                public <T> void bar(T arg) {
                  arg.hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideWidensSubstitutedExplicitNonNullMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<X extends @Nullable Object> {
                <T extends X> void bar(T arg);
              }
              static class Baz<Y extends @Nullable Object> implements Foo<@NonNull Y> {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends Y> void bar(T arg) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overridePreservesFreeTypeVariableMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<X extends @Nullable Object> {
                <T extends X> void bar(T arg);
              }
              interface Bar<X extends @Nullable Object> extends Foo<X> {
                @Override
                <T extends X> void bar(T arg);
              }
              static class Baz<X extends @Nullable Object> implements Foo<X> {
                @Override
                public <T extends X> void bar(T arg) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overridePreservesExplicitNonNullFreeTypeVariableMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<X extends @Nullable Object> {
                <T extends @NonNull X> void bar(T arg);
              }
              static class Baz<X extends @Nullable Object> implements Foo<X> {
                @Override
                public <T extends @NonNull X> void bar(T arg) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideWidensExplicitNonNullFreeTypeVariableMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<X extends @Nullable Object> {
                <T extends @NonNull X> void bar(T arg);
              }
              static class Baz<X extends @Nullable Object> implements Foo<X> {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a @Nullable upper bound
                public <T extends X> void bar(T arg) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideNarrowsNullableFreeTypeVariableMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<X extends @Nullable Object> {
                <T extends X> void bar(T arg);
              }
              static class Baz<X extends @Nullable Object> implements Foo<X> {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a non-null upper bound
                public <T extends @NonNull X> void bar(T arg) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overridePreservesChainedMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo {
                <X extends @Nullable Object, T extends X> void bar(T arg);
              }
              static class Baz implements Foo {
                @Override
                public <X extends @Nullable Object, T extends X> void bar(T arg) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideNarrowsFreeTypeVariableMethodTypeVariableBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              interface Foo<X> {
                <T extends @Nullable X> void bar(T arg);
              }
              static class Baz<X> implements Foo<X> {
                @Override
                // BUG: Diagnostic contains: Method type variable T has a non-null upper bound
                public <T extends X> void bar(T arg) {
                  arg.hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overrideOfNullUnmarkedMethodTypeVariableBoundSkipped() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.NullUnmarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              @NullUnmarked
              interface Foo {
                <T extends @Nullable Object> void bar(T arg);
              }
              // Overriding an unmarked generic method must not warn: unmarked type-variable
              // bounds are not part of the nullness contract.
              static class Baz implements Foo {
                @Override
                public <T> void bar(T arg) {
                  if (arg != null) {
                    arg.hashCode();
                  }
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void repeatedCallsToOneGenericMethodAreInferredSeparately() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Test {
              static <T extends @Nullable Object> T id(T t) {
                return t;
              }
              static <T extends @Nullable Object, U extends @Nullable Object> T chooseFirst(T t, U u) {
                return t;
              }
              static void takesNonNull(String s) {}
              static void test(@Nullable String s, String t) {
                String u = chooseFirst(id(t), id(s));
                // this is safe since each call to id gets its own inference variable
                u.hashCode();
              }
              static void reversed(@Nullable String s, String t) {
                String u = chooseFirst(id(s), id(t));
                // BUG: Diagnostic contains: dereferenced expression 'u' is @Nullable
                u.hashCode();
              }
              static void deeperNesting(@Nullable String s, String t) {
                String u = chooseFirst(id(id(t)), id(id(s)));
                u.hashCode();
              }
              static void deeperNestingReversed(@Nullable String s, String t) {
                String u = chooseFirst(id(id(s)), id(id(t)));
                // BUG: Diagnostic contains: dereferenced expression 'u' is @Nullable
                u.hashCode();
              }
              static void nonLocalContext(@Nullable String s, String t) {
                takesNonNull(chooseFirst(id(t), id(s)));
              }
              static void nonLocalContextReversed(@Nullable String s, String t) {
                // BUG: Diagnostic contains: passing @Nullable parameter 's' where @NonNull is required
                takesNonNull(chooseFirst(id(s), id(t)));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1291RepeatedDiamond() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Test {
              static class Box<T extends @Nullable Object> {
                Box(T t) {}
              }
              static <A extends @Nullable Object, B extends @Nullable Object> A first(
                  Box<A> a, Box<B> b) {
                throw new UnsupportedOperationException();
              }
              static void test(@Nullable String s, String t) {
                String u = first(new Box<>(t), new Box<>(s));
                // safe, since each diamond gets its own inference variables
                u.hashCode();
              }
              static void reversed(@Nullable String s, String t) {
                String u = first(new Box<>(s), new Box<>(t));
                // BUG: Diagnostic contains: dereferenced expression 'u' is @Nullable
                u.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1585NestedNullnessInSubstitution() {
    makeHelper()
        .addSourceLines(
            "Example.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public final class Example {
              public static final class Box<T extends @Nullable Object> {
                public <R extends @Nullable Object> Box<R> flatMap(
                    Mapper<? super T, ? extends Box<R>> mapper) {
                  throw new UnsupportedOperationException();
                }
              }
              @FunctionalInterface
              public interface Mapper<P extends @Nullable Object, R extends @Nullable Object> {
                R apply(P value);
              }
              static void acceptsNested(Box<Box<@Nullable String>> box) {}
              static void reproduce(Box<Box<Box<@Nullable String>>> nested) {
                // R should be inferred as Box<@Nullable String>, which is @NonNull at the top level
                // but has a nested @Nullable type argument
                var flat = nested.flatMap(box -> box);
                acceptsNested(flat);
              }
              static void blockBody(Box<Box<Box<@Nullable String>>> nested) {
                var flat = nested.flatMap(box -> { return box; });
                acceptsNested(flat);
              }
              static void explicitDeclaration(Box<Box<Box<@Nullable String>>> nested) {
                Box<Box<@Nullable String>> flat = nested.flatMap(box -> box);
                acceptsNested(flat);
              }
              static void nestedNonNull(Box<Box<Box<String>>> nested) {
                var flat = nested.flatMap(box -> box);
                // BUG: Diagnostic contains: incompatible types: Box<Box<String>> cannot be converted to Box<Box<@Nullable String>>
                acceptsNested(flat);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1585LambdaArgumentOfNestedCall() {
    makeHelper()
        .addSourceLines(
            "Example.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public final class Example {
              public static final class Box<T extends @Nullable Object> {
                public <R extends @Nullable Object> Box<R> flatMap(
                    Mapper<? super T, ? extends Box<R>> mapper) {
                  throw new UnsupportedOperationException();
                }
              }
              @FunctionalInterface
              public interface Mapper<P extends @Nullable Object, R extends @Nullable Object> {
                R apply(P value);
              }
              static <T extends @Nullable Object> T id(T t) {
                return t;
              }
              static void acceptsNested(Box<Box<@Nullable String>> box) {}
              static void nestedCall(Box<Box<Box<@Nullable String>>> nested) {
                // flatMap is a nested call in the inference problem for id; the type of its lambda
                // argument must also reflect R = Box<@Nullable String>
                acceptsNested(id(nested.flatMap(box -> box)));
              }
              static void nestedCallBlockBody(Box<Box<Box<@Nullable String>>> nested) {
                acceptsNested(id(nested.flatMap(box -> { return box; })));
              }
              static void nestedCallNonNull(Box<Box<Box<String>>> nested) {
                // BUG: Diagnostic contains: incompatible types: Box<Box<String>> cannot be converted to Box<Box<@Nullable String>>
                acceptsNested(id(nested.flatMap(box -> box)));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredNestedNullnessIsPerCall() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Test {
              static class Box<T extends @Nullable Object> {}
              static <T extends @Nullable Object> T id(T t) {
                return t;
              }
              static void acceptsNullableBox(Box<@Nullable String> box) {}
              static void acceptsNonNullBox(Box<String> box) {}
              static void test(Box<@Nullable String> nullableBox, Box<String> nonNullBox) {
                // each call to id gets its own type argument, including nested nullability
                acceptsNullableBox(id(nullableBox));
                acceptsNonNullBox(id(nonNullBox));
                var a = id(nullableBox);
                var b = id(nonNullBox);
                acceptsNullableBox(a);
                acceptsNonNullBox(b);
                // BUG: Diagnostic contains: incompatible types: Box<String> cannot be converted to Box<@Nullable String>
                acceptsNullableBox(b);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredEnclosingTypeNullnessSurvivesInnerClassIdentity() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Outer<E extends @Nullable Object> {
                final E value;
                Outer(E value) { this.value = value; }
                class Inner {
                  E get() { return value; }
                }
              }
              static <T extends @Nullable Object> Outer<T>.Inner identity(Outer<T>.Inner value) {
                return value;
              }
              static void test(
                  Outer<@Nullable String>.Inner nullableInner, Outer<String>.Inner nonNullInner) {
                // BUG: Diagnostic contains: dereferenced expression
                identity(nullableInner).get().length();
                identity(nonNullInner).get().length();
                var extracted = identity(nullableInner).get();
                // BUG: Diagnostic contains: dereferenced expression
                extracted.length();
                var nonNullExtracted = identity(nonNullInner).get();
                nonNullExtracted.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void fullTypeInferenceRespectsNestedDeclarationBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static class Bad extends Box<@Nullable String> {}
              static class Good extends Box<String> {}
              static <T extends Box<String>> T boundedId(T value) {
                return value;
              }
              static <S extends Box<@Nullable String>> void badTypeVariable(S value) {
                // BUG: Diagnostic contains: incompatible types
                boundedId(value);
              }
              static <S extends Box<String>> void goodTypeVariable(S value) {
                boundedId(value);
              }
              static void test(Box<@Nullable String> nullableBox, Bad bad, Good good) {
                // BUG: Diagnostic contains: incompatible types
                boundedId(nullableBox);
                // BUG: Diagnostic contains: incompatible types
                boundedId(bad);
                boundedId(good);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void fullTypeInferenceMergesCovariantArrayBoundsInEitherOrder() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T pick(T first, T second) {
                return first;
              }
              static void acceptsNonNullElements(String[] value) {}
              static void acceptsNullableElements(@Nullable String[] value) {}
              static void test(String[] nonNullElements, @Nullable String[] nullableElements) {
                // BUG: Diagnostic contains: incompatible types
                acceptsNonNullElements(pick(nonNullElements, nullableElements));
                // BUG: Diagnostic contains: incompatible types
                acceptsNonNullElements(pick(nullableElements, nonNullElements));
                acceptsNullableElements(pick(nonNullElements, nullableElements));
                acceptsNullableElements(pick(nullableElements, nonNullElements));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredArrayElementDereferenceInEitherOrder() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T pick(T first, T second) {
                return first;
              }
              static void direct(String[] nonNullElements, @Nullable String[] nullableElements) {
                // BUG: Diagnostic contains: dereferenced expression 'pick(nonNullElements, nullableElements)[0]' is @Nullable
                pick(nonNullElements, nullableElements)[0].length();
                // BUG: Diagnostic contains: dereferenced expression 'pick(nullableElements, nonNullElements)[0]' is @Nullable
                pick(nullableElements, nonNullElements)[0].length();
                pick(nonNullElements, nonNullElements)[0].length();
              }
              static void inferredLocal(String[] nonNullElements, @Nullable String[] nullableElements) {
                var forward = pick(nonNullElements, nullableElements);
                var reverse = pick(nullableElements, nonNullElements);
                var nonNull = pick(nonNullElements, nonNullElements);
                // BUG: Diagnostic contains: dereferenced expression 'forward[0]' is @Nullable
                forward[0].length();
                // BUG: Diagnostic contains: dereferenced expression 'reverse[0]' is @Nullable
                reverse[0].length();
                nonNull[0].length();
              }
              static void explicit(@Nullable String[] elements) {
                // BUG: Diagnostic contains: dereferenced expression 'elements[0]' is @Nullable
                elements[0].length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredArrayElementFlowRefinement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T pick(T first, T second) {
                return first;
              }
              static void test(String[] nonNullElements, @Nullable String[] nullableElements) {
                var forward = pick(nonNullElements, nullableElements);
                var reverse = pick(nullableElements, nonNullElements);
                if (forward[0] != null) {
                  forward[0].length();
                }
                if (reverse[0] == null) {
                  return;
                }
                reverse[0].length();
                // BUG: Diagnostic contains: dereferenced expression 'forward[1]' is @Nullable
                forward[1].length();
                forward[2] = "safe";
                forward[2].length();
                forward[2] = null;
                // BUG: Diagnostic contains: dereferenced expression 'forward[2]' is @Nullable
                forward[2].length();
              }
              static void explicit(@Nullable String[] elements) {
                if (elements[0] != null) {
                  elements[0].length();
                }
              }
              static void indexed(
                  String[] nonNullElements, @Nullable String[] nullableElements, int index) {
                var elements = pick(nullableElements, nonNullElements);
                if (elements[index] != null) {
                  elements[index].length();
                  // BUG: Diagnostic contains: dereferenced expression 'elements[index + 1]' is @Nullable
                  elements[index + 1].length();
                }
              }
              static void extracted(String[] nonNullElements, @Nullable String[] nullableElements) {
                var elements = pick(nonNullElements, nullableElements);
                var element = elements[0];
                // BUG: Diagnostic contains: dereferenced expression 'element' is @Nullable
                element.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayElementFieldIndicesHaveDistinctReceivers() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Index {
                int i;
              }
              Index left = new Index();
              Index right = new Index();
              void test(@Nullable String[] a) {
                a[left.i] = "safe";
                // BUG: Diagnostic contains: dereferenced expression 'a[right.i]' is @Nullable
                a[right.i].length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayElementRequireNonNullRefinesAccess() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Objects;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void test(@Nullable String[] a) {
                Objects.requireNonNull(a[0]);
                a[0].length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayElementNonNullGuardRefinesAccess() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Objects;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void test(@Nullable String[] a) {
                if (Objects.nonNull(a[0])) {
                  a[0].length();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayElementGuardSurvivesUnrelatedStatements() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void test(@Nullable String[] a) {
                if (a[0] != null) {
                  int unrelated = 1;
                  new Object();
                  a[0].length();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayElementGuardSurvivesDifferentElementWrite() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void test(@Nullable String[] a) {
                if (a[0] != null) {
                  a[1] = "safe";
                  a[0].length();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayElementAliasWriteInvalidatesRefinement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T pick(T first, T second) {
                return first;
              }
              static void explicit(@Nullable String[] a) {
                if (a[0] != null) {
                  var b = a;
                  b[0] = null;
                  // BUG: Diagnostic contains: dereferenced expression 'a[0]' is @Nullable
                  a[0].length();
                }
              }
              static void inferred(String[] nonNullElements, @Nullable String[] nullableElements) {
                var a = pick(nonNullElements, nullableElements);
                if (a[0] != null) {
                  var b = a;
                  b[0] = null;
                  // BUG: Diagnostic contains: dereferenced expression 'a[0]' is @Nullable
                  a[0].length();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayElementIndexRebindingInvalidatesRefinement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void test(@Nullable String[] a) {
                int index = 0;
                a[index] = "safe";
                a[index].length();
                index = 1;
                // BUG: Diagnostic contains: dereferenced expression 'a[index]' is @Nullable
                a[index].length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayRebindingInvalidatesElementRefinement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void test(@Nullable String[] a, @Nullable String[] nullableElements) {
                a[0] = "safe";
                a[0].length();
                a = nullableElements;
                // BUG: Diagnostic contains: dereferenced expression 'a[0]' is @Nullable
                a[0].length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredArrayAccessAsGenericArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T pick(T first, T second) {
                return first;
              }
              static <T extends @Nullable Object> T id(T value) {
                return value;
              }
              static void test(String[] nonNullElements, @Nullable String[] nullableElements) {
                // BUG: Diagnostic contains: dereferenced expression 'id(pick(nonNullElements, nullableElements)[0])' is @Nullable
                id(pick(nonNullElements, nullableElements)[0]).length();
                // BUG: Diagnostic contains: dereferenced expression 'id(pick(nullableElements, nonNullElements)[0])' is @Nullable
                id(pick(nullableElements, nonNullElements)[0]).length();
                id(pick(nonNullElements, nonNullElements)[0]).length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredMultidimensionalArrayElementNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T pick(T first, T second) {
                return first;
              }
              static void acceptsNonNullElements(String[] elements) {}
              static void acceptsNullableElements(@Nullable String[] elements) {}
              static void leaves(String[][] nonNull, @Nullable String[][] nullableLeaves) {
                int length = pick(nonNull, nullableLeaves)[0].length;
                // BUG: Diagnostic contains: dereferenced expression 'pick(nonNull, nullableLeaves)[1][0]' is @Nullable
                pick(nonNull, nullableLeaves)[1][0].length();
                // BUG: Diagnostic contains: dereferenced expression 'pick(nullableLeaves, nonNull)[0][0]' is @Nullable
                pick(nullableLeaves, nonNull)[0][0].length();
                var elements = pick(nonNull, nullableLeaves);
                var row = elements[0];
                // BUG: Diagnostic contains: dereferenced expression 'row[0]' is @Nullable
                row[0].length();
                // BUG: Diagnostic contains: incompatible types
                acceptsNonNullElements(pick(nonNull, nullableLeaves)[0]);
                acceptsNullableElements(pick(nonNull, nullableLeaves)[0]);
                pick(nonNull, nonNull)[0][0].length();
              }
              static void rows(String[][] nonNull, String[] @Nullable [] nullableRows) {
                // BUG: Diagnostic contains: dereferenced expression 'pick(nonNull, nullableRows)[0]' is @Nullable
                int length = pick(nonNull, nullableRows)[0].length;
                var elements = pick(nullableRows, nonNull);
                // BUG: Diagnostic contains: dereferenced expression 'elements[0]' is @Nullable
                int otherLength = elements[0].length;
                if (elements[1] != null) {
                  elements[1][0].length();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredArrayElementsInEnhancedFor() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T pick(T first, T second) {
                return first;
              }
              static void test(String[] nonNullElements, @Nullable String[] nullableElements) {
                for (var element : pick(nonNullElements, nullableElements)) {
                  // BUG: Diagnostic contains: dereferenced expression 'element' is @Nullable
                  element.length();
                }
                var elements = pick(nullableElements, nonNullElements);
                for (var element : elements) {
                  // BUG: Diagnostic contains: dereferenced expression 'element' is @Nullable
                  element.length();
                }
                for (var element : pick(nonNullElements, nonNullElements)) {
                  element.length();
                }
                for (var element : nullableElements) {
                  if (element != null) {
                    element.length();
                  }
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredArrayElementsUnboxedInDirectEnhancedFor() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T pick(T first, T second) {
                return first;
              }
              static void test(Integer[] nonNullElements, @Nullable Integer[] nullableElements) {
                // BUG: Diagnostic contains: unboxing of a @Nullable value
                for (int element : pick(nonNullElements, nullableElements)) {}
                // BUG: Diagnostic contains: unboxing of a @Nullable value
                for (int element : pick(nullableElements, nonNullElements)) {}
                for (int element : pick(nonNullElements, nonNullElements)) {}
                // BUG: Diagnostic contains: unboxing of a @Nullable value
                for (int element : nullableElements) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredArrayElementsUnboxedInVarEnhancedFor() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T pick(T first, T second) {
                return first;
              }
              static void test(Integer[] nonNullElements, @Nullable Integer[] nullableElements) {
                var forward = pick(nonNullElements, nullableElements);
                var reverse = pick(nullableElements, nonNullElements);
                var nonNull = pick(nonNullElements, nonNullElements);
                // BUG: Diagnostic contains: unboxing of a @Nullable value
                for (int element : forward) {}
                // BUG: Diagnostic contains: unboxing of a @Nullable value
                for (int element : reverse) {}
                for (int element : nonNull) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredArrayRecoveryDoesNotStartNestedLambdaAnalysis() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T supplied(Supplier<T> source) {
                return source.get();
              }
              static void test(String[] nonNullElements, @Nullable String[] nullableElements) {
                // Recovering this array during transfer visits a lambda inside another lambda
                // whose analysis is not running. Environment setup must not start that analysis.
                // BUG: Diagnostic contains: dereferenced expression
                supplied(() -> supplied(() -> nullableElements))[0].length();
                supplied(() -> supplied(() -> nonNullElements))[0].length();
                var elements = supplied(() -> supplied(() -> nullableElements));
                // BUG: Diagnostic contains: dereferenced expression 'elements[0]' is @Nullable
                elements[0].length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredArrayRecoveryPreservesLambdaFlowRefinement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object> T supplied(Supplier<T> source) {
                return source.get();
              }
              static <T extends @Nullable Object> T id(T value) {
                return value;
              }
              static void test(@Nullable String[] elements) {
                supplied(() -> {
                  if (elements[0] == null) {
                    return "fallback";
                  }
                  return elements[0];
                }).length();
                supplied(() -> {
                  var element = elements[0];
                  if (element == null) {
                    return "fallback";
                  }
                  return id(element);
                }).length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayElementFieldIndexGuardsHaveDistinctReceivers() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Objects;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Index {
                int i;
              }
              Index left = new Index();
              Index right = new Index();
              void test(@Nullable String[] a) {
                if (a[left.i] != null) {
                  // BUG: Diagnostic contains: dereferenced expression 'a[right.i]' is @Nullable
                  a[right.i].length();
                }
                if (Objects.nonNull(a[left.i])) {
                  // BUG: Diagnostic contains: dereferenced expression 'a[right.i]' is @Nullable
                  a[right.i].length();
                }
                Objects.requireNonNull(a[left.i]);
                // BUG: Diagnostic contains: dereferenced expression 'a[right.i]' is @Nullable
                a[right.i].length();
                a[left.i] = "safe";
                // Receiver-dependent indices cannot justify even same-receiver refinement.
                // BUG: Diagnostic contains: dereferenced expression 'a[left.i]' is @Nullable
                a[left.i].length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayElementFieldIndexRebindingInvalidatesRefinement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Index {
                int i;
              }
              Index left = new Index();
              Index right = new Index();
              static int index;
              void receiverRebinding(@Nullable String[] a) {
                if (a[left.i] != null) {
                  left = right;
                  // BUG: Diagnostic contains: dereferenced expression 'a[left.i]' is @Nullable
                  a[left.i].length();
                }
              }
              static void staticIndexRebinding(@Nullable String[] a) {
                if (a[index] != null) {
                  a[index].length();
                  index = 1;
                  // BUG: Diagnostic contains: dereferenced expression 'a[index]' is @Nullable
                  a[index].length();
                }
                a[index] = "safe";
                a[index].length();
                index = 2;
                // BUG: Diagnostic contains: dereferenced expression 'a[index]' is @Nullable
                a[index].length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nullableArrayFieldRebindingInvalidatesElementRefinement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Holder {
                @Nullable String[] elements = new String[2];
              }
              static void test(Holder holder, @Nullable String[] replacement) {
                if (holder.elements[0] != null) {
                  holder.elements[0].length();
                  holder.elements = replacement;
                  // BUG: Diagnostic contains: dereferenced expression 'holder.elements[0]' is @Nullable
                  holder.elements[0].length();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void arrayBackedMapKeysTrackWritesAndRebindings() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static void elementWrite(Map<String, @Nullable String> map, String[] keys) {
                if (map.get(keys[0]) != null) {
                  map.get(keys[0]).length();
                  keys[0] = "other";
                  // BUG: Diagnostic contains: dereferenced expression
                  map.get(keys[0]).length();
                }
              }
              static void indexRebinding(Map<String, @Nullable String> map, String[] keys) {
                int index = 0;
                if (map.get(keys[index]) != null) {
                  map.get(keys[index]).length();
                  index = 1;
                  // BUG: Diagnostic contains: dereferenced expression
                  map.get(keys[index]).length();
                }
              }
              static void arrayRebinding(
                  Map<String, @Nullable String> map, String[] keys, String[] replacement) {
                if (map.get(keys[0]) != null) {
                  keys = replacement;
                  // BUG: Diagnostic contains: dereferenced expression
                  map.get(keys[0]).length();
                }
              }
              static void distinctKnownIndexPreservesKey(
                  Map<String, @Nullable String> map, String[] keys) {
                if (map.get(keys[0]) != null) {
                  keys[1] = "other";
                  map.get(keys[0]).length();
                }
              }
              static void constantKeyPreservesRefinement(
                  Map<String, @Nullable String> map, String[] keys) {
                if (map.get("constant") != null) {
                  keys[0] = "other";
                  map.get("constant").length();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void arrayAssignmentRefinementRequiresStableOperands() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static int cursor;
              static String moveCursor() { cursor = 1; return "safe"; }
              static void sideEffectfulRhs(@Nullable String[] values) {
                cursor = 0;
                values[cursor] = moveCursor();
                // BUG: Diagnostic contains: dereferenced expression
                values[cursor].length();
              }
              static void incrementingIndex(@Nullable String[] values, int index) {
                values[index++] = "safe";
                // BUG: Diagnostic contains: dereferenced expression
                values[index].length();
              }
              static void stableCast(@Nullable String[] values) {
                values[0] = (String) "safe";
                values[0].length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferredArrayElementsRemainNonNullInLegacyMode() {
    makeTestHelperWithArgs(Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              static <T extends @Nullable Object> T pick(T first, T second) {
                return first;
              }
              static void test(String[] nonNullElements, @Nullable String[] nullableElements) {
                pick(nonNullElements, nullableElements)[0].length();
                pick(nullableElements, nonNullElements)[0].length();
                var elements = pick(nonNullElements, nullableElements);
                elements[0].length();
                nullableElements[0].length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void receiverInstantiatedMethodVariableBounds() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static class Parent<X extends @Nullable Object> {
                <T extends X> T id(T value) { return value; }
                <T extends Box<X>> T boxId(T value) { return value; }
              }
              static class Child extends Parent<String> {}
              static void test(Child child, Box<@Nullable String> nullableBox) {
                child.id("value");
                // BUG: Diagnostic contains: inference failure: type variable T is constrained to be @Nullable, but its upper bound requires it to be @NonNull
                child.id(null);
                // BUG: Diagnostic contains: incompatible types
                child.boxId(nullableBox);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void dependentDeclarationEdgePropagatesNullnessWithoutStructuredLowers() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static <T extends @Nullable Object, U extends T> T widen(U value) {
                return value;
              }
              static void test() {
                // A null literal constrains U's root without adding structured lower evidence.
                var nullable = widen(null);
                // BUG: Diagnostic contains: dereferenced expression
                nullable.toString();
                var nonNull = widen("value");
                nonNull.toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void nestedDeclarationBoundsAcquireLowerEvidenceOnLaterPreparationPasses() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              // T and U are visited before V, which initially owns all the lower evidence.
              static <T extends @Nullable Object, U extends Box<T>, V extends Box<U>> T extract(
                  V value) {
                throw new RuntimeException();
              }
              static void test(Box<Box<@Nullable String>> nullable, Box<Box<String>> nonNull) {
                var nullableResult = extract(nullable);
                // BUG: Diagnostic contains: dereferenced expression
                nullableResult.length();
                var nonNullResult = extract(nonNull);
                nonNullResult.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inheritedStructuredEvidenceReachesFixedPoint() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static class Base<T extends @Nullable Object> {}
              static class Nested extends Base<Box<@Nullable String>> {}
              static class GenericNested<S extends @Nullable Object> extends Base<@Nullable S> {}
              static <U extends @Nullable Object, T extends Base<U>> void consume(T value) {}
              static void test(Nested nested) {
                consume(nested);
              }
              static <S extends @Nullable Object> void genericTest(GenericNested<S> nested) {
                consume(nested);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardDeclarationBoundUsesContainmentFallback() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static <T extends Box<? super String>> T id(T value) { return value; }
              static void test(Box<@Nullable Object> box) {
                id(box);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unmarkedDeclarationBoundIsNotNullnessEvidence() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.jspecify.annotations.NullUnmarked;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              @NullUnmarked
              static <T extends Box<@Nullable String>> @NonNull T id(T value) {
                return value;
              }
              @NullUnmarked
              static class C<T extends Box<@Nullable String>> {
                @NullMarked
                C(T value) {}
              }
              static void takes(Box<String> value) {}
              static void test(Box<String> value) {
                var inferred = id(value);
                takes(inferred);
                new C<>(value);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unmarkedBoundOnlyVariablesPreserveReceiverInstantiations() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import org.jspecify.annotations.NullUnmarked;
            @NullMarked
            class Test {
              @NullUnmarked
              static class Receiver<E> {
                <T extends List<E>, U extends T, R extends Comparable<R>, D extends List<R>>
                    @NonNull U probe(@NonNull U value) { return value; }
              }
              static void acceptsNonNull(List<String> values) {}
              static void acceptsNullable(List<@Nullable String> values) {}
              static void test(Receiver<@Nullable String> nullableReceiver,
                  Receiver<String> nonNullReceiver, List<@Nullable String> nullableValues,
                  List<String> nonNullValues) {
                var nullable = nullableReceiver.probe(nullableValues);
                acceptsNullable(nullable);
                // BUG: Diagnostic contains: incompatible types
                acceptsNonNull(nullable);
                // BUG: Diagnostic contains: dereferenced expression
                nullable.get(0).length();
                var nonNull = nonNullReceiver.probe(nonNullValues);
                acceptsNonNull(nonNull);
                nonNull.get(0).length();
                var again = nullableReceiver.probe(nullableValues);
                acceptsNullable(again);
                // BUG: Diagnostic contains: dereferenced expression
                again.get(0).length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void arrayDeclarationBoundPreservesNestedNullnessThroughSubtyping() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Pair<A extends @Nullable Object, B extends @Nullable Object>
                  extends Box<B> {}
              static class Bad extends Box<@Nullable String> {}
              static class Good extends Box<String> {}
              static class Parent<X extends @Nullable Object> {
                <T extends X> T id(T value) { return value; }
              }
              static void test(
                  Parent<Box<String>[]> parent,
                  Pair<Integer, @Nullable String>[] nullablePairs,
                  Pair<Integer, String>[] nonNullPairs,
                  Bad[] bad,
                  Good[] good) {
                // BUG: Diagnostic contains: incompatible types
                parent.id(nullablePairs);
                parent.id(nonNullPairs);
                // BUG: Diagnostic contains: incompatible types
                parent.id(bad);
                parent.id(good);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void recursiveDeclarationBoundPreservesNestedNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Node<A extends @Nullable Object, B extends @Nullable Object> {}
              static class Box<E extends @Nullable Object> {}
              static class Bad extends Node<Bad, Box<@Nullable String>> {}
              static class Good extends Node<Good, Box<String>> {}
              static <T extends Node<T, Box<String>>> void require(T value) {}
              static void test(Bad bad, Good good) {
                // BUG: Diagnostic contains: incompatible types
                require(bad);
                require(good);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void structuredMethodReferenceChecksNestedNullnessAndNullableReturn() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              interface Fn<I extends @Nullable Object, O extends @Nullable Object> {
                O apply(I value);
              }
              static <T extends @Nullable Object> T id(T value) { return value; }
              static <T extends @Nullable Object> @Nullable T nullableId(T value) {
                return null;
              }
              static <I extends @Nullable Object> void use(I value, Fn<I, Box<String>> fn) {}
              static <I extends @Nullable Object> void useNullableReturn(
                  I value, Fn<I, @Nullable Box<String>> fn) {}
              static void test(Box<@Nullable String> nullableBox, Box<String> nonNullBox) {
                // BUG: Diagnostic contains: mismatched type parameter nullability
                use(nullableBox, Test::id);
                use(nonNullBox, Test::id);
                useNullableReturn(nonNullBox, Test::id);
                useNullableReturn(nonNullBox, Test::nullableId);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardDeclarationBoundPreservesNestedNonNullRequirement() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static class Bad extends Box<Box<@Nullable String>> {}
              static class Good extends Box<Box<String>> {}
              static <T extends Box<? extends Box<String>>> void require(T value) {}
              static void test(Bad bad, Good good) {
                // BUG: Diagnostic contains: incompatible types
                require(bad);
                require(good);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void upperOnlyFactoryDeclarationBoundConflictsWithNullableNestedTarget() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static <T extends Box<String>> T make() { throw new AssertionError(); }
              // BUG: Diagnostic contains: incompatible types
              Box<@Nullable String> field = make();
              Box<String> nonNullField = make();
            }
            """)
        .doTest();
  }

  @Test
  public void annotatedOccurrencePreservesNestedDeclarationBound() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static class Bad extends Box<@Nullable String> {}
              static class Good extends Box<String> {}
              static <U extends @Nullable Object> U id(U value) { return value; }
              static <T extends Box<String>> void requireNullable(@Nullable T value) {}
              static <T extends Box<String>> void requireNonNull(@NonNull T value) {}
              static void test(Bad bad, Good good) {
                // BUG: Diagnostic contains: incompatible types
                requireNullable(bad);
                // BUG: Diagnostic contains: incompatible types
                requireNonNull(bad);
                requireNullable(good);
                requireNonNull(good);
                requireNullable(null);
                // BUG: Diagnostic contains: incompatible types
                requireNullable(id(bad));
                // BUG: Diagnostic contains: incompatible types
                requireNonNull(id(bad));
                requireNullable(id(good));
                requireNonNull(id(good));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void referenceSiblingViolationsSurviveEnclosingFailure() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<T extends @Nullable Object> {}
              static class Bad extends Box<@Nullable String> {}
              static class Good extends Box<String> {}
              interface Fn<I extends @Nullable Object, O extends @Nullable Object> {
                O apply(I value);
              }
              static <T extends Box<String>> T boundedId(T value) { return value; }
              static <A extends @Nullable Object> void pair(A value, Fn<Bad, Bad> fn) {}
              static <A extends @Nullable Object> void reversedPair(Fn<Bad, Bad> fn, A value) {}
              static void refs(Fn<Bad, Bad> first, Fn<Bad, Bad> second) {}
              static <A extends @Nullable Object> void goodPair(A value, Fn<Good, Good> fn) {}
              static <A extends @Nullable Object> void goodReversedPair(Fn<Good, Good> fn, A value) {}
              static void goodRefs(Fn<Good, Good> first, Fn<Good, Good> second) {}
              static void test(Bad bad, Good good) {
                pair(
                    // BUG: Diagnostic contains: incompatible types
                    boundedId(bad),
                    // BUG: Diagnostic contains: incompatible types
                    Test::boundedId);
                reversedPair(
                    // BUG: Diagnostic contains: incompatible types
                    Test::boundedId,
                    // BUG: Diagnostic contains: incompatible types
                    boundedId(bad));
                refs(
                    // BUG: Diagnostic contains: incompatible types
                    Test::boundedId,
                    // BUG: Diagnostic contains: incompatible types
                    Test::boundedId);
                goodPair(boundedId(good), Test::boundedId);
                goodReversedPair(Test::boundedId, boundedId(good));
                goodRefs(Test::boundedId, Test::boundedId);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unusedTypeVariablesDoNotEraseCertifiedLambdaTargets() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.function.Supplier;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
              static class Box<E extends @Nullable Object> {}
              static <U, R extends @Nullable Object> R plain(Supplier<R> factory) {
                return factory.get();
              }
              static <U extends Comparable<U>, R extends @Nullable Object> R recursive(
                  Supplier<R> factory) {
                return factory.get();
              }
              static void acceptsNullable(Box<@Nullable String> value) {}
              static void acceptsNonNull(Box<String> value) {}
              static void test(Box<@Nullable String> box) {
                var first = plain(() -> box);
                var second = recursive(() -> box);
                acceptsNullable(first);
                acceptsNullable(second);
                // BUG: Diagnostic contains: incompatible types
                acceptsNonNull(first);
                // BUG: Diagnostic contains: incompatible types
                acceptsNonNull(second);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void inferenceFromEnclosingTypeArguments() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            import java.util.function.Consumer;
            @NullMarked
            public class Test {
              static class Outer<X extends @Nullable Object> {
                class Inner {
                  class Deepest {}
                }
                class SubInner extends Inner {}
              }
              static <T extends @Nullable Object> void takeInner(Outer<T>.Inner inner) {}
              static <T> void takeNonNullInner(Outer<T>.Inner inner) {}
              static <T extends @Nullable Object> T valueOf(Outer<T>.Inner inner) {
                throw new UnsupportedOperationException();
              }
              static <T extends @Nullable Object> T deepestValueOf(Outer<T>.Inner.Deepest deepest) {
                throw new UnsupportedOperationException();
              }
              static <T extends @Nullable Object> void takeConsumer(Consumer<Outer<T>.Inner> consumer) {}
              static <T> void takeNonNullConsumer(Consumer<Outer<T>.Inner> consumer) {}
              static void refNullable(Outer<@Nullable String>.Inner inner) {}
              static void refNonNull(Outer<String>.Inner inner) {}
              void argument(Outer<@Nullable String>.Inner nullableInner, Outer<String>.Inner nonNullInner) {
                takeInner(nullableInner);
                takeInner(nonNullInner);
                // BUG: Diagnostic contains: type variable T is constrained to be @Nullable, but its upper bound requires it to be @NonNull
                takeNonNullInner(nullableInner);
                takeNonNullInner(nonNullInner);
              }
              void inferredReturn(Outer<@Nullable String>.Inner nullableInner, Outer<String>.Inner nonNullInner) {
                // BUG: Diagnostic contains: dereferenced expression 'valueOf(nullableInner)' is @Nullable
                valueOf(nullableInner).hashCode();
                valueOf(nonNullInner).hashCode();
              }
              void subclass(Outer<@Nullable String>.SubInner nullableSub, Outer<String>.SubInner nonNullSub) {
                // BUG: Diagnostic contains: dereferenced expression 'valueOf(nullableSub)' is @Nullable
                valueOf(nullableSub).hashCode();
                valueOf(nonNullSub).hashCode();
              }
              void twoLevels(
                  Outer<@Nullable String>.Inner.Deepest nullableDeepest, Outer<String>.Inner.Deepest nonNullDeepest) {
                // BUG: Diagnostic contains: dereferenced expression 'deepestValueOf(nullableDeepest)' is @Nullable
                deepestValueOf(nullableDeepest).hashCode();
                deepestValueOf(nonNullDeepest).hashCode();
              }
              void methodReference() {
                takeConsumer(Test::refNullable);
                takeConsumer(Test::refNonNull);
                // BUG: Diagnostic contains: type variable T is constrained to be @Nullable, but its upper bound requires it to be @NonNull
                takeNonNullConsumer(Test::refNullable);
                takeNonNullConsumer(Test::refNonNull);
              }
              void anonymousSubclass() {
                // False negative: javac erases the enclosing type in the supertype of an anonymous
                // class, so the @Nullable type argument of the outer instance does not reach T
                valueOf(new Outer<@Nullable String>().new Inner() {}).hashCode();
                valueOf(new Outer<String>().new Inner() {}).hashCode();
              }
              void explicitlyTypedLambda() {
                takeConsumer((Outer<@Nullable String>.Inner inner) -> {});
                takeConsumer((Outer<String>.Inner inner) -> {});
                // BUG: Diagnostic contains: type variable T is constrained to be @Nullable, but its upper bound requires it to be @NonNull
                takeNonNullConsumer((Outer<@Nullable String>.Inner inner) -> {});
                takeNonNullConsumer((Outer<String>.Inner inner) -> {});
              }
            }
            """)
        .doTest();
  }

  @Test
  public void anonymousInnerClassOfGenericOuterAsArgument() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            public class Test<K, V> {
              private abstract class Task<T> {
                abstract @Nullable T execute();
              }
              private <T> @Nullable T doTask(Task<T> task) {
                return task.execute();
              }
              @Nullable V get() {
                return doTask(new Task<V>() {
                  @Override
                  @Nullable V execute() {
                    return null;
                  }
                });
              }
            }
            """)
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(
            Arrays.asList("-XepOpt:NullAway:AnnotatedPackages=com.uber")));
  }
}
