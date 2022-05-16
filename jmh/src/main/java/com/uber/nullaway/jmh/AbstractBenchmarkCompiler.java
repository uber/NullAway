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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** common logic for compiling a benchmark in JMH performance testing */
public abstract class AbstractBenchmarkCompiler {

  private final NullawayJavac nullawayJavac;

  public AbstractBenchmarkCompiler() throws IOException {
    nullawayJavac =
        NullawayJavac.create(
            getSourceFileNames(),
            getAnnotatedPackages(),
            getClasspath(),
            getExtraErrorProneArgs(),
            getExtraProcessorPath());
  }

  public final boolean compile() {
    return nullawayJavac.compile();
  }

  /** Get the names of source files to be compiled */
  protected List<String> getSourceFileNames() throws IOException {
    String sourceDir = getSourceDirectory();
    try (Stream<Path> stream =
        Files.find(
            Paths.get(sourceDir), 100, (p, bfa) -> p.getFileName().toString().endsWith(".java"))) {
      List<String> sourceFileNames =
          stream.map(p -> p.toFile().getAbsolutePath()).collect(Collectors.toList());
      return sourceFileNames;
    }
  }

  /** Get the root directory containing the benchmark source files */
  protected abstract String getSourceDirectory();

  /** Get the value to pass for {@code -XepOpt:NullAway:AnnotatedPackages} */
  protected abstract String getAnnotatedPackages();

  /** Get the classpath required to compile the benchmark */
  protected abstract String getClasspath();

  /** Get any extra arguments that should be passed to Error Prone */
  protected List<String> getExtraErrorProneArgs() {
    return Collections.emptyList();
  }

  /** Get a path of additional jars to be included in the processor path when compiling */
  protected String getExtraProcessorPath() {
    return "";
  }
}
