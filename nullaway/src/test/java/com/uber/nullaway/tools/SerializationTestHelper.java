/*
 * Copyright (c) 2022 Uber Technologies, Inc.
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

import static org.junit.Assert.fail;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.fixserialization.out.SuggestedFixInfo;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SerializationTestHelper {
  private FixDisplay[] expectedOutputFixes;
  private final Path outputPath;
  private CompilationTestHelper compilationTestHelper;

  public SerializationTestHelper(Path outputPath) {
    this.outputPath = outputPath.resolve("fixes.tsv");
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  public SerializationTestHelper addSourceLines(String path, String... lines) {
    compilationTestHelper.addSourceLines(path, lines);
    return this;
  }

  public SerializationTestHelper addExpectedFixes(FixDisplay... fixDisplays) {
    this.expectedOutputFixes = fixDisplays;
    return this;
  }

  public SerializationTestHelper expectNoFixSerialization() {
    this.expectedOutputFixes = new FixDisplay[0];
    return this;
  }

  public SerializationTestHelper setArgs(List<String> args) {
    compilationTestHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass()).setArgs(args);
    return this;
  }

  public void doTest() {
    clearOutput();
    compilationTestHelper.doTest();
    FixDisplay[] outputFixDisplays;
    outputFixDisplays = readOutputFixes();
    compareFixes(outputFixDisplays);
  }

  private void clearOutput() {
    try {
      Files.deleteIfExists(outputPath);
    } catch (IOException ignored) {
      throw new RuntimeException("Failed to delete older files.");
    }
  }

  private void compareFixes(FixDisplay[] outputFixDisplays) {
    ArrayList<FixDisplay> notFound = new ArrayList<>();
    ArrayList<FixDisplay> output = new ArrayList<>(Arrays.asList(outputFixDisplays));
    for (FixDisplay f : expectedOutputFixes) {
      if (!output.contains(f)) {
        notFound.add(f);
      } else {
        output.remove(f);
      }
    }
    if (notFound.size() == 0 && output.size() == 0) {
      return;
    }
    if (notFound.size() == 0) {
      fail(
          ""
              + output.size()
              + " redundant suggest(s) were found: "
              + "\n"
              + Arrays.deepToString(output.toArray())
              + "\n");
    }
    fail(
        ""
            + notFound.size()
            + " suggest(s) were not found: "
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
      reader = Files.newBufferedReader(this.outputPath, Charset.defaultCharset());
      String header = reader.readLine();
      if (!header.equals(SuggestedFixInfo.header())) {
        fail(
            "Expected header of fixes.tsv to be: "
                + SuggestedFixInfo.header()
                + "\bBut found: "
                + header);
      }
      String line = reader.readLine();
      while (line != null) {
        FixDisplay fixDisplay = FixDisplay.fromStringWithDelimiter(line);
        // Fixes are written in Temp Directory and is not known at compile time, therefore, relative
        // paths are getting compared.
        fixDisplay.uri = fixDisplay.uri.substring(fixDisplay.uri.indexOf("com/uber/"));
        fixDisplays.add(fixDisplay);
        line = reader.readLine();
      }
      reader.close();
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeException("Error happened in reading the fixDisplays.");
    }
    return fixDisplays.toArray(new FixDisplay[0]);
  }
}
