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
import com.google.common.collect.ImmutableList;
import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class SerializationTestHelper<T extends Display> {

  private final Path outputDir;
  private ImmutableList<T> expectedOutputs;
  private CompilationTestHelper compilationTestHelper;
  private DisplayFactory<T> factory;
  private String fileName;
  private String header;

  public SerializationTestHelper(Path outputDir) {
    this.outputDir = outputDir;
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  public SerializationTestHelper<T> addSourceLines(String path, String... lines) {
    compilationTestHelper.addSourceLines(path, lines);
    return this;
  }

  @SafeVarargs
  public final SerializationTestHelper<T> setExpectedOutputs(T... outputs) {
    this.expectedOutputs = ImmutableList.copyOf(outputs);
    return this;
  }

  public SerializationTestHelper<T> expectNoOutput() {
    this.expectedOutputs = ImmutableList.of();
    return this;
  }

  public SerializationTestHelper<T> setArgs(List<String> args) {
    compilationTestHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass()).setArgs(args);
    return this;
  }

  public SerializationTestHelper<T> setFactory(DisplayFactory<T> factory) {
    this.factory = factory;
    return this;
  }

  public SerializationTestHelper<T> setOutputFileNameAndHeader(String fileName, String header) {
    this.fileName = fileName;
    this.header = header;
    return this;
  }

  public void doTest() {
    Preconditions.checkNotNull(factory, "Factory cannot be null");
    Preconditions.checkNotNull(fileName, "File name cannot be null");
    Path outputPath = outputDir.resolve(fileName);
    try {
      Files.deleteIfExists(outputPath);
    } catch (IOException e) {
      throw new RuntimeException("Failed to delete older file at: " + outputPath, e);
    }
    compilationTestHelper.doTest();
    List<T> actualOutputs = readActualOutputs(outputPath);
    compare(actualOutputs);
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

  private List<T> readActualOutputs(Path outputPath) {
    List<T> outputs = new ArrayList<>();
    BufferedReader reader;
    try {
      reader = Files.newBufferedReader(outputPath, Charset.defaultCharset());
      String actualHeader = reader.readLine();
      if (!header.equals(actualHeader)) {
        fail(
            "Expected header of "
                + outputPath.getFileName()
                + " to be: "
                + header
                + "\nBut found: "
                + actualHeader);
      }
      String line = reader.readLine();
      while (line != null) {
        T output = factory.fromValuesInString(line.split("\\t"));
        outputs.add(output);
        line = reader.readLine();
      }
      reader.close();
    } catch (IOException e) {
      throw new RuntimeException("Error happened in reading the outputs.", e);
    }
    return outputs;
  }

  /**
   * Checks if given paths are equal. Under different OS environments, identical paths might have a
   * different string representation. In windows all forward slashes are replaced with backslashes.
   *
   * @param expected Expected serialized path.
   * @param found Serialized path.
   * @return true, if paths are identical.
   */
  public static boolean pathsAreEqual(String expected, String found) {
    if (found.equals(expected)) {
      return true;
    }
    return found.replaceAll("\\\\", "/").equals(expected);
  }

  /**
   * Extracts relative path from the serialized full path.
   *
   * @param pathInString Full serialized path.
   * @return Relative path to "com" from the given path including starting from "com" directory.
   */
  public static String getRelativePathFromUnitTestTempDirectory(String pathInString) {
    if (pathInString.equals("null")) {
      return "null";
    }
    // using atomic refs to use them inside inner class below. This is not due to any concurrent
    // modifications.
    AtomicReference<Path> relativePath = new AtomicReference<>(Paths.get("com"));
    AtomicReference<Boolean> relativePathStarted = new AtomicReference<>(false);
    Path path = Paths.get(pathInString);
    path.iterator()
        .forEachRemaining(
            remaining -> {
              if (relativePathStarted.get()) {
                relativePath.set(relativePath.get().resolve(remaining));
              }
              if (remaining.toString().startsWith("com")) {
                relativePathStarted.set(true);
              }
            });
    return relativePath.get().toString();
  }
}
