package com.uber.nullaway.tools;

import static org.junit.Assert.fail;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AutoFixTestHelper {
  private final List<FixDisplay> fixDisplays = new ArrayList<>();
  private final String outputPath = "/tmp/NullAwayFix/fixes.csv";
  private boolean haveFixes = true;
  private CompilationTestHelper compilationTestHelper;

  @SuppressWarnings("ResultOfMethodCallIgnored")
  public AutoFixTestHelper addSourceLines(String path, String... lines) {
    compilationTestHelper.addSourceLines(path, lines);
    return this;
  }

  public AutoFixTestHelper addFixes(FixDisplay... fixDisplays) {
    this.fixDisplays.addAll(Arrays.asList(fixDisplays));
    return this;
  }

  public AutoFixTestHelper setNoFix() {
    this.haveFixes = false;
    return this;
  }

  public AutoFixTestHelper setArgs(List<String> args) {
    compilationTestHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass()).setArgs(args);
    return this;
  }

  public void doTest() {
    clearOutput();
    try {
      compilationTestHelper.doTest();
    } catch (Exception e) {
      System.out.println("Ignored");
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
        FixDisplay fixDisplay = FixDisplay.fromCSVLine(line, "(\\$\\*\\$)");
        fixDisplay.uri = fixDisplay.uri.substring(fixDisplay.uri.indexOf("com/uber/"));
        fixDisplays.add(fixDisplay);
        line = reader.readLine();
      }
      reader.close();
    } catch (IOException e) {
      System.err.println("Error happened in reading the fixDisplays!" + e);
    }
    return fixDisplays.toArray(new FixDisplay[0]);
  }
}
