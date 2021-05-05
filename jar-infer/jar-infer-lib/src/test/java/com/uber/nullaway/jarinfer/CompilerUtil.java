/*
 * Copyright (C) 2018. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.nullaway.jarinfer;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.BaseErrorProneJavaCompiler;
import com.google.errorprone.DiagnosticTestHelper;
import com.google.errorprone.ErrorProneInMemoryFileManager;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.scanner.ScannerSupplier;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Utility type for compiling Java code using Error Prone. Similar to {@link
 * com.google.errorprone.CompilationTestHelper} but does not require providing any Error Prone
 * checker.
 *
 * <p>A great deal of code is taken from {@link com.google.errorprone.CompilationTestHelper}
 */
public class CompilerUtil {

  private static final ImmutableList<String> DEFAULT_ARGS =
      ImmutableList.of(
          "-encoding",
          "UTF-8",
          // print stack traces for completion failures
          "-XDdev");

  private final ErrorProneInMemoryFileManager fileManager;
  private final List<JavaFileObject> sources = new ArrayList<>();
  private final BaseErrorProneJavaCompiler compiler;
  private final ByteArrayOutputStream outputStream;
  private final DiagnosticTestHelper diagnosticHelper;
  private List<String> args = ImmutableList.of();

  public CompilerUtil(Class<?> klass) {
    this.fileManager = new ErrorProneInMemoryFileManager(klass);
    try {
      fileManager.setLocation(StandardLocation.SOURCE_PATH, Collections.<File>emptyList());
    } catch (IOException e) {
      throw new RuntimeException("unexpected IOException", e);
    }
    outputStream = new ByteArrayOutputStream();
    diagnosticHelper = new DiagnosticTestHelper();
    this.compiler =
        new BaseErrorProneJavaCompiler(
            ScannerSupplier.fromBugCheckerClasses(
                Collections.<Class<? extends BugChecker>>emptySet()));
  }
  /**
   * Adds a source file to the test compilation, from an existing resource file.
   *
   * @param path the path to the source file
   */
  public CompilerUtil addSourceFile(String path) {
    this.sources.add(fileManager.forResource(path));
    return this;
  }

  public CompilerUtil addSourceLines(String path, String... lines) {
    this.sources.add(fileManager.forSourceLines(path, lines));
    return this;
  }

  /**
   * Sets custom command-line arguments for the compilation. These will be appended to the default
   * compilation arguments.
   */
  public CompilerUtil setArgs(List<String> args) {
    this.args = args;
    return this;
  }

  private boolean compile(Iterable<JavaFileObject> sources, Iterable<String> args) {
    PrintWriter writer =
        new PrintWriter(new BufferedWriter(new OutputStreamWriter(outputStream, UTF_8)), true);
    JavaCompiler.CompilationTask task =
        compiler.getTask(
            writer,
            fileManager,
            diagnosticHelper.collector,
            args,
            null,
            ImmutableList.copyOf(sources));
    return task.call();
  }

  public String getOutput() {
    return outputStream.toString();
  }

  /**
   * Creates a list of arguments to pass to the compiler, including the list of source files to
   * compile. Uses DEFAULT_ARGS as the base and appends the extraArgs passed in.
   */
  private static List<String> buildArguments(List<String> extraArgs) {
    return ImmutableList.<String>builder()
        .addAll(DEFAULT_ARGS)
        .addAll(disableImplicitProcessing(extraArgs))
        .build();
  }

  /**
   * Pass -proc:none unless annotation processing is explicitly enabled, to avoid picking up
   * annotation processors via service loading.
   */
  private static List<String> disableImplicitProcessing(List<String> args) {
    if (args.indexOf("-processor") != -1 || args.indexOf("-processorpath") != -1) {
      return args;
    }
    return ImmutableList.<String>builder().addAll(args).add("-proc:none").build();
  }

  public boolean run() {
    Preconditions.checkState(!sources.isEmpty(), "No source files to compile");
    List<String> allArgs = buildArguments(args);
    return compile(sources, allArgs);
  }
}
