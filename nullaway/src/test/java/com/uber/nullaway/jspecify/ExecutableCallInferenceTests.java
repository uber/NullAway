package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import org.junit.Test;

/** Equivalent method and constructor calls preserve executable-owned inference constraints. */
public class ExecutableCallInferenceTests extends NullAwayTestsBase {

  @Test
  public void genericParametersPreserveNestedArgumentAndResultAnnotations() {
    check(
        """
          static class Box<E extends @Nullable Object> {
            Box(E value) {}
          }
          static <E extends @Nullable Object> Box<E> box(E value) { return new Box<>(value); }
          static <U> void consume(List<U> values) {}
          static class Consumer {
            <U> Consumer(List<U> values) {}
          }
          void test(List<@Nullable String> nullable, List<String> nonNull) {
            var method = box(nullable);
            var constructor = new Box<>(nullable);
            Box<List<@Nullable String>> methodResult = method;
            Box<List<@Nullable String>> constructorResult = constructor;
            // BUG: Diagnostic contains: incompatible types
            Box<List<String>> badMethodResult = method;
            // BUG: Diagnostic contains: incompatible types
            Box<List<String>> badConstructorResult = constructor;
            consume(nonNull);
            new Consumer(nonNull);
            // BUG: Diagnostic contains: cannot be converted
            consume(nullable);
            // BUG: Diagnostic contains: cannot be converted
            new Consumer(nullable);
          }
        """);
  }

  @Test
  public void fixedAndDiamondClassArgumentsStaySeparateFromExecutableVariables() {
    check(
        """
          static class Box<E extends @Nullable Object> {
            <U> Box(List<U> values) {}
          }
          static <E extends @Nullable Object, U> Box<E> box(List<U> values) {
            return new Box<E>(values);
          }
          static <X extends @Nullable Object> X id(X value) { return value; }
          void test(List<String> values, List<@Nullable String> nullable) {
            Box<@Nullable String> method = box(values);
            Box<@Nullable String> diamond = new Box<>(values);
            Box<@Nullable String> fixed = id(new Box<@Nullable String>(values));
            Box<@Nullable String> witnessed = id(new <String>Box<@Nullable String>(values));
            Box<String> nonNullMethod = box(values);
            Box<String> nonNullDiamond = new Box<>(values);
            // BUG: Diagnostic contains: incompatible types
            Box<String> wrongFixed = id(new Box<@Nullable String>(values));
            // BUG: Diagnostic contains: incompatible types
            Box<@Nullable String> wrongReverse = id(new Box<String>(values));
            // BUG: Diagnostic contains: cannot be converted
            Box<@Nullable String> badMethod = box(nullable);
            // BUG: Diagnostic contains: cannot be converted
            Box<@Nullable String> badConstructor = new Box<@Nullable String>(nullable);
          }
        """);
  }

  @Test
  public void explicitWitnessesRespectNullableAndNonNullExecutableBounds() {
    check(
        """
          static <U extends @Nullable Object> void nullable(U value) {}
          static <U> void nonNull(U value) {}
          static class NullableBound {
            <U extends @Nullable Object> NullableBound(U value) {}
          }
          static class NonNullBound {
            <U> NonNullBound(U value) {}
          }
          void test(List<@Nullable String> values, @Nullable String value) {
            Test.<@Nullable String>nullable(value);
            new <@Nullable String>NullableBound(value);
            Test.<List<@Nullable String>>nonNull(values);
            new <List<@Nullable String>>NonNullBound(values);
            // BUG: Diagnostic contains: Type argument cannot be @Nullable
            Test.<@Nullable String>nonNull(value);
            // BUG: Diagnostic contains: Type argument cannot be @Nullable
            new <@Nullable String>NonNullBound(value);
            Test.<String>nullable("x");
            new <String>NullableBound("x");
            Test.<@Nullable String>nullable(value);
            new <@Nullable String>NullableBound(value);
          }
        """);
  }

  @Test
  public void qualifiedReceiversAndConstructorsKeepAnnotatedEnclosingBounds() {
    check(
        """
          static class Outer<E extends @Nullable Object> {
            <U extends E> U identity(U value) { return value; }
            class Inner<F extends @Nullable Object> {
              <U extends E> Inner(U value, F other) {}
            }
          }
          static <X extends @Nullable Object> X id(X value) { return value; }
          void test(Outer<@Nullable String> nullable, Outer<String> nonNull,
              @Nullable String value) {
            nullable.identity(value);
            // BUG: Diagnostic contains: dereferenced expression
            nullable.identity(value).length();
            nonNull.identity("x").length();
            Outer<@Nullable String>.Inner<String> fixed =
                id(nullable.new Inner<String>(value, "x"));
            Outer<@Nullable String>.Inner<@Nullable String> diamond =
                nullable.new Inner<>(value, value);
            String result = nonNull.identity("x");
            Outer<String>.Inner<String> nonNullResult = nonNull.new Inner<>("x", "x");
            // BUG: Diagnostic contains: inference failure
            nonNull.identity(value);
            // BUG: Diagnostic contains: inference failure
            nonNull.new Inner<String>(value, "x");
            // BUG: Diagnostic contains: incompatible types
            Outer<String>.Inner<String> wrongEnclosing =
                id(nullable.new Inner<String>("x", "x"));
            nullable.identity(value);
            nullable.new Inner<String>(value, "x");
          }
        """);
  }

  @Test
  public void siblingNestedAndLambdaCallsKeepIndependentOwners() {
    check(
        """
          static class Box<E extends @Nullable Object> {
            Box(E value) {}
          }
          interface Factory<R> { R get(); }
          static <E extends @Nullable Object> Box<E> box(E value) { return new Box<>(value); }
          static <X extends @Nullable Object> X id(X value) { return value; }
          static <E extends @Nullable Object> void siblings(Box<E> left, Box<E> right) {}
          static <R> R supply(Factory<R> factory) { return factory.get(); }
          void test(List<@Nullable String> nullable, List<String> nonNull) {
            siblings(box(nullable), new Box<>(nullable));
            siblings(new Box<>(nonNull), box(nonNull));
            Box<List<@Nullable String>> nested = id(box(id(nullable)));
            Box<List<String>> nestedConstructor = id(new Box<>(id(nonNull)));
            Box<List<@Nullable String>> lambda = supply(() -> {
              var local = id(new Box<>(nullable));
              return id(local);
            });
            Box<List<String>> methodLambda = supply(() -> {
              var local = id(box(nonNull));
              return local;
            });
            supply(() -> {
              var method = box(nullable);
              var constructor = new Box<>(nullable);
              // BUG: Diagnostic contains: incompatible types
              Box<List<String>> badMethod = method;
              // BUG: Diagnostic contains: incompatible types
              Box<List<String>> badConstructor = constructor;
              return constructor;
            });
            Box<List<String>> after = id(new Box<>(nonNull));
            Box<List<@Nullable String>> nullableAfter = id(box(nullable));
          }
        """);
  }

  /** Checks executable calls in a self-contained nullness-marked source file. */
  private void check(String members) {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @NullMarked
            class Test {
            """
                + members
                + "}\n")
        .doTest();
  }

  /** Creates a helper using the standard JSpecify full-type inference configuration. */
  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }
}
