package com.uber.nullaway;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.BaseErrorProneJavaCompiler;
import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.DiagnosticTestHelper;
import com.google.errorprone.FileManagers;
import com.google.errorprone.FileObjects;
import com.google.errorprone.scanner.ScannerSupplier;
import java.io.StringWriter;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class JSpecifyUnrecognizedAnnotationLocationTest {

  /** Raises the check to warning level, since it reports nothing at its default severity. */
  private static final String WARN = "-Xep:JSpecifyUnrecognizedAnnotationLocation:WARN";

  /** Turns off reporting on the root type of a local variable, the one location with an option. */
  private static final String NO_LOCAL_ROOT_TYPE =
      "-XepOpt:JSpecifyUnrecognizedAnnotationLocation:CheckLocalVariableRootType=false";

  private CompilationTestHelper compilationHelper;
  private CompilationTestHelper compilationHelperDefaultLevel;
  private BugCheckerRefactoringTestHelper refactoringHelper;

  @Before
  public void setUp() {
    compilationHelper =
        CompilationTestHelper.newInstance(JSpecifyUnrecognizedAnnotationLocation.class, getClass())
            .setArgs(WARN);
    compilationHelperDefaultLevel =
        CompilationTestHelper.newInstance(JSpecifyUnrecognizedAnnotationLocation.class, getClass());
    refactoringHelper =
        BugCheckerRefactoringTestHelper.newInstance(
                JSpecifyUnrecognizedAnnotationLocation.class, getClass())
            .setArgs(WARN);
  }

  @Test
  public void noReportAtDefaultLevel() {
    // The source covers one construct per matcher, so any of the six that lost its isEnabled check
    // would report here.  At its SUGGESTION default the check emits neither a warning nor a note.
    compilationHelperDefaultLevel
        .addSourceLines(
            "test/Quiet.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            @Nullable
            class Quiet {
              @Nullable int count = 0;
              List<@Nullable ?> wildcard;
              @Nullable int method() {
                return 0;
              }
              <@Nullable T> void typeParameter() {}
              void arrayCreation() {
                Object array = new String @Nullable [5];
              }
            }
            """)
        .doTest();
  }

  @Test
  public void localVariableRootTypeIsSilencedWhenAskedFor() {
    // NO_LOCAL_ROOT_TYPE silences the root type only.  The rest of a local declaration is
    // classified as usual: a primitive is still reported for being a primitive, and a type
    // argument stays recognized.
    CompilationTestHelper.newInstance(JSpecifyUnrecognizedAnnotationLocation.class, getClass())
        .setArgs(WARN, NO_LOCAL_ROOT_TYPE)
        .addSourceLines(
            "test/Locals.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Locals {
              void method() {
                @Nullable List<String> rootType = null;
                String @Nullable [] array = null;
                try (@Nullable AutoCloseable resource = null) {
                } catch (Exception e) {
                }
                List<@Nullable String> typeArgument = null;
                // BUG: Diagnostic contains: on a primitive type
                @Nullable int primitive = 0;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void localVariableRootTypeIsReportedUnlessSilenced() {
    compilationHelper
        .addSourceLines(
            "test/Locals.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Locals {
              void method() {
                // BUG: Diagnostic contains: on the root type of a local variable
                @Nullable List<String> rootType = null;
                // BUG: Diagnostic contains: on the root type of a local variable
                String @Nullable [] array = null;
                try (
                    // BUG: Diagnostic contains: on the root type of a local variable
                    @Nullable AutoCloseable resource = null) {
                } catch (Exception e) {
                }
                List<@Nullable String> typeArgument = null;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void suppressionUsesTheCanonicalName() {
    compilationHelper
        .addSourceLines(
            "test/Suppressed.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Suppressed {
              @SuppressWarnings("JSpecifyUnrecognizedAnnotationLocation")
              @Nullable int count = 0;
            }
            """)
        .doTest();
  }

  @Test
  public void nonJSpecifyNullnessAnnotationsAreIgnored() {
    // The check matches JSpecify's own two annotations by fully qualified name, because the rule
    // is JSpecify's and JSpecify states it about its own annotations.  Another tool's annotation
    // keeps the meaning that tool defines, in these locations as it does anywhere else.
    compilationHelper
        .addSourceLines(
            "test/Nullable.java",
            """
            package test;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE_USE)
            public @interface Nullable {}
            """)
        .addSourceLines(
            "test/Families.java",
            """
            package test;
            class Families {
              @org.jetbrains.annotations.Nullable int jetbrains = 0;
              @org.jetbrains.annotations.NotNull int notNull = 0;
              @org.checkerframework.checker.nullness.qual.Nullable int checker = 0;
              @javax.annotation.Nullable int declarationOnly = 0;
              @Nullable int declaredHere = 0;
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              @org.jspecify.annotations.Nullable int jspecifyNullable = 0;
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              @org.jspecify.annotations.NonNull int jspecifyNonNull = 0;
            }
            """)
        .doTest();
  }

  @Test
  public void nullAwayStillReportsTheInnerClassLocation() {
    // Both checks report this annotation, each under its own canonical name, so suppressing one
    // leaves the other reported.
    bothChecksOn(
        """
        package test;
        import org.jspecify.annotations.Nullable;
        class Both {
          class Inner {}
          // BUG: Diagnostic contains: Type-use nullability annotations should be applied on inner class
          @Nullable Both.Inner field;
        }
        """);
  }

  @Test
  public void outerTypeStillReportedAlongsideNullAway() {
    bothChecksOn(
        """
        package test;
        import org.jspecify.annotations.Nullable;
        class Both {
          class Inner {}
          // BUG: Diagnostic contains: on the outer type qualifying an inner type
          @Nullable Both.Inner field;
        }
        """);
  }

  @Test
  public void annotationsInRecognizedLocationsAreNotReported() {
    compilationHelper
        .addSourceLines(
            "test/Recognized.java",
            """
            package test;
            import java.util.ArrayList;
            import java.util.List;
            import java.util.Map;
            import java.util.function.Function;
            import org.jspecify.annotations.Nullable;
            class Recognized<T extends @Nullable Object> {
              @Nullable String field;
              String @Nullable [] nullableArray;
              @Nullable String[] arrayOfNullable;
              List<@Nullable String> typeArgument;
              List<? extends @Nullable String> wildcardBound;
              Map.@Nullable Entry<String, String> innerType;
              @Nullable String method(@Nullable String parameter) {
                return parameter;
              }
              void nested() {
                Function<@Nullable String, String> lambda = (@Nullable String s) -> "";
                Object cast = (List<@Nullable String>) typeArgument;
                Object created = new ArrayList<@Nullable String>();
                Object array = new @Nullable String[5];
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aRecognizedLocationNestedInAnUnrecognizedOneIsNotReported() {
    compilationHelper
        .addSourceLines(
            "test/Nested.java",
            """
            package test;
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Nested extends ArrayList<@Nullable String> {
              record Component(@Nullable String value) {}
              void varargs(@Nullable String... values) {}
              void method(Object o) {
                Object cast = (List<@Nullable String>) o;
                Object created = new ArrayList<@Nullable String>();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void annotationInsideAQualifierChainIsRecognized() {
    // The annotation is on the type argument of a qualifier, a recognized location, so the check
    // reports nothing.  It is not on the outer type that qualifies Deep.
    compilationHelper
        .addSourceLines(
            "test/Chain.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Chain {
              class Generic<X> { class Deep {} }
              Chain.Generic<@Nullable String>.Deep insideChain;
            }
            """)
        .doTest();
  }

  @Test
  public void aNullnessAnnotationOnAPrimitiveIsReported() {
    // An annotation before an array applies to the component type, so the check reports a
    // primitive array as a primitive.  An annotation on the array itself is recognized.
    compilationHelper
        .addSourceLines(
            "test/Primitives.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Primitives {
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              @Nullable int field = 0;
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              @Nullable int[] primitiveArray = {};
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              @Nullable int[][] twoDimensions = {};
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              @Nullable int method() {
                return 0;
              }
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              void parameter(@Nullable long value) {}
              void local() {
                // BUG: Diagnostic contains: A nullness annotation on a primitive type
                @Nullable int[] array = null;
              }
              int @Nullable [] annotatedArray = null;
            }
            """)
        .doTest();
  }

  @Test
  public void eachConstructInsideAMethodBodyIsReportedAsItsOwnLocation() {
    compilationHelper
        .addSourceLines(
            "test/Locals.java",
            """
            package test;
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Locals {
              void method(Object o) {
                // BUG: Diagnostic contains: on the root type of a local variable
                @Nullable List<String> local = null;
                // BUG: Diagnostic contains: on the root type of a local variable
                String @Nullable [] array = null;
                // BUG: Diagnostic contains: on the root type of a cast
                Object cast = (@Nullable String) o;
                // BUG: Diagnostic contains: on the root type of an object creation expression
                Object created = new @Nullable ArrayList<String>();
                // BUG: Diagnostic contains: on the array type of an array creation expression
                Object newArray = new String @Nullable [5];
                // BUG: Diagnostic contains: on the type after an instanceof operator
                if (o instanceof @Nullable String) {}
                // BUG: Diagnostic contains: on a type in a pattern
                if (o instanceof @Nullable String s) {}
                try {
                  method(o);
                // BUG: Diagnostic contains: on the type of an exception parameter
                } catch (@Nullable RuntimeException e) {
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void declarationsAndSupertypes() {
    compilationHelper
        .addSourceLines(
            "test/Declarations.java",
            """
            package test;
            import java.util.ArrayList;
            import org.jspecify.annotations.Nullable;
            // BUG: Diagnostic contains: A nullness annotation on a class declaration
            @Nullable
            // BUG: Diagnostic contains: on a supertype in a class declaration
            class Declarations extends @Nullable ArrayList<String> {
              // BUG: Diagnostic contains: directly on a type parameter declaration
              <@Nullable T> void typeParameter() {}
              // BUG: Diagnostic contains: on the type of a receiver parameter
              void receiver(@Nullable Declarations this) {}
              // BUG: Diagnostic contains: on a thrown exception type
              void thrown() throws @Nullable RuntimeException {}
            }
            """)
        .doTest();
  }

  @Test
  public void annotationInterfaceMemberReturnTypeIsReported() {
    compilationHelper
        .addSourceLines(
            "test/Marker.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            @interface Marker {
              // BUG: Diagnostic contains: on the return type of an annotation interface member
              @Nullable String value();
            }
            """)
        .doTest();
  }

  @Test
  public void aNullnessAnnotationDirectlyOnAWildcardIsReported() {
    compilationHelper
        .addSourceLines(
            "test/Wildcards.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Wildcards {
              // BUG: Diagnostic contains: A nullness annotation directly on a wildcard
              List<@Nullable ?> unbounded;
              // BUG: Diagnostic contains: A nullness annotation directly on a wildcard
              List<@Nullable ? extends String> upperBounded;
              // BUG: Diagnostic contains: A nullness annotation directly on a wildcard
              List<@Nullable ? super String> lowerBounded;
            }
            """)
        .doTest();
  }

  @Test
  public void outerTypeQualifyingAnInnerTypeIsReported() {
    compilationHelper
        .addSourceLines(
            "test/Outer.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Outer {
              class Inner {}
              // BUG: Diagnostic contains: on the outer type qualifying an inner type
              @Nullable Outer.Inner field;
              Outer.@Nullable Inner recognized;
            }
            """)
        .doTest();
  }

  @Test
  public void anAnnotatedEnumConstantIsReportedAsItsType() {
    compilationHelper
        .addSourceLines(
            "test/Constants.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            enum Constants {
              // BUG: Diagnostic contains: on the type of an enum constant
              @Nullable BARE,
              // BUG: Diagnostic contains: on the type of an enum constant
              @Nullable WITH_ARGUMENT("x"),
              // BUG: Diagnostic contains: on the type of an enum constant
              @Nullable WITH_BODY {
                @Override
                String describe() {
                  return "body";
                }
              };
              Constants() {}
              Constants(String unused) {}
              String describe() {
                return "constant";
              }
            }
            """)
        .doTest();
  }

  @Test
  public void aConstructorIsReportedAsItsResultType() {
    compilationHelper
        .addSourceLines(
            "test/Constructors.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Constructors {
              // BUG: Diagnostic contains: on the result type of a constructor
              @Nullable Constructors() {}
              static class Nested {
                // BUG: Diagnostic contains: on the result type of a constructor
                @Nullable Nested() {}
              }
              record Rec(String value) {
                // BUG: Diagnostic contains: on the result type of a constructor
                @Nullable Rec {
                }
              }
              static class WithDeclarationAnnotation {
                // BUG: Diagnostic contains: on the result type of a constructor
                @Deprecated @Nullable WithDeclarationAnnotation() {}
              }
              @Nullable String method() {
                return null;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void annotationInterfaceDeclarationIsReported() {
    compilationHelper
        .addSourceLines(
            "test/Marked.java",
            """
            package test;
            // BUG: Diagnostic contains: on a class declaration
            @org.jspecify.annotations.Nullable
            @interface Marked {}
            """)
        .doTest();
  }

  @Test
  public void anyMultiCatchAlternativeIsReportedAsAnExceptionParameter() {
    compilationHelper
        .addSourceLines(
            "test/MultiCatch.java",
            """
            package test;
            import java.io.IOException;
            import org.jspecify.annotations.Nullable;
            class MultiCatch {
              void first() {
                try {
                  throw new IOException();
                // BUG: Diagnostic contains: on the type of an exception parameter
                } catch (@Nullable IOException | RuntimeException e) {
                }
              }
              void second() {
                try {
                  throw new IOException();
                // BUG: Diagnostic contains: on the type of an exception parameter
                } catch (IOException | @Nullable RuntimeException e) {
                }
              }
              void third() {
                try {
                  throw new IOException();
                // BUG: Diagnostic contains: on the type of an exception parameter
                } catch (IOException | IllegalArgumentException | @Nullable IllegalStateException e) {
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void intersectionCastRootTypeIsReported() {
    compilationHelper
        .addSourceLines(
            "test/Intersection.java",
            """
            package test;
            import java.io.Serializable;
            import java.util.RandomAccess;
            import org.jspecify.annotations.Nullable;
            class Intersection {
              void first(Object o) {
                // BUG: Diagnostic contains: on the root type of a cast
                Object x = (@Nullable Runnable & Serializable) o;
              }
              void later(Object o) {
                // BUG: Diagnostic contains: on the root type of a cast
                Object x = (Runnable & @Nullable Serializable) o;
              }
              void three(Object o) {
                // BUG: Diagnostic contains: on the root type of a cast
                Object x = (Runnable & Serializable & @Nullable RandomAccess) o;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void typeArgumentOfReceiverParameterTypeIsReported() {
    compilationHelper
        .addSourceLines(
            "test/Receiver.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Receiver<T> {
              class Inner {
                // BUG: Diagnostic contains: on the type of a receiver parameter
                void method(Receiver<@Nullable T>.Inner this) {}
              }
            }
            """)
        .doTest();
  }

  @Test
  public void methodReferenceRootTypeIsReported() {
    // JSpecify lists no location that a method reference's root type falls under, so its
    // catch-all rule classifies the root type: everything not listed as recognized is
    // unrecognized.
    //
    // Two spellings that javac has not treated alike across releases sit in
    // methodReferenceQualifiedRootTypeIsReported and methodReferenceArrayRootTypeBeforeJdk26
    // instead, so this fixture holds only rows that produce the same diagnostic on every
    // supported release.
    compilationHelper
        .addSourceLines(
            "test/References.java",
            """
            package test;
            import java.util.function.Function;
            import java.util.function.Supplier;
            import org.jspecify.annotations.Nullable;
            class References {
              static class Nested {}
              // BUG: Diagnostic contains: on the root type of a method reference
              Supplier<String> simpleName = @Nullable String::new;
              // BUG: Diagnostic contains: on the root type of a method reference
              Function<String, Integer> instanceMethod = @Nullable String::length;
              // A type argument of the root type is nested inside it, and stays recognized.
              java.util.function.Supplier<java.util.List<String>> typeArgument =
                  java.util.ArrayList<@Nullable String>::new;
              <X> void g() {}
              void explicitTypeArgument() {
                // Written as a type argument, the same name reports its qualifier instead.  The
                // phrase is the specification's and names the common case; `Nested` is static
                // here, so `References` scopes the name rather than enclosing an instance.
                // BUG: Diagnostic contains: on the outer type qualifying an inner type
                this.<@Nullable References.Nested>g();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void methodReferenceQualifiedRootTypeIsReported() {
    // A qualified name crashes javac while it parses, in TreeInfo.isStaticSym on a symbol the
    // parser has not set yet, where 25 and earlier accept it.  The regression arrived in 26 b23
    // and is open as https://bugs.openjdk.org/browse/JDK-8391567.
    //
    // The upper bound is a reminder, not a claim about 29: the test runs again on 29, and passes
    // there once javac is fixed.  A failure on 29 says the fix has not landed, so raise the bound
    // to the next release; once it passes, drop the guard and fold this fixture back into
    // methodReferenceRootTypeIsReported.
    int feature = Runtime.version().feature();
    Assume.assumeTrue(feature < 26 || feature >= 29);
    compilationHelper
        .addSourceLines(
            "test/References.java",
            """
            package test;
            import java.util.function.Supplier;
            import org.jspecify.annotations.Nullable;
            class References {
              static class Nested {}
              // BUG: Diagnostic contains: on the root type of a method reference
              Supplier<Nested> qualifiedName = @Nullable References.Nested::new;
            }
            """)
        .doTest();
  }

  @Test
  public void methodReferenceArrayRootTypeBeforeJdk26() {
    // Before 26 javac lands the annotation on the array; from 26 on it lands on the component,
    // which JSpecify recognizes.  So the diagnostic below is the right one only before 26.  That
    // move is deliberate: JDK-8369489 replaced the wrapping with insertAnnotationsToMostInner.
    // This guard therefore stays, unlike the one in methodReferenceQualifiedRootTypeIsReported.
    // The test pins the check to the type usage javac produces, not to which of the two readings
    // the language specifies.
    Assume.assumeTrue(Runtime.version().feature() < 26);
    compilationHelper
        .addSourceLines(
            "test/References.java",
            """
            package test;
            import java.util.function.Function;
            import org.jspecify.annotations.Nullable;
            class References {
              // BUG: Diagnostic contains: on the root type of a method reference
              Function<Integer, String[]> arrayConstructor = @Nullable String[]::new;
            }
            """)
        .doTest();
  }

  @Test
  public void arrayTypeOfAnArrayCreationIsReported() {
    compilationHelper
        .addSourceLines(
            "test/ArrayCreation.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class ArrayCreation {
              void method() {
                // BUG: Diagnostic contains: on the array type of an array creation expression
                Object initializer = new String @Nullable [] {"a"};
                // BUG: Diagnostic contains: on the array type of an array creation expression
                Object dimension = new String @Nullable [5];
                // BUG: Diagnostic contains: on the array type of an array creation expression
                Object nested = new String @Nullable [][] {{"a"}};
                Object component = new @Nullable String[] {"a"};
                Object componentDimension = new @Nullable String[5];
              }
            }
            """)
        .doTest();
  }

  @Test
  public void arrayCreationReportsTheCreatedArrayOnlyOnce() {
    // javac records the created array's annotation in getAnnotations() for the initializer
    // spelling and in getDimAnnotations() for the dimension spelling; matchNewArray reads
    // whichever holds it, so it reports each spelling's array once.
    ImmutableList<String> messages =
        diagnostics(
            "test/Once.java",
            "package test;",
            "import org.jspecify.annotations.Nullable;",
            "class Once {",
            "  Object initializer = new String @Nullable [] {\"x\"};",
            "  Object dimension = new String @Nullable [1];",
            "}");
    assertThat(messages).hasSize(2);
    for (String message : messages) {
      assertThat(message).contains("on the array type of an array creation expression");
    }
  }

  @Test
  public void unionAndIntersectionMembersAreReported() {
    compilationHelper
        .addSourceLines(
            "test/Members.java",
            """
            package test;
            import java.io.IOException;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Members {
              void method(Object o) {
                try {
                  throw new IOException();
                // BUG: Diagnostic contains: on the type of an exception parameter
                } catch (@Nullable IOException | RuntimeException e) {
                }
                try {
                  throw new IOException();
                // BUG: Diagnostic contains: on the type of an exception parameter
                } catch (IOException | @Nullable RuntimeException e) {
                }
                // BUG: Diagnostic contains: on the root type of a cast
                Object intersection = (@Nullable Runnable & Serializable) o;
              }
              // A type parameter bound is recognized, intersection or not.
              <T extends @Nullable Number & Serializable> void bound() {}
            }
            """)
        .doTest();
  }

  @Test
  public void outerTypeIsReportedUnderEveryConstructThatDoesNotCoverNestedTypes() {
    compilationHelper
        .addSourceLines(
            "test/Constructs.java",
            """
            package test;
            import java.util.ArrayList;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Constructs {
              class Inner {}
              // BUG: Diagnostic contains: on the outer type qualifying an inner type
              List<@Nullable Constructs.Inner> field;
              // BUG: Diagnostic contains: on the outer type qualifying an inner type
              static class Sub extends ArrayList<@Nullable Constructs.Inner> {}
              void method(Object o) {
                // BUG: Diagnostic contains: on the outer type qualifying an inner type
                List<@Nullable Constructs.Inner> local = null;
                // BUG: Diagnostic contains: on the outer type qualifying an inner type
                Object cast = (List<@Nullable Constructs.Inner>) o;
                // BUG: Diagnostic contains: on the outer type qualifying an inner type
                Object created = new ArrayList<@Nullable Constructs.Inner>();
                // A location that covers nested types takes the annotation instead, so the fix
                // removes it rather than moving it somewhere still unrecognized.
                // BUG: Diagnostic contains: on the type after an instanceof operator
                if (o instanceof @Nullable Constructs.Inner) {}
                // BUG: Diagnostic contains: on a type in a pattern
                if (o instanceof @Nullable Constructs.Inner i) {}
                // The annotation is on the cast's own root type, so the cast wins there.
                // BUG: Diagnostic contains: on the root type of a cast
                Object rootCast = (@Nullable Constructs.Inner) o;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void outerTypeInsideAnUnrecognizedRootIsReportedAsThatRoot() {
    // Moving the annotation inward would only relocate it to another unrecognized location, so the
    // diagnostic names the enclosing root and the fix only deletes the annotation.
    compilationHelper
        .addSourceLines(
            "test/Roots.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Roots {
              class Inner extends RuntimeException { class Deeper {} }
              // BUG: Diagnostic contains: on the root type of a cast
              Object cast(Object o) { return (@Nullable Roots.Inner) o; }
              // BUG: Diagnostic contains: on the type after an instanceof operator
              boolean test(Object o) { return o instanceof @Nullable Roots.Inner; }
              // BUG: Diagnostic contains: on a thrown exception type
              void thrown() throws @Nullable Roots.Inner {}
              // BUG: Diagnostic contains: on the root type of an object creation expression
              Object created = new @Nullable Roots.Inner();
              // BUG: Diagnostic contains: on the root type of a cast
              Object chainInCast(Object o) { return (@Nullable Roots.Inner.Deeper) o; }
              // A type argument is a recognized nesting step, so this one still moves.
              // BUG: Diagnostic contains: on the outer type qualifying an inner type
              List<@Nullable Roots.Inner> typeArgument;
              // BUG: Diagnostic contains: on the outer type qualifying an inner type
              @Nullable Roots.Inner field;
            }
            """)
        .doTest();
  }

  @Test
  public void qualifiedInnerTypeInArrayIsReported() {
    compilationHelper
        .addSourceLines(
            "test/Arrays.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Arrays {
              class Inner {}
              // BUG: Diagnostic contains: on the outer type qualifying an inner type
              @Nullable Arrays.Inner field;
              // BUG: Diagnostic contains: on the outer type qualifying an inner type
              @Nullable Arrays.Inner[] oneDimension;
              // BUG: Diagnostic contains: on the outer type qualifying an inner type
              @Nullable Arrays.Inner[][] twoDimensions;
              // BUG: Diagnostic contains: on the outer type qualifying an inner type
              void parameter(@Nullable Arrays.Inner[] values) {}
              // A plain array component is a recognized location and stays unreported.
              @Nullable String[] plainArray;
              void locals() {
                // BUG: Diagnostic contains: on the root type of a local variable
                @Nullable Arrays.Inner local = null;
                // The array component is a recognized location even in a local, so the annotation
                // is named by what it lands on rather than by the declaration.
                // BUG: Diagnostic contains: on the outer type qualifying an inner type
                @Nullable Arrays.Inner[] arrayLocal = null;
                @Nullable String[] plainLocal = null;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void wildcardTakesAnEnclosingLocationThatCoversNestedTypes() {
    compilationHelper
        .addSourceLines(
            "test/CoveredByEnclosing.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class CoveredByEnclosing {
              void method(Object o) {
                // BUG: Diagnostic contains: on the type after an instanceof operator
                if (o instanceof List<@Nullable ?>) {}
                // BUG: Diagnostic contains: on a type in a pattern
                if (o instanceof List<@Nullable ?> l) {}
                // BUG: Diagnostic contains: A nullness annotation directly on a wildcard
                List<@Nullable ?> local = null;
                // BUG: Diagnostic contains: A nullness annotation directly on a wildcard
                Object cast = (List<@Nullable ?>) o;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void annotatedPrimitiveInAnonymousSupertypeIsReportedOnce() {
    // The primitive classification is held until the walk ends, so the anonymous-class guard
    // still runs and keeps the supertype path from reporting a second time.
    ImmutableList<String> messages =
        diagnostics(
            "test/Primitive.java",
            "package test;",
            "import java.util.ArrayList;",
            "import org.jspecify.annotations.Nullable;",
            "class Primitive {",
            "  Object anonymous = new ArrayList<@Nullable int[]>() {};",
            "  Object plain = new ArrayList<@Nullable int[]>();",
            "  Object cast(long x) { return (@Nullable int) x; }",
            "  @Nullable int field = 0;",
            "  boolean test(java.util.List<int[]> l) { return l instanceof ArrayList<@Nullable int[]>; }",
            "  void pattern(java.util.List<int[]> l) {",
            "    if (l instanceof ArrayList<@Nullable int[]> a) {}",
            "  }",
            "}");
    // `instanceof` and its pattern cover the types nested inside them, so an enclosing location
    // competes with the primitive on those two rows alone.  Both need a checked operand: with
    // an `Object` the cast is unchecked and javac rejects the source.
    assertThat(messages).hasSize(6);
    for (String message : messages) {
      // A primitive names the most specific location, so an enclosing one never wins.
      assertThat(message).contains("on a primitive type");
    }
  }

  @Test
  public void typeArgumentOfGenericOuterTypeIsRecognized() {
    compilationHelper
        .addSourceLines(
            "test/Generic.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Generic<T> {
              class Inner {}
              Generic<@Nullable String>.Inner field;
              void parameter(Generic<@Nullable T>.Inner unused) {}
              void method() {
                Generic<@Nullable String>.Inner local = null;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void anonymousClassSupertypeIsReportedOnce() {
    // The anonymous class body's supertype tree is the same tree the `new` expression names, so
    // the annotation is reachable through two TreePaths and the check reports it on only one.
    ImmutableList<String> messages =
        diagnostics(
            "test/Anonymous.java",
            "package test;",
            "import org.jspecify.annotations.Nullable;",
            "class Anonymous {",
            "  class Inner {}",
            "  class Generic<X> {}",
            "  Object fromInterface = new @Nullable Runnable() { public void run() {} };",
            "  Object fromClass = new @Nullable Thread() {};",
            "  Object qualified = new @Nullable Anonymous.Inner() {};",
            "  Object parameterized = new @Nullable Anonymous.Generic<String>() {};",
            "  Object plain = new @Nullable Thread();",
            "}");
    assertThat(messages).hasSize(5);
    for (String message : messages) {
      assertThat(message).contains("on the root type of an object creation expression");
    }
  }

  @Test
  public void namedClassInsideAnonymousClassStillReportsItsSupertype() {
    // The anonymous-class guard covers only the supertype the `new` expression names, so
    // declarations inside the body keep their own locations.
    compilationHelper
        .addSourceLines(
            "test/Inside.java",
            """
            package test;
            import java.util.ArrayList;
            import org.jspecify.annotations.Nullable;
            class Inside {
              Object o = new Runnable() {
                // BUG: Diagnostic contains: on a supertype in a class declaration
                class Local extends @Nullable ArrayList<String> {}
                // BUG: Diagnostic contains: on the root type of a local variable
                public void run() { @Nullable String s = null; }
              };
            }
            """)
        .doTest();
  }

  @Test
  public void annotatedEnumConstantIsReportedOnce() {
    // An enum constant with a body declares an anonymous class as well, so one annotation could be
    // reported twice.  A per-line diagnostic match sees the text of a report and not how many
    // arrive, so this test asserts the number of messages instead.
    ImmutableList<String> messages =
        diagnostics(
            "test/Constants.java",
            "package test;",
            "import org.jspecify.annotations.Nullable;",
            "enum Constants {",
            "  UNANNOTATED,",
            "  @Nullable ANNOTATED,",
            "  @Nullable WITH_BODY { void run() {} };",
            "  void run() {}",
            "  @Nullable String field;",
            "}");
    assertThat(messages).hasSize(2);
    for (String message : messages) {
      assertThat(message).contains("on the type of an enum constant");
    }
  }

  @Test
  public void annotationsInsideEnumConstantArgumentsKeepTheirOwnLocations() {
    // An enum constant declares no type, so the only annotation it can carry is in its modifiers.
    // An annotation written inside its arguments takes the location of the construct that holds it.
    compilationHelper
        .addSourceLines(
            "test/Arguments.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            enum Arguments {
              // BUG: Diagnostic contains: on the root type of a cast
              CAST((@Nullable Object) null),
              // The component type of an array creation is a recognized location.
              ARRAY(new @Nullable String[1]),
              // BUG: Diagnostic contains: on the root type of an object creation expression
              CREATION(new @Nullable Object());
              Arguments(Object o) {}
            }
            """)
        .doTest();
  }

  @Test
  public void recordComponentIsReportedOnce() {
    // javac copies each record component into the compact constructor's parameter list, keeping
    // the component's source positions and sharing its annotation trees, so one annotation is
    // reachable through two declarations.
    ImmutableList<String> messages =
        diagnostics(
            "test/Records.java",
            "package test;",
            "import java.util.List;",
            "import org.jspecify.annotations.Nullable;",
            "class Records {",
            "  class Inner {}",
            "  record Compact(@Nullable int x) { Compact { } }",
            "  record CompactWildcard(List<@Nullable ?> w) { CompactWildcard { } }",
            "  record CompactQualifier(@Nullable Records.Inner q) { CompactQualifier { } }",
            "  record CompactGeneric<T>(List<@Nullable ?> g) { CompactGeneric { } }",
            "  record Bare(@Nullable int z) {}",
            "}");
    assertThat(messages).hasSize(5);
  }

  @Test
  public void compactConstructorBodyIsStillReported() {
    // javac's copies of the record components are told apart from the author's own declarations by
    // the parameter list they sit in, not by their symbol kind: a lambda parameter written in the
    // compact constructor's body is an ElementKind.PARAMETER owned by that same constructor.
    compilationHelper
        .addSourceLines(
            "test/Body.java",
            """
            package test;
            import java.util.function.IntFunction;
            import org.jspecify.annotations.Nullable;
            class Body {
              record R(int a) {
                R {
                  // BUG: Diagnostic contains: on a primitive type
                  IntFunction<String> lambda = (@Nullable int s) -> "";
                  // BUG: Diagnostic contains: on the root type of a local variable
                  @Nullable String local = null;
                  try {
                    throw new RuntimeException();
                  // BUG: Diagnostic contains: on the type of an exception parameter
                  } catch (@Nullable RuntimeException caught) {
                  }
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void explicitCanonicalConstructorReportsBothAnnotations() {
    // An explicit canonical constructor's parameters are the author's own, not javac's copies, so
    // an annotation on the component and one on the parameter are two reports, not one.
    ImmutableList<String> messages =
        diagnostics(
            "test/Explicit.java",
            "package test;",
            "class Explicit {",
            "  record R(@org.jspecify.annotations.Nullable int y) {",
            "    R(@org.jspecify.annotations.Nullable int y) { this.y = y; }",
            "  }",
            "}");
    assertThat(messages).hasSize(2);
  }

  @Test
  public void recordComponentAnnotationIsReportedWhateverTheConstructorForm() {
    // A compact constructor's parameters are javac's copies of the record components, and the
    // check reports the component rather than the copy.
    compilationHelper
        .addSourceLines(
            "test/Constructors.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Constructors {
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              record Implicit(@Nullable int a) {}
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              record Compact(@Nullable int a) {
                Compact {
                  a = Math.abs(a);
                }
              }
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              record Explicit(@Nullable int a) {
                Explicit(int a) {
                  this.a = a;
                }
              }
              // BUG: Diagnostic contains: A nullness annotation on a primitive type
              record Generic<T>(@Nullable int a, T b) {
                Generic {}
              }
              // An annotation the author writes on an explicit canonical constructor's own
              // parameter is on that parameter, so it is still classified.
              record WrittenParameter(int a) {
                // BUG: Diagnostic contains: A nullness annotation on a primitive type
                WrittenParameter(@Nullable int a) {
                  this.a = a;
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void unrecognizedRecordComponentTypeIsReported() {
    compilationHelper
        .addSourceLines(
            "test/Components.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Components {
              class Inner {}
              // BUG: Diagnostic contains: on the outer type qualifying an inner type
              record OuterType(@Nullable Components.Inner outerType) {}
              record Primitive(
                  // BUG: Diagnostic contains: on a primitive type
                  @Nullable int count) {}
              // BUG: Diagnostic contains: on a primitive type
              @Nullable int notARecordComponent = 0;
            }
            """)
        .doTest();
  }

  @Test
  public void fixRemovesAnnotationOnPrimitive() {
    refactoringHelper
        .addInputLines(
            "test/Primitives.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Primitives {
              @Nullable int count = 0;
              @Nullable int[] counts = {};
              @Nullable int[][] grid = {};
            }
            """)
        .addOutputLines(
            "test/Primitives.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Primitives {
              int count = 0;
              int[] counts = {};
              int[][] grid = {};
            }
            """)
        .doTest();
  }

  @Test
  public void fixMovesWildcardAnnotationToBound() {
    refactoringHelper
        .addInputLines(
            "test/Wildcards.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Wildcards {
              List<@Nullable ?> unbounded;
              List<@Nullable ? extends String> upperBounded;
            }
            """)
        .addOutputLines(
            "test/Wildcards.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Wildcards {
              List<? extends @Nullable Object> unbounded;
              List<? extends @Nullable String> upperBounded;
            }
            """)
        .doTest();
  }

  @Test
  public void fixMovesTypeParameterAnnotationToBound() {
    refactoringHelper
        .addInputLines(
            "test/Parameters.java",
            """
            package test;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Parameters {
              <@Nullable T> void unbounded() {}
              <@Nullable T extends Number> void bounded() {}
              <@Nullable T extends Number & Serializable> void intersection() {}
            }
            """)
        .addOutputLines(
            "test/Parameters.java",
            """
            package test;
            import java.io.Serializable;
            import org.jspecify.annotations.Nullable;
            class Parameters {
              <T extends @Nullable Object> void unbounded() {}
              <T extends @Nullable Number> void bounded() {}
              <T extends Number & Serializable> void intersection() {}
            }
            """)
        .doTest();
  }

  @Test
  public void fixMovesBoundAnnotationOntoTheBoundItself() {
    refactoringHelper
        .addInputLines(
            "test/Bounds.java",
            """
            package test;
            import java.util.List;
            import java.util.Map;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Bounds {
              class Inner {}
              <@Nullable T extends Bounds.Inner> void innerType() {}
              <@Nullable T extends Map.Entry<String, String>> void staticNested() {}
              <@Nullable T extends java.util.Locale> void packageQualified() {}
              <@Nullable T extends Number> void plainClass() {}
              List<@Nullable ? extends Bounds.Inner> innerTypeWildcard;
              List<@Nullable ? extends Map.Entry<String, String>> staticNestedWildcard;
              List<@Nullable ? extends String[]> arrayWildcard;
              List<@Nullable ? extends String[][]> twoDimensionArrayWildcard;
              List<@Nullable ? extends String @NonNull []> alreadyAnnotatedArrayWildcard;
            }
            """)
        .addOutputLines(
            "test/Bounds.java",
            """
            package test;
            import java.util.List;
            import java.util.Map;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Bounds {
              class Inner {}
              <T extends Bounds.@Nullable Inner> void innerType() {}
              <T extends Map.@Nullable Entry<String, String>> void staticNested() {}
              <T extends java.util.@Nullable Locale> void packageQualified() {}
              <T extends @Nullable Number> void plainClass() {}
              List<? extends Bounds.@Nullable Inner> innerTypeWildcard;
              List<? extends Map.@Nullable Entry<String, String>> staticNestedWildcard;
              List<? extends String @Nullable []> arrayWildcard;
              List<? extends String @Nullable [][]> twoDimensionArrayWildcard;
              List<? extends String @NonNull []> alreadyAnnotatedArrayWildcard;
            }
            """)
        .doTest();
  }

  @Test
  public void fixAnnotatesAnArrayBoundAtItsOutermostDimension() {
    refactoringHelper
        .addInputLines(
            "test/ArrayBounds.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class ArrayBounds {
              List<@Nullable ? extends String[][]> twoDimensional;
              List<@Nullable ? extends int[]> primitiveComponent;
            }
            """)
        .addOutputLines(
            "test/ArrayBounds.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class ArrayBounds {
              List<? extends String @Nullable [][]> twoDimensional;
              List<? extends int @Nullable []> primitiveComponent;
            }
            """)
        .doTest();
  }

  @Test
  public void fixAnnotatesAnArrayBoundWithAGenericOrQualifiedComponent() {
    refactoringHelper
        .addInputLines(
            "test/Components.java",
            """
            package test;
            import java.util.List;
            import java.util.Map;
            import org.jspecify.annotations.Nullable;
            class Components {
              class Inner {}
              List<@Nullable ? extends List<String>[]> generic;
              List<@Nullable ? extends Map.Entry<String, String>[]> qualified;
              List<@Nullable ? extends Components.Inner[]> innerClass;
            }
            """)
        .addOutputLines(
            "test/Components.java",
            """
            package test;
            import java.util.List;
            import java.util.Map;
            import org.jspecify.annotations.Nullable;
            class Components {
              class Inner {}
              List<? extends List<String> @Nullable []> generic;
              List<? extends Map.Entry<String, String> @Nullable []> qualified;
              List<? extends Components.Inner @Nullable []> innerClass;
            }
            """)
        .doTest();
  }

  @Test
  public void fixMovesOntoAnArrayWhoseComponentIsAlreadyAnnotated() {
    refactoringHelper
        .addInputLines(
            "test/AnnotatedComponent.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class AnnotatedComponent {
              List<@Nullable ? extends @NonNull String[]> field;
            }
            """)
        .addOutputLines(
            "test/AnnotatedComponent.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class AnnotatedComponent {
              List<? extends @NonNull String @Nullable []> field;
            }
            """)
        .doTest();
  }

  @Test
  public void fixAnnotatesAnArrayBoundWhoseOutermostDimensionIsAnnotated() {
    refactoringHelper
        .addInputLines(
            "test/Td.java",
            """
            package test;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE_USE)
            @interface Td {}
            """)
        .expectUnchanged()
        .addInputLines(
            "test/Dimensions.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Dimensions {
              List<@Nullable ? extends String @Td []> outermost;
              List<@Nullable ? extends String @Td [] @Td []> bothDimensions;
              List<@Nullable ? extends String @NonNull []> occupied;
            }
            """)
        .addOutputLines(
            "test/Dimensions.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Dimensions {
              List<? extends String @Nullable @Td []> outermost;
              List<? extends String @Nullable @Td [] @Td []> bothDimensions;
              List<? extends String @NonNull []> occupied;
            }
            """)
        .doTest();
  }

  @Test
  public void fixRemovesAnnotationOnLowerBoundedWildcard() {
    refactoringHelper
        .addInputLines(
            "test/Wildcards.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Wildcards {
              List<@Nullable ? super String> lowerBounded;
            }
            """)
        .addOutputLines(
            "test/Wildcards.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Wildcards {
              List<? super String> lowerBounded;
            }
            """)
        .doTest();
  }

  @Test
  public void fixRemovesTypeParameterAnnotationWhenTheBoundIsAnIntersection() {
    refactoringHelper
        .addInputLines(
            "test/Intersection.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Intersection {
              interface A {}
              interface B {}
              <@Nullable T extends A & B> void method() {}
            }
            """)
        .addOutputLines(
            "test/Intersection.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Intersection {
              interface A {}
              interface B {}
              <T extends A & B> void method() {}
            }
            """)
        .doTest();
  }

  @Test
  public void fixMovesOuterTypeAnnotationToInnerType() {
    refactoringHelper
        .addInputLines(
            "test/Outer.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Outer {
              class Inner {}
              @Nullable Outer.Inner field;
            }
            """)
        .addOutputLines(
            "test/Outer.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class Outer {
              class Inner {}
              Outer.@Nullable Inner field;
            }
            """)
        .doTest();
  }

  @Test
  public void fixMovesOuterTypeAnnotationPastEveryQualifier() {
    refactoringHelper
        .addInputLines(
            "test/Chain.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Chain {
              class B {
                class C {}
              }
              List<@Nullable Chain.B.C> insideATypeArgument;
              @Nullable Chain.B.C inModifiers;
            }
            """)
        .addOutputLines(
            "test/Chain.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Chain {
              class B {
                class C {}
              }
              List<Chain.B.@Nullable C> insideATypeArgument;
              Chain.B.@Nullable C inModifiers;
            }
            """)
        .doTest();
  }

  @Test
  public void fixMovesOuterTypeAnnotationInsideATypeArgument() {
    refactoringHelper
        .addInputLines(
            "test/Outer.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Outer {
              class Inner {}
              List<@Nullable Outer.Inner> field;
            }
            """)
        .addOutputLines(
            "test/Outer.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Outer {
              class Inner {}
              List<Outer.@Nullable Inner> field;
            }
            """)
        .doTest();
  }

  @Test
  public void fixMovesOuterTypeAnnotationInAnArrayDeclaration() {
    refactoringHelper
        .addInputLines(
            "test/ArrayFix.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class ArrayFix {
              class Inner {}
              @Nullable ArrayFix.Inner[] oneDimension;
              @Nullable ArrayFix.Inner[][] twoDimensions;
            }
            """)
        .addOutputLines(
            "test/ArrayFix.java",
            """
            package test;
            import org.jspecify.annotations.Nullable;
            class ArrayFix {
              class Inner {}
              ArrayFix.@Nullable Inner[] oneDimension;
              ArrayFix.@Nullable Inner[][] twoDimensions;
            }
            """)
        .doTest();
  }

  @Test
  public void fixMovesOuterTypeAnnotationWithAGenericQualifier() {
    refactoringHelper
        .addInputLines(
            "test/Generic.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Generic<X> {
              class Inner {
                class Deeper {}
              }
              List<@Nullable Generic<String>.Inner> typeArgument;
              List<? extends @Nullable Generic<String>.Inner> wildcardBound;
              List<@Nullable Generic<String>.Inner[]> arrayComponent;
              List<@Nullable Generic<String>.Inner.Deeper> twoLevels;
              @Nullable Generic<String>.Inner declaration;
            }
            """)
        .addOutputLines(
            "test/Generic.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Generic<X> {
              class Inner {
                class Deeper {}
              }
              List<Generic<String>.@Nullable Inner> typeArgument;
              List<? extends Generic<String>.@Nullable Inner> wildcardBound;
              List<Generic<String>.@Nullable Inner[]> arrayComponent;
              List<Generic<String>.Inner.@Nullable Deeper> twoLevels;
              Generic<String>.@Nullable Inner declaration;
            }
            """)
        .doTest();
  }

  @Test
  public void fixKeepsOtherTypeUseAnnotationsOnTheQualifiedType() {
    refactoringHelper
        .addInputLines(
            "test/Nested.java",
            """
            package test;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Nested {
              @Target(ElementType.TYPE_USE)
              @interface Interned {}
              class Inner {}
              List<@Nullable @Interned Nested.Inner> insideATypeArgument;
              @Nullable Nested.@Interned Inner inModifiers;
            }
            """)
        .addOutputLines(
            "test/Nested.java",
            """
            package test;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            class Nested {
              @Target(ElementType.TYPE_USE)
              @interface Interned {}
              class Inner {}
              List<@Interned Nested.@Nullable Inner> insideATypeArgument;
              Nested.@Interned @Nullable Inner inModifiers;
            }
            """)
        .doTest();
  }

  @Test
  public void fixLocatesTheSelectedNameInSource() {
    // A name may be written with Unicode escapes, which javac decodes before it lexes, so the
    // source tokens have to supply the offset rather than the decoded name's length.  The fixture
    // spells `Inner` with an escape for its `e`: `\\u0065` is not itself a Unicode escape, because
    // only an odd number of backslashes starts one, so the six characters reach the fixture intact.
    // The assertions read the fix out of the diagnostic rather than through
    // BugCheckerRefactoringTestHelper, which formats both sides with google-java-format and cannot
    // parse an escaped name.
    ImmutableList<String> messages =
        diagnostics(
            "test/Escapes.java",
            "package test;",
            "import java.util.List;",
            "import org.jspecify.annotations.Nullable;",
            "class Escapes {",
            "  class Inner {}",
            "  @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)",
            "  @interface Marker { String name(); Class<?> type() default void.class; }",
            "  @Nullable Escapes.Inn\\u0065r escapedName;",
            "  @Nullable Escap\\u0065s.Inner escapedQualifier;",
            "  @Nullable Escapes. /* between */ Inner comment;",
            "  @Nullable Escapes.@Marker(name = \"x\") Inner annotationArgument;",
            "  List<@Nullable ? extends Escapes.Inn\\u0065r> escapedBound;",
            "  List<@Nullable ? extends Escapes.@Marker(name = \"x\") Inner> annotatedBound;",
            "  @Nullable Escapes.@Marker(name = \"x\", type = int.class) Inner keywordArgument;",
            "}");
    assertThat(messages).hasSize(7);
    assertThat(messages.get(0)).contains("Escapes.@Nullable Inn\\u0065r escapedName");
    assertThat(messages.get(1)).contains("Escap\\u0065s.@Nullable Inner escapedQualifier");
    assertThat(messages.get(2)).contains("Escapes. /* between */ @Nullable Inner comment");
    assertThat(messages.get(3))
        .contains("Escapes.@Marker(name = \"x\") @Nullable Inner annotationArgument");
    assertThat(messages.get(4)).contains("? extends Escapes.@Nullable Inn\\u0065r");
    assertThat(messages.get(5)).contains("? extends Escapes.@Marker(name = \"x\") @Nullable Inner");
    assertThat(messages.get(6))
        .contains(
            "Escapes.@Marker(name = \"x\", type = int.class) @Nullable Inner keywordArgument");
  }

  @Test
  public void fixMovesOuterTypeAnnotationPastAnAnnotatedQualifier() {
    refactoringHelper
        .addInputLines(
            "test/Tq.java",
            """
            package test;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE_USE)
            @interface Tq {}
            """)
        .expectUnchanged()
        .addInputLines(
            "test/Chain.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Chain {
              class B {
                class C {}
              }
              List<@Nullable Chain.@Tq B.C> nested;
              @Nullable Chain.@Tq B.C declaration;
              List<@Nullable Chain.@Nullable B.C> nullnessOnTheQualifier;
              List<@Nullable Chain.@NonNull B.C> differentNullnessOnTheQualifier;
              @Nullable Chain.@Nullable B.C nullnessOnTheQualifierInADeclaration;
              List<@Nullable Chain.@Tq B.@Nullable C> bothArms;
            }
            """)
        .addOutputLines(
            "test/Chain.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Chain {
              class B {
                class C {}
              }
              List<Chain.@Tq B.@Nullable C> nested;
              Chain.@Tq B.@Nullable C declaration;
              List<Chain.B.@Nullable C> nullnessOnTheQualifier;
              List<Chain.B.@NonNull C> differentNullnessOnTheQualifier;
              Chain.B.@Nullable C nullnessOnTheQualifierInADeclaration;
              List<Chain.@Tq B.@Nullable C> bothArms;
            }
            """)
        .doTest();
  }

  @Test
  public void fixRemovesTheAnnotationWhereTheDestinationCarriesOneAlready() {
    refactoringHelper
        .addInputLines(
            "test/Occupied.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Occupied {
              class Inner {}
              List<@Nullable ? extends @Nullable String> annotatedBound;
              List<@Nullable ? extends @NonNull String> differentAnnotationOnBound;
              List<@Nullable ? extends String @NonNull []> occupiedArrayBound;
              List<@Nullable ? extends @Nullable Occupied.Inner> annotatedInnerBound;
              List<@Nullable ? extends @NonNull Occupied.Inner> annotatedInnerQualifier;
              <@Nullable T extends @Nullable String> void typeParameter() {}
              @Nullable Occupied.@Nullable Inner innerType;
              List<@Nullable Occupied.@Nullable Inner> nestedInnerType;
            }
            """)
        .addOutputLines(
            "test/Occupied.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Occupied {
              class Inner {}
              List<? extends @Nullable String> annotatedBound;
              List<? extends @NonNull String> differentAnnotationOnBound;
              List<? extends String @NonNull []> occupiedArrayBound;
              List<? extends Occupied.@Nullable Inner> annotatedInnerBound;
              List<? extends Occupied.@NonNull Inner> annotatedInnerQualifier;
              <T extends @Nullable String> void typeParameter() {}
              Occupied.@Nullable Inner innerType;
              List<Occupied.@Nullable Inner> nestedInnerType;
            }
            """)
        .doTest();
  }

  @Test
  public void fixStillMovesWhereTheExistingAnnotationSitsElsewhere() {
    refactoringHelper
        .addInputLines(
            "test/Elsewhere.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Elsewhere {
              class Inner {}
              List<@Nullable ? extends List<@Nullable String>> annotatedTypeArgument;
              @Nullable Elsewhere.Inner @NonNull [] annotatedArrayOfInner;
            }
            """)
        .addOutputLines(
            "test/Elsewhere.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Elsewhere {
              class Inner {}
              List<? extends @Nullable List<@Nullable String>> annotatedTypeArgument;
              Elsewhere.@Nullable Inner @NonNull [] annotatedArrayOfInner;
            }
            """)
        .doTest();
  }

  @Test
  public void fixDeletesWhereNoRecognizedLocationExists() {
    // A lower-bounded wildcard has no upper bound to write the annotation on, and two annotations
    // on one anchor make the move ambiguous.  In both, the fix only deletes the annotation.
    refactoringHelper
        .addInputLines(
            "test/Ambiguous.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Ambiguous {
              List<@Nullable ? super String> lowerBounded;
              List<@Nullable @NonNull ?> twoAnnotations;
            }
            """)
        .addOutputLines(
            "test/Ambiguous.java",
            """
            package test;
            import java.util.List;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Ambiguous {
              List<? super String> lowerBounded;
              List<?> twoAnnotations;
            }
            """)
        .doTest();
  }

  @Test
  public void fixLeavesNoBlankBehindTheAnnotation() {
    // A blank left where the annotation stood survives -XepPatchChecks into the working tree, where
    // a style checker rejects `( T)` and rejects the column of indentation a line gains when it
    // began with the annotation.  Neither refactoring mode shows the blank: TEXT_MATCH formats
    // both sides with google-java-format, and AST_MATCH compares parsed trees.  The fix text is
    // read out of the diagnostic instead, and the diagnostic renderer trims the line it quotes, so
    // it cannot show a blank in front of the annotation; each assertion below is the same
    // deletion, seen where the blank falls inside the line instead.
    ImmutableList<String> messages =
        diagnostics(
            "test/Blanks.java",
            "package test;",
            "import java.util.List;",
            "import org.jspecify.annotations.Nullable;",
            "class Blanks {",
            "  void parameter(@Nullable int value) {}",
            "  List<@Nullable ?> wildcard;",
            "  void body(Object o) {",
            "    Object cast = (@Nullable String) o;",
            "  }",
            "}");
    assertThat(messages.get(0)).contains("void parameter(int value) {}");
    assertThat(messages.get(1)).contains("List<? extends @Nullable Object> wildcard;");
    assertThat(messages.get(2)).contains("Object cast = (String) o;");
  }

  @Test
  public void fixOutputIsNotReportedAgain() {
    // A move fix must yield source the check does not report, so every move fix's output belongs in
    // this fixture.  A fix that relocates an annotation into another unrecognized location fails
    // here.
    compilationHelper
        .addSourceLines(
            "test/Outputs.java",
            """
            package test;
            import java.io.Serializable;
            import java.util.List;
            import java.util.Map;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            class Outputs {
              class Inner { class Innermost {} }
              class Generic<X> { class Deep {} }
              Outputs.@Nullable Inner field;
              Outputs.@Nullable Inner[] array;
              Outputs.@Nullable Inner[][] twoDimensions;
              List<Outputs.@Nullable Inner> typeArgument;
              List<Outputs.Inner.@Nullable Innermost> chain;
              List<Outputs.Generic<String>.@Nullable Deep> parameterizedChain;
              List<? extends @Nullable Object> unboundedWildcard;
              List<? extends @Nullable String> boundedWildcard;
              List<? extends Outputs.@Nullable Inner> innerTypeWildcard;
              List<? extends Map.@Nullable Entry<String, String>> staticNestedWildcard;
              List<? extends String @Nullable []> arrayWildcard;
              List<? extends String @Nullable [][]> twoDimensionArrayWildcard;
              List<? extends String @NonNull []> alreadyAnnotatedArrayWildcard;
              <T extends @Nullable Object> void unbounded() {}
              <T extends @Nullable Number> void bounded() {}
              <T extends Number & Serializable> void intersection() {}
              <T extends Outputs.@Nullable Inner> void innerTypeBound() {}
              <T extends Map.@Nullable Entry<String, String>> void staticNestedBound() {}
              <T extends java.util.@Nullable Locale> void packageQualifiedBound() {}
              void locals() {
                Outputs.@Nullable Inner[] arrayLocal = null;
              }
            }
            """)
        .doTest();
  }

  /**
   * Returns the check's diagnostics for {@code lines}, one string each.
   *
   * <p>Each string is one diagnostic's line number, a colon, then its message. The strings arrive
   * in the order javac reported the diagnostics. Fails the test when {@code lines} does not
   * compile.
   *
   * <p>{@link CompilationTestHelper} matches each {@code // BUG:} pattern against the diagnostics
   * on its line with a {@code hasItem} matcher, so a construct reported twice looks exactly like
   * one reported once. To test that a construct is reported exactly once, count the diagnostics.
   */
  private static ImmutableList<String> diagnostics(String path, String... lines) {
    DiagnosticTestHelper diagnosticHelper = new DiagnosticTestHelper();
    boolean compiled =
        new BaseErrorProneJavaCompiler(
                ScannerSupplier.fromBugCheckerClasses(JSpecifyUnrecognizedAnnotationLocation.class))
            .getTask(
                new StringWriter(),
                FileManagers.testFileManager(),
                diagnosticHelper.collector,
                ImmutableList.of(
                    "-encoding",
                    "UTF-8",
                    "-XDcompilePolicy=simple",
                    "-XDaddTypeAnnotationsToSymbol=true",
                    "-proc:none",
                    WARN),
                ImmutableList.of(),
                ImmutableList.of(FileObjects.forSourceLines(path, lines)))
            .call();
    ImmutableList.Builder<String> messages = ImmutableList.builder();
    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnosticHelper.getDiagnostics()) {
      messages.add(diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ENGLISH));
    }
    ImmutableList<String> built = messages.build();
    // getDiagnostics() returns errors as well as warnings, so the returned list holds only the
    // check's reports when the snippet compiled.
    assertWithMessage("%s did not compile: %s", path, built).that(compiled).isTrue();
    return built;
  }

  /**
   * Runs NullAway and this check together over {@code source}, both at warning level, as a user who
   * has turned both on would.
   *
   * <p>NullAway checks package {@code test} only, so declare {@code package test;} in {@code
   * source}.
   */
  private void bothChecksOn(String source) {
    CompilationTestHelper.newInstance(
            ScannerSupplier.fromBugCheckerClasses(
                NullAway.class, JSpecifyUnrecognizedAnnotationLocation.class),
            getClass())
        .setArgs("-Xep:NullAway:WARN", WARN, "-XepOpt:NullAway:AnnotatedPackages=test")
        .addSourceLines("test/Both.java", source)
        .doTest();
  }
}
