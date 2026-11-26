package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import org.junit.Test;

public class JSpecifyLibraryModelsTests extends NullAwayTestsBase {

  @Test
  public void atomicReferenceGet() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import org.jspecify.annotations.*;
            import java.util.concurrent.atomic.AtomicReference;
            @NullMarked
            class Test {
              void testNegative() {
                AtomicReference<Integer> x = new AtomicReference<>(Integer.valueOf(3));
                x.get().hashCode();
              }
              void testPositive() {
                AtomicReference<@Nullable Integer> x = new AtomicReference<>(Integer.valueOf(3));
                // BUG: Diagnostic contains: dereferenced expression 'x.get()' is @Nullable
                x.get().hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalMethods() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import java.util.function.Function;
            import org.jspecify.annotations.*;

            @NullMarked
            class Test {
              Optional<String> ofNullableAcceptsNullable(@Nullable String value) {
                return Optional.ofNullable(value);
              }

              Optional<String> mapCanReturnNullable(Optional<String> value) {
                Function<String, @Nullable String> mapper = unused -> null;
                return value.map(mapper);
              }

              void equalsAcceptsNullable(Optional<String> value) {
                value.equals(null);
              }

              // Regression test for applying the top-level @Nullable parameter
              // library model for orElse.  With a wildcard type we shouldn't create
              // the invalid type `@Nullable ?`
              void orElseAcceptsNullableForWildcard(Optional<?> value) {
                value.orElse(null);
              }

              void ofRejectsNull() {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Optional.of(null);
              }

              void orElseReturnsNullable(Optional<String> value) {
                // BUG: Diagnostic contains: dereferenced expression 'value.orElse(null)' is @Nullable
                value.orElse(null).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOrElseUsesFallbackNullness() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              void test(Optional<String> optional, String nonNull, @Nullable String nullable) {
                optional.orElse(nonNull).hashCode();
                // BUG: Diagnostic contains: dereferenced expression 'optional.orElse(nullable)' is @Nullable
                optional.orElse(nullable).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void getPropertyUsesDefaultValueNullness() {
    checkGetPropertyDefaultValueNullness(makeHelper());
  }

  @Test
  public void getPropertyUsesDefaultValueNullnessWithoutJdkModels() {
    checkGetPropertyDefaultValueNullness(
        makeTestHelperWithArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                Arrays.asList(
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:JSpecifyJDKModels=false"))));
  }

  /** Checks that both getProperty models use the default value's flow-sensitive nullness. */
  private void checkGetPropertyDefaultValueNullness(CompilationTestHelper helper) {
    helper
        .addSourceLines(
            "Test.java",
            """
            import java.util.Properties;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class Test {
              int systemPropertyWithLiteralDefault() {
                String value = System.getProperty("user.home", "");
                return value.length();
              }

              int propertiesPropertyWithLiteralDefault(Properties properties) {
                String value = properties.getProperty("key", "");
                return value.length();
              }

              void nonNullDefault(Properties properties, String defaultValue) {
                System.getProperty("key", defaultValue).length();
                properties.getProperty("key", defaultValue).length();
              }

              void nullDefault(Properties properties) {
                // BUG: Diagnostic contains: dereferenced expression 'System.getProperty("key", null)' is @Nullable
                System.getProperty("key", null).length();
                // BUG: Diagnostic contains: dereferenced expression 'properties.getProperty("key", null)' is @Nullable
                properties.getProperty("key", null).length();
              }

              void nullableDefault(Properties properties, @Nullable String defaultValue) {
                // BUG: Diagnostic contains: dereferenced expression 'System.getProperty("key", defaultValue)' is @Nullable
                System.getProperty("key", defaultValue).length();
                // BUG: Diagnostic contains: dereferenced expression 'properties.getProperty("key", defaultValue)' is @Nullable
                properties.getProperty("key", defaultValue).length();
              }

              void refinedDefault(Properties properties, @Nullable String defaultValue) {
                if (defaultValue != null) {
                  System.getProperty("key", defaultValue).length();
                  properties.getProperty("key", defaultValue).length();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalMapWithLambdaArg() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import org.jspecify.annotations.*;

            @NullMarked
            class Test {
              Optional<String> mapCanReturnNullable(Optional<String> value) {
                return value.map(unused -> null);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void optionalOrElseGet() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Optional;
            import java.util.function.Function;
            import org.jspecify.annotations.*;

            @NullMarked
            class Test {
              void orElseGetReturnsNullable(Optional<String> value) {
                value.orElseGet(() -> "fallback").hashCode();

                // BUG: Diagnostic contains: dereferenced expression 'value.orElseGet(() -> null)' is @Nullable
                value.orElseGet(() -> null).hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void issue1611() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
                    import java.util.Optional;
                    import org.jspecify.annotations.*;

                    @NullMarked
                    class Test {
                      static Optional<String> getKey(@Nullable String key) {
                        return Optional.ofNullable(key).or(() -> Optional.ofNullable(System.getenv("DEFAULT_KEY")));
                      }
                    }
                    """)
        .doTest();
  }

  @Test
  public void optionalOfNullableFilterIssue1597() {
    makeHelper()
        .addSourceLines(
            "Repro.java",
            """
            import java.util.Optional;
            import org.jspecify.annotations.*;

            @NullMarked
            public record Repro(@Nullable String name) {
              public Optional<String> getNameOpt() {
                return Optional.ofNullable(name).filter(it -> true);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void getCanonicalNameOnClassLiteral() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            """
            import java.util.Map;
            import java.util.function.IntSupplier;
            import org.jspecify.annotations.NullMarked;

            @NullMarked
            class Test {
              static class Member {
                static class MemberOfMember {}
              }

              Class<?> field = String.class;

              static void takesNonNull(String s) {}

              // ---- class literals of types with a canonical name: non-null ----

              int topLevelClass() {
                return String.class.getCanonicalName().length();
              }

              int memberClass() {
                return Map.Entry.class.getCanonicalName().length();
              }

              int memberOfMemberClass() {
                return Member.MemberOfMember.class.getCanonicalName().length();
              }

              int primitiveType() {
                return int.class.getCanonicalName().length();
              }

              int voidType() {
                return void.class.getCanonicalName().length();
              }

              int arrayOfTopLevelClass() {
                return String[].class.getCanonicalName().length();
              }

              int arrayOfPrimitive() {
                return int[][].class.getCanonicalName().length();
              }

              int viaLocalVariable() {
                String name = String.class.getCanonicalName();
                return name.length();
              }

              String returnedFromNonNullMethod() {
                return String.class.getCanonicalName();
              }

              void passedAsNonNullArgument() {
                takesNonNull(String.class.getCanonicalName());
              }

              // ---- class literals of types without a canonical name: nullable ----

              int localClass() {
                class Local {}
                // BUG: Diagnostic contains: dereferenced expression 'Local.class.getCanonicalName()' is @Nullable
                return Local.class.getCanonicalName().length();
              }

              int memberOfLocalClass() {
                class Local {
                  class Inner {}
                }
                // BUG: Diagnostic contains: dereferenced expression 'Local.Inner.class.getCanonicalName()' is @Nullable
                return Local.Inner.class.getCanonicalName().length();
              }

              int arrayOfLocalClass() {
                class Local {}
                // BUG: Diagnostic contains: dereferenced expression 'Local[].class.getCanonicalName()' is @Nullable
                return Local[].class.getCanonicalName().length();
              }

              int localRecord() {
                record LocalRecord() {}
                // BUG: Diagnostic contains: dereferenced expression 'LocalRecord.class.getCanonicalName()' is @Nullable
                return LocalRecord.class.getCanonicalName().length();
              }

              int localEnum() {
                enum LocalEnum { A }
                // BUG: Diagnostic contains: dereferenced expression 'LocalEnum.class.getCanonicalName()' is @Nullable
                return LocalEnum.class.getCanonicalName().length();
              }

              int localInterface() {
                interface LocalInterface {}
                // BUG: Diagnostic contains: dereferenced expression 'LocalInterface.class.getCanonicalName()' is @Nullable
                return LocalInterface.class.getCanonicalName().length();
              }

              int localClassInLambda() {
                IntSupplier supplier = () -> {
                  class InLambda {}
                  // BUG: Diagnostic contains: dereferenced expression 'InLambda.class.getCanonicalName()' is @Nullable
                  return InLambda.class.getCanonicalName().length();
                };
                return supplier.getAsInt();
              }

              int localClassViaLocalVariable() {
                class Local {}
                String name = Local.class.getCanonicalName();
                // BUG: Diagnostic contains: dereferenced expression 'name' is @Nullable
                return name.length();
              }

              String localClassReturned() {
                class Local {}
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                return Local.class.getCanonicalName();
              }

              void localClassPassedAsArgument() {
                class Local {}
                // BUG: Diagnostic contains: passing @Nullable parameter 'Local.class.getCanonicalName()' where @NonNull is required
                takesNonNull(Local.class.getCanonicalName());
              }

              // ---- receivers that are not class literals: remain nullable ----

              int classTypedParameter(Class<?> clazz) {
                // BUG: Diagnostic contains: dereferenced expression 'clazz.getCanonicalName()' is @Nullable
                return clazz.getCanonicalName().length();
              }

              int fieldReceiver() {
                // BUG: Diagnostic contains: dereferenced expression 'this.field.getCanonicalName()' is @Nullable
                return this.field.getCanonicalName().length();
              }

              int getClassReceiver(Object o) {
                // BUG: Diagnostic contains: dereferenced expression 'o.getClass().getCanonicalName()' is @Nullable
                return o.getClass().getCanonicalName().length();
              }

              int literalStoredInLocalVariable() {
                Class<?> clazz = String.class;
                // BUG: Diagnostic contains: dereferenced expression 'clazz.getCanonicalName()' is @Nullable
                return clazz.getCanonicalName().length();
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
