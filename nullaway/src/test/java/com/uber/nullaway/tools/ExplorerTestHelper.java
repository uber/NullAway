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
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

@SuppressWarnings({
  "UnusedVariable",
  "UnusedMethod"
}) // TODO: remove this later, this class is still under construction on
// 'AutoFix' branch
public class ExplorerTestHelper {

  private static final ImmutableList<String> DEFAULT_ARGS =
      ImmutableList.of("-encoding", "UTF-8", "-XDdev", "-parameters", "-XDcompilePolicy=simple");

  private final DiagnosticTestHelper diagnosticHelper;
  private final BaseErrorProneJavaCompiler compiler;
  private final ByteArrayOutputStream outputStream;
  private final NullAwayInMemoryFileManager fileManager;
  private final List<JavaFileObject> sources = new ArrayList<>();
  private ImmutableList<String> extraArgs = ImmutableList.of();
  private boolean run = false;
  private final List<Batch> batches = new ArrayList<>();
  private String outputPath;

  private ExplorerTestHelper(ScannerSupplier scannerSupplier, String checkName, Class<?> clazz) {
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

  public static ExplorerTestHelper newInstance(
      Class<? extends BugChecker> checker, Class<?> clazz) {
    ScannerSupplier scannerSupplier = ScannerSupplier.fromBugCheckerClasses(checker);
    String checkName = checker.getAnnotation(BugPattern.class).name();
    return new ExplorerTestHelper(scannerSupplier, checkName, clazz);
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

  public ExplorerTestHelper addSourceLines(String path, String... lines) {
    this.sources.add(fileManager.forSourceLines(path, lines));
    return this;
  }

  public ExplorerTestHelper addBatches(Batch... batches) {
    Path p = fileManager.fileSystem().getRootDirectories().iterator().next();
    for (Batch batch : batches) {
      batch.setRootForUri(p);
    }
    this.batches.addAll(Arrays.asList(batches));
    return this;
  }

  public ExplorerTestHelper setArgs(List<String> args) {
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
    Batch[] outputBatches = readOutputBatches();
    compareBatches(outputBatches);
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private void clearOutput() {
    new File(outputPath).delete();
  }

  private void compareBatches(Batch[] outputBatches) {
    ArrayList<Batch> notFound = new ArrayList<>();
    ArrayList<Batch> output = new ArrayList<>(Arrays.asList(outputBatches));
    for (Batch f : batches) {
      if (!output.contains(f)) notFound.add(f);
      else output.remove(f);
    }
    if (notFound.size() == 0 && output.size() == 0) return;
    if (notFound.size() == 0) {
      fail(
          ""
              + output.size()
              + " redundant batch(s) were found: "
              + "\n"
              + Arrays.deepToString(output.toArray())
              + "\n"
              + "Fixer did not found any batch!"
              + "\n");
    }
    fail(
        ""
            + notFound.size()
            + " batch(s) were not found: "
            + "\n"
            + Arrays.deepToString(notFound.toArray())
            + "\n"
            + "redundant batches list:"
            + "\n"
            + "================="
            + "\n"
            + Arrays.deepToString(output.toArray())
            + "\n"
            + "================="
            + "\n");
  }

  private Batch[] readOutputBatches() {
    try {
      BufferedReader bufferedReader =
          Files.newBufferedReader(Paths.get(this.outputPath), Charset.defaultCharset());
      JSONObject obj = (JSONObject) new JSONParser().parse(bufferedReader);
      JSONArray batchesJson = (JSONArray) obj.get("batches");
      bufferedReader.close();
      return extractBatchesFromJson(batchesJson);
    } catch (IOException ex) {
      return new Batch[0];
    } catch (ParseException e) {
      throw new RuntimeException("Error in parsing object: " + e);
    }
  }

  private Batch[] extractBatchesFromJson(JSONArray batchesJson) {
    Batch[] batches = new Batch[batchesJson.size()];
    for (int i = 0; i < batchesJson.size(); i++) {
      JSONObject batch = (JSONObject) batchesJson.get(i);
      batches[i] = Batch.createFromJson(batch);
    }
    return batches;
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

  public ExplorerTestHelper setOutputPath(String outputPath) {
    this.outputPath = outputPath;
    return this;
  }
}
