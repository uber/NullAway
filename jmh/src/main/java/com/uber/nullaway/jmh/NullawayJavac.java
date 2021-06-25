package com.uber.nullaway.jmh;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Code to run Javac with NullAway enabled. */
public class NullawayJavac {

  private static int getJavaRuntimeVersion() {
    String version = System.getProperty("java.version");
    if (version.startsWith("1.")) {
      version = version.substring(2, 3);
    } else {
      int dot = version.indexOf(".");
      if (dot != -1) {
        version = version.substring(0, dot);
      }
    }
    return Integer.parseInt(version);
  }

  public static void main(String[] args) throws Exception {
    // Check at runtime for appropriate Java version.  For convenience we keep the code building
    // with Java 8.
    if (getJavaRuntimeVersion() < 11) {
      throw new RuntimeException(
          "Must be run on JDK 11 or greater; version is " + System.getProperty("java.version"));
    }
    NullawayJavac nb = new NullawayJavac();
    nb.prepareForSimpleTest();
    for (int i = 0; i < 5; i++) {
      System.out.println(i);
      nb.testCompile();
    }
    System.out.println("SUCCESS");
  }

  private List<JavaFileObject> compilationUnits;
  private JavaCompiler compiler;
  private DiagnosticListener<JavaFileObject> diagnosticListener;
  private StandardJavaFileManager fileManager;
  private List<String> options;

  public void prepareForSimpleTest() throws IOException {
    String testClass =
        "package com.uber;\n"
            + "import java.util.*;\n"
            + "class Test {   \n"
            + "  public static void main(String args[]) {\n"
            + "    Set<Short> s = null;\n"
            + "    for (short i = 0; i < 100; i++) {\n"
            + "      s.add(i);\n"
            + "      s.remove(i - 1);\n"
            + "    }\n"
            + "    System.out.println(s.size());"
            + "  }\n"
            + "}\n";
    compilationUnits = Collections.singletonList(new JavaSourceFromString("Test", testClass));
    finishSetup("com.uber");
  }

  public void prepare(List<String> sourceFileNames, String annotatedPackages) throws IOException {
    compilationUnits = new ArrayList<>();
    for (String sourceFileName : sourceFileNames) {
      String content = readFile(sourceFileName);
      String classname =
          sourceFileName.substring(
              sourceFileName.lastIndexOf(File.separatorChar) + 1, sourceFileName.indexOf(".java"));
      compilationUnits.add(new JavaSourceFromString(classname, content));
    }

    finishSetup(annotatedPackages);
  }

  /**
   * Finishes setup of options and state for running javac, assuming that {@link #compilationUnits}
   * has already been set up.
   *
   * <p>To pass the appropriate {@code -processorpath} argument to the spawned javac, we make this
   * project depend on NullAway and Error Prone Core, and then pass our own classpath as the
   * processorpath. Note that this makes (dependencies of) NullAway and Error Prone visible on the
   * <emph>classpath</emph> for the spawned javac instance as well. So, if a benchmark depends on
   * some library that NullAway depends on (e.g., Guava), the dependence will be magically
   * satisfied. Note that this could lead to problems for benchmarks that depend on a conflicting
   * version of a library.
   *
   * @param annotatedPackages argument to pass for "-XepOpt:NullAway:AnnotatedPackages" option
   * @throws IOException if a temporary output directory cannot be created
   */
  private void finishSetup(String annotatedPackages) throws IOException {
    compiler = ToolProvider.getSystemJavaCompiler();
    diagnosticListener =
        diagnostic -> {
          // do nothing
        };
    // uncomment this if you want to see compile errors get printed out
    // diagnosticListener = null;
    fileManager = compiler.getStandardFileManager(diagnosticListener, null, null);
    Path outputDir = Files.createTempDirectory("classes");
    outputDir.toFile().deleteOnExit();
    // TODO support passing additional benchmark dependencies as the -classpath argument
    options =
        Arrays.asList(
            "-processorpath",
            System.getProperty("java.class.path"),
            "-d",
            outputDir.toAbsolutePath().toString(),
            "-XDcompilePolicy=simple",
            "-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages="
                + annotatedPackages);
  }

  public Boolean testCompile() {
    JavaCompiler.CompilationTask task =
        compiler.getTask(null, fileManager, diagnosticListener, options, null, compilationUnits);
    return task.call();
  }

  static String readFile(String path) throws IOException {
    byte[] encoded = Files.readAllBytes(Paths.get(path));
    return new String(encoded, StandardCharsets.UTF_8);
  }

  /**
   * This class allows code to be generated directly from a String, instead of having to be on disk.
   *
   * <p>Based on code in Apache Pig; see <a
   * href="https://github.com/apache/pig/blob/59ec4a326079c9f937a052194405415b1e3a2b06/src/org/apache/pig/impl/util/JavaCompilerHelper.java#L42-L58">here</a>.
   */
  private static class JavaSourceFromString extends SimpleJavaFileObject {
    final String code;

    JavaSourceFromString(String name, String code) {
      super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
      this.code = code;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return code;
    }
  }
}
