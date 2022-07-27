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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SerializationTestHelper<T extends Display> {

  private final Path outputDir;
  private ImmutableList<T> expectedOutputs;
  private CompilationTestHelper compilationTestHelper;
  private DisplayFactory<T> factory;
  private String fileNamePostfix;
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

  public SerializationTestHelper<T> setOutputFileNamePostfixAndHeader(
      String fileNamePostfix, String header) {
    this.fileNamePostfix = fileNamePostfix;
    this.header = header;
    return this;
  }

  public void doTest() {
    Preconditions.checkNotNull(factory, "Factory cannot be null");
    Preconditions.checkNotNull(fileNamePostfix, "File name postfix cannot be null");
    clearAnyExistingFileWithPostFixUnderOutputDirectory();
    compilationTestHelper.doTest();
    List<T> actualOutputs = readActualOutputsFromFileWithPostfix();
    compare(actualOutputs);
  }

  private void clearAnyExistingFileWithPostFixUnderOutputDirectory() {
    try (Stream<Path> paths = Files.walk(outputDir)) {
      paths
          .filter(
              path ->
                  path.toFile().isFile() && path.getFileName().toString().endsWith(fileNamePostfix))
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException e) {
                  throw new RuntimeException("Error while deleting file at: " + path, e);
                }
              });
    } catch (IOException exception) {
      throw new RuntimeException(
          "Error while deleting existing files with postfix: "
              + fileNamePostfix
              + " at: "
              + outputDir);
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

  private List<T> readActualOutputsFromFileWithPostfix() {
    List<T> outputs = new ArrayList<>();
    BufferedReader reader;
    // Max depth is set to 1 as the output file should be the direct child of the defined output
    // directory.
    try (Stream<Path> paths = Files.walk(outputDir, 1)) {
      Optional<Path> optional =
          paths
              .filter(
                  path ->
                      path.toFile().isFile()
                          && path.getFileName().toString().endsWith(fileNamePostfix))
              .findAny();
      if (!optional.isPresent()) {
        throw new RuntimeException(
            "File name with postfix: "
                + fileNamePostfix
                + " was not found under directory: "
                + outputDir);
      }
      Path outputPath = optional.get();
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
}
