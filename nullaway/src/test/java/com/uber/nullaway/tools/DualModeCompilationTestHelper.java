/*
 * Copyright (c) 2026 Uber Technologies, Inc.
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

package com.uber.nullaway.tools;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assume.assumeTrue;

import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.bugpatterns.BugChecker;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.AssumptionViolatedException;

/**
 * Runs a NullAway test snippet either as plain source code or with part of it moved to the
 * classpath, depending on the {@link TestMode} it is created with.
 *
 * <p>The helper records the sources and the javac arguments a test declares and replays them
 * through Error Prone's {@link CompilationTestHelper} when {@link #doTest()} runs. In {@link
 * TestMode#SOURCE} the replay is exactly what the test would have done on its own. In {@link
 * TestMode#BYTECODE} some of the source files are compiled to class files first and handed to
 * NullAway on the classpath, which exercises the class-file reading path against a snippet written
 * for the source path.
 *
 * <p>Every file carrying an {@code // BUG: Diagnostic contains:} marker stays source, since only a
 * file NullAway analyzes can produce the diagnostics the test expects, and the files that carry no
 * marker move to the classpath. Bytecode mode therefore needs at least one file with no marker and
 * at least one file left to analyze, which a single-file snippet — the commonest shape in the suite
 * — never has, and neither has a snippet whose every file is marked. The helper compiles as many of
 * the eligible files as javac accepts, dropping the ones it reports errors on and retrying with the
 * rest, and keeps the largest set that compiles. With nothing marked it weighs all but the last
 * file against all but the first, since either end of the snippet may be the file under test.
 * {@link #doTest()} skips the test through a JUnit assumption whenever no split compiles, so use
 * {@link SkipBytecodeTestMode} only for a test that must not run in bytecode mode at all.
 */
public final class DualModeCompilationTestHelper {

  private static final List<String> BUG_MARKERS =
      List.of("// BUG: Diagnostic contains:", "// BUG: Diagnostic matches:");

  private static final List<String> CLASSPATH_OPTIONS =
      List.of("-classpath", "-cp", "--class-path");

  private static final String CLASS_PATH_PREFIX = "--class-path=";

  private final Class<? extends BugChecker> checker;
  private final Class<?> resourceClass;
  private final TestMode testMode;
  private final List<TestSource> sources = new ArrayList<>();
  private List<String> args = List.of();

  private DualModeCompilationTestHelper(
      Class<? extends BugChecker> checker, Class<?> resourceClass, TestMode testMode) {
    this.checker = checker;
    this.resourceClass = resourceClass;
    this.testMode = testMode;
  }

  /**
   * Returns a helper for the given check.
   *
   * @param checker the {@link BugChecker} to test
   * @param resourceClass the class used to locate the resources of {@link #addSourceFile}
   * @param testMode whether the snippet runs as source or partly as class files
   * @return the test helper
   */
  public static DualModeCompilationTestHelper newInstance(
      Class<? extends BugChecker> checker, Class<?> resourceClass, TestMode testMode) {
    return new DualModeCompilationTestHelper(checker, resourceClass, testMode);
  }

  /**
   * Adds a source file to the test compilation, from the string content of the file.
   *
   * @param path a path for the source file
   * @param lines the content of the source file
   * @return this helper
   */
  public DualModeCompilationTestHelper addSourceLines(String path, String... lines) {
    sources.add(new SourceLines(path, lines));
    return this;
  }

  /**
   * Adds a source file to the test compilation, from an existing resource file.
   *
   * @param path the resource path of the source file
   * @return this helper
   */
  public DualModeCompilationTestHelper addSourceFile(String path) {
    sources.add(new ResourceFile(resourceClass, path));
    return this;
  }

  /**
   * Sets the javac arguments for the compilation, which may be given only once.
   *
   * @param args the javac arguments
   * @return this helper
   */
  public DualModeCompilationTestHelper setArgs(List<String> args) {
    Preconditions.checkState(this.args.isEmpty(), "Args already set: %s", this.args);
    this.args = List.copyOf(args);
    return this;
  }

