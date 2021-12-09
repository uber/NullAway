package com.uber.nullaway.tools;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.BaseErrorProneJavaCompiler;
import com.google.errorprone.BugPattern;
import com.google.errorprone.DiagnosticTestHelper;
import com.google.errorprone.ErrorProneOptions;
import com.google.errorprone.InvalidCommandLineOptionException;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.scanner.ScannerSupplier;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.main.Main;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

public class AutoFixTestHelper {

  private static final ImmutableList<String> DEFAULT_ARGS =
      ImmutableList.of("-encoding", "UTF-8", "-XDdev", "-parameters", "-XDcompilePolicy=simple");

  private final DiagnosticTestHelper diagnosticHelper;
  private final BaseErrorProneJavaCompiler compiler;
  private final ByteArrayOutputStream outputStream;
  private final NullAwayInMemoryFileManager fileManager;
  private final List<JavaFileObject> sources = new ArrayList<>();
  private ImmutableList<String> extraArgs = ImmutableList.of();
  private boolean run = false;
  private final List<FixDisplay> fixDisplays = new ArrayList<>();
  private String outputPath;
  private boolean haveFixes = true;

  private AutoFixTestHelper(ScannerSupplier scannerSupplier, String checkName, Class<?> clazz) {
    this.fileManager = new NullAwayInMemoryFileManager(clazz);
    try {
      fileManager.setLocation(StandardLocation.SOURCE_PATH, Collections.emptyList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    this.diagnosticHelper = new DiagnosticTestHelper(checkName);
    this.outputStream = new ByteArrayOutputStream();
    this.compiler = new BaseErrorProneJavaCompiler(scannerSupplier);
  }

  public static AutoFixTestHelper newInstance(Class<? extends BugChecker> checker, Class<?> clazz) {
    ScannerSupplier scannerSupplier = ScannerSupplier.fromBugCheckerClasses(checker);
    String checkName = checker.getAnnotation(BugPattern.class).name();
    return new AutoFixTestHelper(scannerSupplier, checkName, clazz);
  }

  static List<String> disableImplicitProcessing(List<String> args) {
    if (args.contains("-processor") || args.contains("-processorpath")) {
      return args;
    }
    return ImmutableList.<String>builder().addAll(args).add("-proc:none").build();
  }

  private static List<String> buildArguments(List<String> extraArgs) {
    ImmutableList.Builder<String> result = ImmutableList.<String>builder().addAll(DEFAULT_ARGS);
    return result.addAll(disableImplicitProcessing(extraArgs)).build();
  }

  public AutoFixTestHelper addSourceLines(String path, String... lines) {
    this.sources.add(fileManager.forSourceLines(path, lines));
    return this;
  }

  public AutoFixTestHelper addFixes(FixDisplay... fixDisplays) {
    Path p = fileManager.fileSystem().getRootDirectories().iterator().next();
    for (FixDisplay f : fixDisplays) {
      f.uri = p.toAbsolutePath().toUri().toASCIIString().concat(f.uri);
    }
    this.fixDisplays.addAll(Arrays.asList(fixDisplays));
    return this;
  }

  public AutoFixTestHelper setNoFix() {
    this.haveFixes = false;
    return this;
  }

  public AutoFixTestHelper setArgs(List<String> args) {
    this.extraArgs = ImmutableList.copyOf(args);
    return this;
  }

  public void doTest() {
    clearOutput();
    checkState(!sources.isEmpty(), "No source files to compile");
    checkState(!run, "doTest should only be called once");
    this.run = true;
    Main.Result result = compile();
    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnosticHelper.getDiagnostics()) {
      if (diagnostic.getCode().contains("error.prone.crash")) {
        fail(diagnostic.getMessage(Locale.ENGLISH));
      }
    }
    FixDisplay[] outputFixDisplays;
    if (haveFixes) {
      outputFixDisplays = readOutputFixes();
    } else {
      outputFixDisplays = new FixDisplay[0];
    }
    compareFixes(outputFixDisplays);
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private void clearOutput() {
    new File(outputPath).delete();
  }

  private void compareFixes(FixDisplay[] outputFixDisplays) {
    ArrayList<FixDisplay> notFound = new ArrayList<>();
    ArrayList<FixDisplay> output = new ArrayList<>(Arrays.asList(outputFixDisplays));
    for (FixDisplay f : fixDisplays) {
      if (!output.contains(f)) notFound.add(f);
      else output.remove(f);
    }
    if (notFound.size() == 0 && output.size() == 0) return;
    if (notFound.size() == 0) {
      fail(
          ""
              + output.size()
              + " redundant fix(s) were found: "
              + "\n"
              + Arrays.deepToString(output.toArray())
              + "\n"
              + "Fixer did not found any fix!"
              + "\n");
    }
    fail(
        ""
            + notFound.size()
            + " fix(s) were not found: "
            + "\n"
            + Arrays.deepToString(notFound.toArray())
            + "\n"
            + "redundant fixDisplays list:"
            + "\n"
            + "================="
            + "\n"
            + Arrays.deepToString(output.toArray())
            + "\n"
            + "================="
            + "\n");
  }

  private FixDisplay[] readOutputFixes() {
    ArrayList<FixDisplay> fixDisplays = new ArrayList<>();
    BufferedReader reader;
    try {
      reader = Files.newBufferedReader(Paths.get(this.outputPath), Charset.defaultCharset());
      String line = reader.readLine();
      if (line != null) line = reader.readLine();
      while (line != null) {
        fixDisplays.add(FixDisplay.fromCSVLine(line));
        line = reader.readLine();
      }
      reader.close();
    } catch (IOException e) {
      System.err.println("Error happened in reading the fixDisplays!" + e);
    }
    return fixDisplays.toArray(new FixDisplay[0]);
  }

  private Main.Result compile() {
    List<String> processedArgs = buildArguments(extraArgs);
    checkWellFormed(sources, processedArgs);
    fileManager.createAndInstallTempFolderForOutput();
    return compiler
            .getTask(
                new PrintWriter(
                    new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8)), true),
                fileManager,
                diagnosticHelper.collector,
                ImmutableList.copyOf(processedArgs),
                ImmutableList.of(),
                sources)
            .call()
        ? Main.Result.OK
        : Main.Result.ERROR;
  }

  private void checkWellFormed(Iterable<JavaFileObject> sources, List<String> args) {
    fileManager.createAndInstallTempFolderForOutput();
    JavaCompiler compiler = JavacTool.create();
    OutputStream outputStream = new ByteArrayOutputStream();
    List<String> remainingArgs = null;
    try {
      remainingArgs = Arrays.asList(ErrorProneOptions.processArgs(args).getRemainingArgs());
    } catch (InvalidCommandLineOptionException e) {
      fail("Exception during argument processing: " + e);
    }
    JavaCompiler.CompilationTask task =
        compiler.getTask(
            new PrintWriter(
                new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8)),
                /*autoFlush=*/ true),
            fileManager,
            null,
            remainingArgs,
            null,
            sources);
    boolean result = task.call();
    assertWithMessage(
            String.format(
                "Test program failed to compile with non Error Prone error: %s", outputStream))
        .that(result)
        .isTrue();
  }

  public AutoFixTestHelper setOutputPath(String outputPath) {
    this.outputPath = outputPath;
    return this;
  }
}
