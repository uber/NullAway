/*
 * Copyright (c) 2021 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

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
import javax.annotation.Nullable;
import javax.tools.DiagnosticListener;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Code to run Javac with NullAway enabled, designed to aid benchmarking. Construction of {@code
 * NullawayJavac} objects performs one-time operations whose cost we do not care to benchmark, so
 * that {@link #compile()} can be run repeatedly to measure performance in the steady state.
 */
public class NullawayJavac {

  //////////////////////
  // state required to run javac via the standard APIs
  //////////////////////
  private List<JavaFileObject> compilationUnits;
  private JavaCompiler compiler;
  @Nullable private DiagnosticListener<JavaFileObject> diagnosticListener;
  private StandardJavaFileManager fileManager;
  private List<String> options;

  /**
   * Sets up compilation for a simple single source file, for testing / sanity checking purposes.
   * Running {@link #compile()} on the resulting object will return {@code false}, as the sample
   * input source has NullAway errors.
   *
   * @throws IOException if output temporary directory cannot be created
   */
  public static NullawayJavac createSimpleTest() throws IOException {
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
    return new NullawayJavac(
        Collections.singletonList(new JavaSourceFromString("Test", testClass)),
        "com.uber",
        null,
        Collections.emptyList(),
        "");
  }

  /**
   * Creates a NullawayJavac object to compile a set of source files.
   *
   * @param sourceFileNames absolute paths to the source files to be compiled
   * @param annotatedPackages argument to pass for "-XepOpt:NullAway:AnnotatedPackages" option
   * @param classpath classpath for the benchmark
   * @param extraErrorProneArgs extra arguments to pass to Error Prone
   * @param extraProcessorPath additional elements to concatenate to the processor path
   * @throws IOException if a temporary output directory cannot be created
   */
  public static NullawayJavac create(
      List<String> sourceFileNames,
      String annotatedPackages,
      String classpath,
      List<String> extraErrorProneArgs,
      String extraProcessorPath)
      throws IOException {
    List<JavaFileObject> compilationUnits = new ArrayList<>();
    for (String sourceFileName : sourceFileNames) {
      // we read every source file into memory in the prepare phase, to avoid some I/O during
      // compilations
      String content = readFile(sourceFileName);
      String classname =
          sourceFileName.substring(
              sourceFileName.lastIndexOf(File.separatorChar) + 1, sourceFileName.indexOf(".java"));
      compilationUnits.add(new JavaSourceFromString(classname, content));
    }

    return new NullawayJavac(
        compilationUnits, annotatedPackages, classpath, extraErrorProneArgs, extraProcessorPath);
  }

  /**
   * Create a NullawayJavac object to compile a single source file given as a String. This only
   * supports cases where no additional classpath, Error Prone arguments, or processor path
   * arguments need to be specified.
   *
   * @param className name of the class to compile
   * @param source source code of the class to compile
   * @param annotatedPackages argument to pass for "-XepOpt:NullAway:AnnotatedPackages" option
   * @throws IOException if a temporary output directory cannot be created
   */
  public static NullawayJavac createFromSourceString(
      String className, String source, String annotatedPackages) throws IOException {
    return new NullawayJavac(
        Collections.singletonList(new JavaSourceFromString(className, source)),
        annotatedPackages,
        null,
        Collections.emptyList(),
        "");
  }

  /**
   * Configures compilation with javac and NullAway.
   *
   * <p>To pass NullAway in the {@code -processorpath} argument to the spawned javac and ensure it
   * gets JIT-compiled during benchmarking, we make this project depend on NullAway and Error Prone
   * Core, and then pass our own classpath as the processorpath. Note that this makes (dependencies
   * of) NullAway and Error Prone visible on the <emph>classpath</emph> for the spawned javac
   * instance as well. Note that this could lead to problems for benchmarks that depend on a
   * conflicting version of a library that NullAway depends on.
   *
   * @param compilationUnits input sources to be compiled
   * @param annotatedPackages argument to pass for "-XepOpt:NullAway:AnnotatedPackages" option
   * @param classpath classpath for the program to be compiled
   * @param extraErrorProneArgs additional arguments to pass to Error Prone
   * @param extraProcessorPath additional elements to concatenate to the processor path
   * @throws IOException if a temporary output directory cannot be created
   */
  private NullawayJavac(
      List<JavaFileObject> compilationUnits,
      String annotatedPackages,
      @Nullable String classpath,
      List<String> extraErrorProneArgs,
      String extraProcessorPath)
      throws IOException {
    this.compilationUnits = compilationUnits;
    this.compiler = ToolProvider.getSystemJavaCompiler();
    this.diagnosticListener =
        diagnostic -> {
          // do nothing
        };
    // uncomment this if you want to see compile errors get printed out
    // this.diagnosticListener = null;
    this.fileManager = compiler.getStandardFileManager(diagnosticListener, null, null);
    Path outputDir = Files.createTempDirectory("classes");
    outputDir.toFile().deleteOnExit();
    this.options = new ArrayList<>();
    if (classpath != null) {
      options.addAll(Arrays.asList("-classpath", classpath));
    }
    String processorPath =
        System.getProperty("java.class.path") + File.pathSeparator + extraProcessorPath;
    options.addAll(
        Arrays.asList(
            "-processorpath",
            processorPath,
            "-d",
            outputDir.toAbsolutePath().toString(),
            "-XDcompilePolicy=simple",
            "-Xplugin:ErrorProne -XepDisableAllChecks -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages="
                + annotatedPackages
                + String.join(" ", extraErrorProneArgs)));
    // add these options since we have at least one benchmark that only compiles with access to
    // javac-internal APIs
    options.addAll(
        Arrays.asList(
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.source.tree=ALL-UNNAMED"));
  }

  /**
   * Runs the compilation.
   *
   * @return true if the input files compile without error; false otherwise
   */
  public boolean compile() {
    JavaCompiler.CompilationTask task =
        compiler.getTask(null, fileManager, diagnosticListener, options, null, compilationUnits);
    return task.call();
  }

  private static String readFile(String path) throws IOException {
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