  /** Compiles the snippet and checks that the diagnostics match the expectations. */
  public void doTest() {
    if (testMode == TestMode.SOURCE) {
      errorProneHelper(sources, args).doTest();
      return;
    }
    List<List<TestSource>> candidates = candidateDependencies();
    assumeTrue(
        "bytecode mode needs a source file that carries no expected diagnostics",
        !candidates.isEmpty());
    Path workingDir = createWorkingDirectory();
    try {
      List<String> errors = List.of();
      List<TestSource> best = List.of();
      Path bestClasses = workingDir;
      for (int i = 0; i < candidates.size(); i++) {
        List<TestSource> candidate = candidates.get(i);
        Attempt attempt = shrinkUntilItCompiles(candidate, workingDir, i);
        if (errors.isEmpty()) {
          errors = attempt.errors;
        }
        if (attempt.dependencies.isEmpty()) {
          continue;
        }
        if (attempt.dependencies.size() == candidate.size()) {
          // A candidate that compiled unshrunk is a whole starting set, and no result is larger
          // than a starting set, so no later candidate can beat it.
          errorProneHelper(analyzed(attempt.dependencies), argsWithClasspath(attempt.classes))
              .doTest();
          return;
        }
        // What one result has over another is subset inclusion: everything the smaller reads from a
        // class file the larger reads from one too. Size linearizes that order, and declaration
        // order breaks a tie between results the order cannot compare.
        if (attempt.dependencies.size() > best.size()) {
          best = attempt.dependencies;
          bestClasses = attempt.classes;
        }
      }
      if (!best.isEmpty()) {
        errorProneHelper(analyzed(best), argsWithClasspath(bestClasses)).doTest();
        return;
      }
      throw new AssumptionViolatedException(
          "the sources moved to the classpath do not compile on their own: " + errors);
    } finally {
      deleteRecursively(workingDir);
    }
  }

  /**
   * Compiles a candidate dependency set, dropping the sources javac reported errors on and retrying
   * until what is left compiles or nothing is left.
   *
   * <p>Removing a source can only remove symbols, so a source that fails in one set fails in every
   * subset containing it. The loop therefore settles on the largest subset of the candidate that
   * compiles, rather than on an arbitrary one — except where javac reports an error it names no
   * source for, which leaves nothing to drop and gives up on a candidate a smaller subset of which
   * might have compiled.
   *
   * @param candidate the sources to start from
   * @param workingDir the directory holding this run's sources and class files
   * @param index which candidate this is, used to keep the attempts in separate directories
   * @return what compiled, with an empty dependency list when nothing did
   */
  private Attempt shrinkUntilItCompiles(List<TestSource> candidate, Path workingDir, int index) {
    List<TestSource> dependencies = candidate;
    List<String> errors = List.of();
    Path classes = workingDir;
    for (int step = 0; !dependencies.isEmpty(); step++) {
      classes = workingDir.resolve("classes" + index + "-" + step);
      CompileResult result =
          compileToClassFiles(
              dependencies, workingDir.resolve("src" + index + "-" + step), classes);
      if (result.errors.isEmpty()) {
        return new Attempt(dependencies, classes, errors);
      }
      if (errors.isEmpty()) {
        errors = result.errors;
      }
      // Everything failed, so by the same monotonicity no subset compiles; or javac named no
      // source, which leaves nothing to drop and is where this gives up early.
      if (result.failed.isEmpty() || result.failed.size() == dependencies.size()) {
        break;
      }
      List<TestSource> failed = result.failed;
      dependencies =
          dependencies.stream()
              .filter(source -> !failed.contains(source))
              .collect(Collectors.toList());
    }
    return new Attempt(List.of(), classes, errors);
  }

  /** What one candidate dependency set settled on. */
  private static final class Attempt {
    private final List<TestSource> dependencies;
    private final Path classes;
    private final List<String> errors;

    Attempt(List<TestSource> dependencies, Path classes, List<String> errors) {
      this.dependencies = dependencies;
      this.classes = classes;
      this.errors = errors;
    }
  }

  /**
   * Returns the dependency sets bytecode mode may start from, most thorough first. The list is
   * empty for a snippet that cannot be split at all, such as a single-file one.
   *
   * @return the candidate dependency sets
   */
  private List<List<TestSource>> candidateDependencies() {
    List<TestSource> unmarked =
        sources.stream().filter(source -> !hasBugMarker(source)).collect(Collectors.toList());
    if (unmarked.size() < sources.size()) {
      // Some file is marked, so every unmarked file may become a class file.
      return unmarked.isEmpty() ? List.of() : List.of(unmarked);
    }
    if (sources.size() < 2) {
      return List.of();
    }
    // With nothing marked, either end of the snippet may be the file under test. Shrinking cannot
    // reach the second candidate from the first, since it moves a different file into the analyzed
    // half.
    return List.of(sources.subList(0, sources.size() - 1), sources.subList(1, sources.size()));
  }

  /**
   * Returns the sources NullAway analyzes, which are the ones that did not become class files.
   *
   * @param dependencies the sources compiled to class files
   * @return the sources to analyze, in declaration order
   */
  private List<TestSource> analyzed(List<TestSource> dependencies) {
    return sources.stream()
        .filter(source -> !dependencies.contains(source))
        .collect(Collectors.toList());
  }

