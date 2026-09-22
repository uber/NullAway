package com.uber.nullaway.generics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.source.util.JavacTask;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class JSpecifyJavacConfigTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  /** A library class whose return type carries a type-use annotation inside a generic. */
  private static final String LIB_SOURCE =
      """
      package t;
      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;
      import java.util.List;
      public class Lib {
        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE_USE)
        public @interface Nullable {}
        public static List<@Nullable String> f() { return List.of(); }
      }
      """;

  /** A trivial compilation unit, so that the second javac invocation has something to compile. */
  private static final String USE_SOURCE =
      """
      package u;
      class Use {}
      """;

  /**
   * Checks that NullAway's notion of "javac puts type annotations on symbols" matches what the
   * running javac does. Vendors number their builds differently, so a version range is not a sound
   * way to state this expectation: Oracle JDK 21.0.12, for one, lacks the support that 21.0.8
   * introduced elsewhere.
   */
  @Test
  public void detectionAgreesWithJavacBehavior() throws Exception {
    // JDK 22+ always puts type annotations on symbols; below that, NullAway probes for the flag
    boolean expected =
        Runtime.version().feature() >= 22
            || JSpecifyJavacConfig.javacOnJDK17Or21SupportsAddTypeAnnotationsToSymbol();
    assertEquals(
        expected,
        typeAnnotationSurvivesBytecodeRead(),
        "NullAway's detection disagrees with javac "
            + Runtime.version()
            + ". If ClassReader.addTypeAnnotationsToSymbol was renamed,"
            + " javacOnJDK17Or21SupportsAddTypeAnnotationsToSymbol reports no support and NullAway"
            + " rejects JSpecify mode on a JDK that in fact supports it.");
  }

  /**
   * Compiles {@link #LIB_SOURCE}, reads the resulting class file back under {@code
   * -XDaddTypeAnnotationsToSymbol=true}, and reports whether javac kept the type-use annotation on
   * the symbol's type.
   *
   * @return true if the annotation is visible on the type read from bytecode
   */
  private boolean typeAnnotationSurvivesBytecodeRead() throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    File srcDir = temporaryFolder.newFolder("src");
    File libClasses = temporaryFolder.newFolder("libClasses");
    File useClasses = temporaryFolder.newFolder("useClasses");
    File libSrc = writeSource(srcDir, "Lib.java", LIB_SOURCE);
    File useSrc = writeSource(srcDir, "Use.java", USE_SOURCE);

    try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
      boolean compiled =
          compiler
              .getTask(
                  null,
                  fm,
                  null,
                  List.of("-d", libClasses.getAbsolutePath(), "-proc:none"),
                  null,
                  fm.getJavaFileObjects(libSrc))
              .call();
      assertTrue(compiled, "the library class must compile");

      JavacTask task =
          (JavacTask)
              compiler.getTask(
                  null,
                  fm,
                  null,
                  List.of(
                      "-d",
                      useClasses.getAbsolutePath(),
                      "-classpath",
                      libClasses.getAbsolutePath(),
                      "-proc:none",
                      JSpecifyJavacConfig.ADD_TYPE_ANNOTATIONS_FLAG),
                  null,
                  fm.getJavaFileObjects(useSrc));
      task.parse();
      task.analyze();

      TypeElement lib = task.getElements().getTypeElement("t.Lib");
      assertNotNull(lib, "t.Lib must be read from bytecode");
      TypeMirror typeArg =
          ((DeclaredType) findMethod(lib, "f").getReturnType()).getTypeArguments().get(0);
      return !typeArg.getAnnotationMirrors().isEmpty();
    }
  }

  /**
   * Writes {@code source} to a file named {@code fileName} under {@code dir}.
   *
   * @return the file that was written
   */
  private static File writeSource(File dir, String fileName, String source) throws Exception {
    File file = new File(dir, fileName);
    Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));
    return file;
  }

  /**
   * Returns the method named {@code name} declared by {@code type}.
   *
   * @throws AssertionError if {@code type} declares no such method
   */
  private static ExecutableElement findMethod(TypeElement type, String name) {
    for (Element e : type.getEnclosedElements()) {
      if (e.getKind() == ElementKind.METHOD && e.getSimpleName().contentEquals(name)) {
        return (ExecutableElement) e;
      }
    }
    throw new AssertionError("no method " + name + " on " + type);
  }
}
