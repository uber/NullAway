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

import com.google.common.base.Preconditions;
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
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class SerializationTestHelper<T> {
  private List<T> expectedOutputs;
  private CompilationTestHelper compilationTestHelper;
  Factory<T> factory;
  private final Path outputPath;

  public SerializationTestHelper(Path outputPath) {
    this.outputPath = outputPath.resolve("fixes.tsv");
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  public SerializationTestHelper<T> addSourceLines(String path, String... lines) {
    compilationTestHelper.addSourceLines(path, lines);
    return this;
  }

  @SafeVarargs
  public final SerializationTestHelper<T> setExpectedOutputs(T... fixDisplays) {
    this.expectedOutputs = Arrays.asList(fixDisplays);
    return this;
  }

  public SerializationTestHelper<T> expectNoOutput() {
    this.expectedOutputs = Collections.emptyList();
    return this;
  }

  public SerializationTestHelper<T> setArgs(List<String> args) {
    compilationTestHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass()).setArgs(args);
    return this;
  }

  public SerializationTestHelper<T> setFactory(Factory<T> factory) {
    this.factory = factory;
    return this;
  }

  public void doTest() {
    Preconditions.checkNotNull(factory, "Factory cannot be null");
    clearOutput();
    compilationTestHelper.doTest();
    List<T> actualOutputs = readActualOutputs();
    compare(actualOutputs);
  }

  private void clearOutput() {
    try {
      Files.deleteIfExists(outputPath);
    } catch (IOException ignored) {
      throw new RuntimeException("Failed to delete older files.");
    }
  }

  private void compare(List<T> actualOutput) {
    List<T> notFound = new ArrayList<>();
    for (T o : expectedOutputs) {
      if (!actualOutput.contains(o)) {
        notFound.add(o);
      } else {
        actualOutput.remove(o);
      }
    }
    if (notFound.size() == 0 && actualOutput.size() == 0) {
      return;
    }
    StringBuilder errorMessage = new StringBuilder();
    if (notFound.size() != 0) {
      errorMessage
          .append(notFound.size())
          .append(" expected outputs were NOT found:")
          .append("\n")
          .append(notFound.stream().map(T::toString).collect(Collectors.toList()))
          .append("\n");
    }
    if (actualOutput.size() != 0) {
      errorMessage
          .append(actualOutput.size())
          .append(" unexpected outputs were found:")
          .append("\n")
          .append(actualOutput.stream().map(T::toString).collect(Collectors.toList()))
          .append("\n");
    }
    fail(errorMessage.toString());
  }

  private List<T> readActualOutputs() {
    List<T> outputs = new ArrayList<>();
    BufferedReader reader;
    try {
      reader = Files.newBufferedReader(this.outputPath, Charset.defaultCharset());
      String header = reader.readLine();
      if (!header.equals(SuggestedFixInfo.header())) {
        fail(
            "Expected header of fixes.tsv to be: "
                + SuggestedFixInfo.header()
                + "\nBut found: "
                + header);
      }
      String line = reader.readLine();
      while (line != null) {

        T fixDisplay = factory.fromStringWithDelimiter(line);
        outputs.add(fixDisplay);
        line = reader.readLine();
      }
      reader.close();
    } catch (IOException e) {
      throw new RuntimeException("Error happened in reading the outputs.", e);
    }
    return outputs;
  }
}
