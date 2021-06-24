package com.uber.nullaway.jmh;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
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
    nb.prepare();
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

  public void prepare() {
    //    String testClass =
    //        "package com.uber;\n"
    //            + "import java.util.*;\n"
    //            + "class Test {   \n"
    //            + "  public static void main(String args[]) {\n"
    //            + "    Set<Short> s = null;\n"
    //            + "    for (short i = 0; i < 100; i++) {\n"
    //            + "      s.add(i);\n"
    //            + "      s.remove(i - 1);\n"
    //            + "    }\n"
    //            + "    System.out.println(s.size());"
    //            + "  }\n"
    //            + "}\n";

    compiler = ToolProvider.getSystemJavaCompiler();
    diagnosticListener =
        diagnostic -> {
          // do nothing
        };
    // diagnosticListener = null;
    fileManager = compiler.getStandardFileManager(diagnosticListener, null, null);
    compilationUnits = new ArrayList<>();
    // compilationUnits.add(new JavaSourceFromString("Test", testClass));
    Iterable<? extends JavaFileObject> javaFileObjects =
        fileManager.getJavaFileObjects(
            "/Users/msridhar/git-repos/NullAway/nullaway/src/test/resources/com/uber/nullaway/testdata/NullAwayPositiveCases.java");
    for (JavaFileObject f : javaFileObjects) {
      compilationUnits.add(f);
    }
    options =
        Arrays.asList(
            "-processorpath",
            System.getProperty("java.class.path"),
            "-XDcompilePolicy=simple",
            "-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages=com.uber");
  }

  public Boolean testCompile() {
    JavaCompiler.CompilationTask task =
        compiler.getTask(null, fileManager, diagnosticListener, options, null, compilationUnits);
    return task.call();
  }

  /**
   * This class allows code to be generated directly from a String, instead of having to be on disk.
   *
   * <p>Based on code in Apache Pig; see <a
   * href="https://github.com/apache/pig/blob/59ec4a326079c9f937a052194405415b1e3a2b06/src/org/apache/pig/impl/util/JavaCompilerHelper.java#L42-L58">here</a>.
   */
  //  private static class JavaSourceFromString extends SimpleJavaFileObject {
  //    final String code;
  //
  //    JavaSourceFromString(String name, String code) {
  //      super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension),
  // Kind.SOURCE);
  //      this.code = code;
  //    }
  //
  //    @Override
  //    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
  //      return code;
  //    }
  //  }
}
