package com.uber.nullaway.oracles;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.BugCheckerInfo;
import com.google.errorprone.ErrorProneJavaCompiler;
import com.google.errorprone.FileObjects;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.scanner.ScannerSupplier;
import com.uber.nullaway.NullAway;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;

/**
 * Compiles sources in this JVM, with or without one Error Prone checker, NullAway unless a test
 * substitutes another.
 *
 * <p>Every compilation uses the classpath this JVM runs with, so a source may reference anything
 * the NullAway tests can: the annotation jars, {@code test-java-lib}, and the like. Instances are
 * not thread-safe, since they share one file manager.
 */
final class Compiler {

  /** Why a NullAway run ended the way it did. */
  enum Status {
    /** The sources compiled and NullAway finished; its warnings are in {@link Outcome}. */
    OK,
    /** javac rejected the sources before NullAway ran, so the run says nothing about NullAway. */
    INVALID,
    /** NullAway threw an exception on sources javac accepted. */
    CRASH,
  }

  /**
   * One NullAway warning.
   *
   * @param path the {@link Source#path()} of the unit the warning is in
   * @param line the 1-based line javac reported
   * @param message the first line of the message, which starts with the checker's name in brackets,
   *     such as {@code [NullAway]}
   */
  record Warning(String path, long line, String message) {}

  /**
   * The result of one NullAway run.
   *
   * @param status how the run ended
   * @param warnings NullAway's warnings, in the order javac reported them; empty unless {@code
   *     status} is {@link Status#OK}
   * @param detail the first javac error for {@link Status#INVALID}, the stack trace for {@link
   *     Status#CRASH}, and empty for {@link Status#OK}
   */
  record Outcome(Status status, ImmutableList<Warning> warnings, String detail) {}

  private static final String CLASSPATH = System.getProperty("java.class.path");

  private final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
  private final StandardJavaFileManager fileManager =
      javac.getStandardFileManager(null, Locale.ROOT, UTF_8);
  private final Class<? extends BugChecker> checker;
  private final String checkerName;

  /** Creates a compiler that runs NullAway. */
  Compiler() {
    this(NullAway.class);
  }

  /** Creates a compiler that runs {@code checker} in place of NullAway. */
  Compiler(Class<? extends BugChecker> checker) {
    this.checker = checker;
    this.checkerName = BugCheckerInfo.create(checker).canonicalName();
  }

  /**
   * Runs NullAway over {@code sources} with {@code nullAwayOptions} added to the javac arguments.
   *
   * <p>NullAway runs at warning severity, so its reports never make javac fail, and an error javac
   * reports means the sources are invalid. Error Prone reports a checker's exception as an error
   * with the code {@code error.prone.crash} and then rethrows it; either sign alone is enough to
   * classify the run as a crash.
   *
   * @param extraClasspath a directory of class files to compile against, or null for none
   */
  Outcome runNullAway(
      List<Source> sources, List<String> nullAwayOptions, @Nullable Path extraClasspath) {
    Path out = createTempDirectory();
    try {
      List<String> args = new ArrayList<>(commonArgs(out, extraClasspath));
      args.addAll(
          List.of(
              "-XDcompilePolicy=simple",
              "--should-stop=ifError=FLOW",
              "-XDaddTypeAnnotationsToSymbol=true",
              "-Xep:" + checkerName + ":WARN"));
      args.addAll(nullAwayOptions);
      DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
      // Only call() runs the checker: an exception from getTask, such as a rejected option, is
      // a defect of this harness and propagates.
      JavaCompiler.CompilationTask task =
          new ErrorProneJavaCompiler(ScannerSupplier.fromBugCheckerClasses(checker))
              .getTask(null, fileManager, collector, args, null, fileObjects(sources));
      Throwable thrown = null;
      try {
        task.call();
      } catch (RuntimeException | LinkageError | StackOverflowError e) {
        thrown = e;
      }
      return classify(sources, collector.getDiagnostics(), thrown, "[" + checkerName + "]");
    } finally {
      deleteRecursively(out);
    }
  }

  /**
   * Compiles {@code sources} with plain javac into {@code outDir}, keeping parameter names so that
   * NullAway messages about the compiled methods name the same parameters as the source would.
   *
   * @return whether javac accepted the sources
   */
  boolean compileWithoutNullAway(List<Source> sources, Path outDir) {
    return javac
        .getTask(
            null,
            fileManager,
            diagnostic -> {},
            commonArgs(outDir, null),
            null,
            fileObjects(sources))
        .call();
  }

  private static ImmutableList<String> commonArgs(Path out, @Nullable Path extraClasspath) {
    String classpath =
        extraClasspath == null ? CLASSPATH : extraClasspath + File.pathSeparator + CLASSPATH;
    return ImmutableList.of(
        "-d",
        out.toString(),
        "-classpath",
        classpath,
        "-proc:none",
        "-parameters",
        // javac reports 100 warnings by default and drops the rest without a sign.
        "-Xmaxwarns",
        "1000000",
        "-encoding",
        "UTF-8");
  }

  private static ImmutableList<JavaFileObject> fileObjects(List<Source> sources) {
    return sources.stream()
        .map(s -> FileObjects.forSourceLines(s.path(), s.text()))
        .collect(ImmutableList.toImmutableList());
  }

  private static Outcome classify(
      List<Source> sources,
      List<Diagnostic<? extends JavaFileObject>> diagnostics,
      @Nullable Throwable thrown,
      String warningPrefix) {
    for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
      if (d.getCode() != null && d.getCode().contains("error.prone.crash")) {
        return new Outcome(Status.CRASH, ImmutableList.of(), d.getMessage(Locale.ROOT));
      }
    }
    if (thrown != null) {
      return new Outcome(
          Status.CRASH, ImmutableList.of(), Throwables.getStackTraceAsString(thrown));
    }
    ImmutableList.Builder<Warning> warnings = ImmutableList.builder();
    for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
      String message = d.getMessage(Locale.ROOT);
      if (d.getKind() == Diagnostic.Kind.ERROR) {
        return new Outcome(Status.INVALID, ImmutableList.of(), message);
      }
      if (message.startsWith(warningPrefix) && d.getSource() != null) {
        warnings.add(
            new Warning(
                pathOf(sources, d.getSource()),
                d.getLineNumber(),
                message.lines().findFirst().get()));
      }
    }
    return new Outcome(Status.OK, warnings.build(), "");
  }

  /** Returns the path of the unit in {@code sources} that {@code file} was created from. */
  private static String pathOf(List<Source> sources, JavaFileObject file) {
    String uriPath = file.toUri().getPath();
    return sources.stream()
        .map(Source::path)
        .filter(uriPath::endsWith)
        .max(Comparator.comparingInt(String::length))
        .orElse(uriPath);
  }

  /** Creates an empty directory under the system temporary directory. */
  static Path createTempDirectory() {
    try {
      return Files.createTempDirectory("nullaway-oracles");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Deletes {@code dir} and everything under it. */
  static void deleteRecursively(Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