  private static boolean hasBugMarker(TestSource source) {
    String content = source.content();
    return BUG_MARKERS.stream().anyMatch(content::contains);
  }

  /**
   * Builds an Error Prone test helper for the given sources and arguments.
   *
   * @param sources the sources NullAway analyzes
   * @param args the javac arguments
   * @return the test helper
   */
  @SuppressWarnings("CheckReturnValue")
  private CompilationTestHelper errorProneHelper(List<TestSource> sources, List<String> args) {
    CompilationTestHelper helper = CompilationTestHelper.newInstance(checker, resourceClass);
    if (!args.isEmpty()) {
      helper = helper.setArgs(args);
    }
    for (TestSource source : sources) {
      helper = source.addTo(helper);
    }
    return helper;
  }

  /**
   * Compiles the given sources to class files with plain javac.
   *
   * @param dependencies the sources to compile
   * @param sourceDir the directory the sources are written to
   * @param classes the directory the class files are written to
   * @return the compilation errors and the sources they were reported on
   */
  private CompileResult compileToClassFiles(
      List<TestSource> dependencies, Path sourceDir, Path classes) {
    List<Path> files = new ArrayList<>();
    try {
      Files.createDirectories(classes);
      for (TestSource source : dependencies) {
        Path file = sourceDir.resolve(source.path());
        Files.createDirectories(file.getParent());
        Files.writeString(file, source.content(), UTF_8);
        files.add(file);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    JavaCompiler javac =
        Preconditions.checkNotNull(
            ToolProvider.getSystemJavaCompiler(),
            "no system Java compiler; tests must run on a JDK");
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    boolean compiled;
    // The file objects are paired with the sources that produced them, so a diagnostic names a
    // TestSource without any matching on file names.
    IdentityHashMap<JavaFileObject, TestSource> sourceOf = new IdentityHashMap<>();
    Map<URI, TestSource> sourceOfUri = new LinkedHashMap<>();
    try (StandardJavaFileManager fileManager =
        javac.getStandardFileManager(diagnostics, null, UTF_8)) {
      List<JavaFileObject> fileObjects = new ArrayList<>();
      fileManager.getJavaFileObjectsFromPaths(files).forEach(fileObjects::add);
      for (int i = 0; i < fileObjects.size(); i++) {
        sourceOf.put(fileObjects.get(i), dependencies.get(i));
        sourceOfUri.put(fileObjects.get(i).toUri(), dependencies.get(i));
      }
      compiled =
          javac
              .getTask(null, fileManager, diagnostics, javacOptions(classes), null, fileObjects)
              .call();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<Diagnostic<? extends JavaFileObject>> errorDiagnostics =
        diagnostics.getDiagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .collect(Collectors.toList());
    List<String> errors =
        errorDiagnostics.stream().map(d -> d.getMessage(Locale.ROOT)).collect(Collectors.toList());
    if (!compiled && errors.isEmpty()) {
      // Reporting this as an error would send it down the retry-then-skip path, where a broken
      // compiler reads as a snippet that cannot be split.
      throw new AssertionError(
          "javac failed without reporting an error; options=" + javacOptions(classes));
    }
    List<TestSource> failed = new ArrayList<>();
    for (Diagnostic<? extends JavaFileObject> diagnostic : errorDiagnostics) {
      JavaFileObject file = diagnostic.getSource();
      if (file == null) {
        continue;
      }
      TestSource source = sourceOf.get(file);
      if (source == null) {
        source = sourceOfUri.get(file.toUri());
      }
      if (source != null && !failed.contains(source)) {
        failed.add(source);
      }
    }
    return new CompileResult(errors, failed);
  }

  /** What one javac run over a candidate dependency set reported. */
  private static final class CompileResult {
    private final List<String> errors;
    private final List<TestSource> failed;

    CompileResult(List<String> errors, List<TestSource> failed) {
      this.errors = errors;
      this.failed = failed;
    }
  }

  /**
   * Returns the javac arguments for the sources that become class files. Error Prone arguments are
   * dropped, since no check runs on those sources, and the classpath is stated once so that these
   * sources see what the test declared rather than what javac happened to read last.
   *
   * @param classes the directory the class files are written to
   * @return the javac arguments
   */
  List<String> javacOptions(Path classes) {
    List<String> options =
        new ArrayList<>(
            List.of(
                "-encoding",
                "UTF-8",
                "-parameters",
                "-d",
                classes.toString(),
                "-classpath",
                declaredClasspath()));
    List<String> declared = javacArgs();
    options.addAll(declared);
    if (declared.stream().noneMatch(arg -> arg.startsWith("-proc") || arg.equals("-processor"))) {
      options.add("-proc:none");
    }
    return options;
  }

  /** Returns the arguments the test declared, without the Error Prone and output-directory ones. */
  private List<String> javacArgs() {
    List<String> result = new ArrayList<>();
    List<String> withoutClasspath = argsWithoutClasspath();
    for (int i = 0; i < withoutClasspath.size(); i++) {
      String arg = withoutClasspath.get(i);
      if (arg.startsWith("-Xep")) {
        continue;
      }
      if (arg.equals("-d")) {
        i++;
        continue;
      }
      result.add(arg);
    }
    return result;
  }

  /**
   * Returns the arguments the test declared, with the compiled sources prepended to the classpath.
   *
   * @param classes the directory holding the class files
   * @return the javac arguments
   */
  List<String> argsWithClasspath(Path classes) {
    List<String> result = argsWithoutClasspath();
    result.add("-classpath");
    result.add(classes + File.pathSeparator + declaredClasspath());
    return result;
  }

  /**
   * Returns the classpath the test declared, or the runtime classpath when it declared none.
   *
   * <p>javac takes the last classpath option it is given, so this does too. A test that names a
   * classpath means it for both compilations of bytecode mode: the sources that become class files
   * are compiled against it, and the analyzed sources see it behind the class files.
   *
   * @return the classpath
   */
  private String declaredClasspath() {
    String classpath = runtimeClasspath();
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (arg.startsWith(CLASS_PATH_PREFIX)) {
        classpath = arg.substring(CLASS_PATH_PREFIX.length());
      } else if (CLASSPATH_OPTIONS.contains(arg) && i + 1 < args.size()) {
        classpath = args.get(++i);
      }
    }
    return classpath;
  }

  /**
   * Returns the arguments the test declared, with every spelling of the classpath option removed.
   */
  private List<String> argsWithoutClasspath() {
    List<String> result = new ArrayList<>();
    for (int i = 0; i < args.size(); i++) {
      String arg = args.get(i);
      if (arg.startsWith(CLASS_PATH_PREFIX)) {
        continue;
      }
      if (CLASSPATH_OPTIONS.contains(arg)) {
        i++;
        continue;
      }
      result.add(arg);
    }
    return result;
  }

  private static String runtimeClasspath() {
    return System.getProperty("java.class.path");
  }

  private static Path createWorkingDirectory() {
    try {
      return Files.createTempDirectory("nullaway-bytecode-mode");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Deletes a directory and everything under it, best-effort. This runs in a {@code finally} block,
   * where an exception would replace the failure the test is already reporting, and the directory
   * lives under the system temp directory, so a failed delete leaks rather than corrupts.
   *
   * @param dir the directory to delete
   */
  private static void deleteRecursively(Path dir) {
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
        try {
          Files.delete(path);
        } catch (IOException e) {
          // best-effort
        }
      }
    } catch (IOException | UncheckedIOException e) {
      // best-effort; Files.walk reports a failure during traversal as UncheckedIOException
    }
  }

  /** A source file of a snippet, replayable into an Error Prone {@link CompilationTestHelper}. */
  private abstract static class TestSource {
    /** Returns the path the source file is written to when it becomes a class file. */
    abstract String path();

    /** Returns the content of the source file. */
    abstract String content();

    /**
     * Adds this source to an Error Prone test helper the same way the test declared it.
     *
     * @param helper the test helper
     * @return the test helper
     */
    abstract CompilationTestHelper addTo(CompilationTestHelper helper);
  }

  /** A source file given as literal lines. */
  private static final class SourceLines extends TestSource {
    private final String path;
    private final String[] lines;

    SourceLines(String path, String[] lines) {
      this.path = path;
      this.lines = lines;
    }

    @Override
    String path() {
      return path;
    }

    @Override
    String content() {
      return String.join("\n", lines) + "\n";
    }

    @Override
    CompilationTestHelper addTo(CompilationTestHelper helper) {
      return helper.addSourceLines(path, lines);
    }
  }

  /** A source file read from a test resource. */
  private static final class ResourceFile extends TestSource {
    private final Class<?> resourceClass;
    private final String path;
    private String content;

    ResourceFile(Class<?> resourceClass, String path) {
      this.resourceClass = resourceClass;
      this.path = path;
    }

    @Override
    String path() {
      return path;
    }

    @Override
    String content() {
      if (content == null) {
        try (InputStream stream = resourceClass.getResourceAsStream(path)) {
          if (stream == null) {
            throw new AssertionError("could not find resource: " + path + " for: " + resourceClass);
          }
          content = new String(ByteStreams.toByteArray(stream), UTF_8);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      return content;
    }

    @Override
    @SuppressWarnings("deprecation")
    CompilationTestHelper addTo(CompilationTestHelper helper) {
      return helper.addSourceFile(path);
    }
  }
}
