/*
 * Copyright (C) 2026. Uber Technologies
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
package com.uber.nullaway.tools;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Arrays.asList;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.scanner.ScannerSupplier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Wraps Error Prone's {@link CompilationTestHelper} so that a multi-file test also has to pass with
 * its sources handed to javac in reverse order.
 *
 * <p>The order in which a build system hands source files to javac is not part of the program, so
 * the diagnostics must not depend on it. NullAway has depended on it before: <a
 * href="https://github.com/uber/NullAway/issues/1725">#1725</a> and <a
 * href="https://github.com/uber/NullAway/issues/1733">#1733</a> both reported a generic inference
 * failure against whichever compilation unit happened to create the dataflow analysis rather than
 * against the file holding the call.
 *
 * <p>This class records the calls a test makes and, on {@link #doTest()}, replays them into a new
 * upstream helper with the sources in the given order. When the check is enabled and the test has
 * more than one source, it replays them again into another new helper with the sources reversed,
 * and that run checks the {@code // BUG: Diagnostic} markers of every file a second time. Reversing
 * the list costs one extra compilation per multi-file test, where checking every permutation would
 * cost <i>n!</i>.
 *
 * <p>Only the calls NullAway's tests make are recorded; a test that needs another method of the
 * upstream helper adds it here.
 *
 * <p>The {@code test} task enables the check through the {@code nullaway.permute.sources} system
 * property; {@code -PpermuteSources=false} disables it.
 */
public final class ReversingCompilationTestHelper {

  private final Supplier<CompilationTestHelper> factory;

  /** {@code addSourceLines} calls, in the order the test made them. */
  private final List<UnaryOperator<CompilationTestHelper>> sources = new ArrayList<>();

  /** Calls other than {@code addSourceLines}, in the order the test made them. */
  private final List<UnaryOperator<CompilationTestHelper>> settings = new ArrayList<>();

  private boolean run;

  private ReversingCompilationTestHelper(Supplier<CompilationTestHelper> factory) {
    this.factory = factory;
  }

  /** See {@link CompilationTestHelper#newInstance(Class, Class)}. */
  public static ReversingCompilationTestHelper newInstance(
      Class<? extends BugChecker> checker, Class<?> clazz) {
    return new ReversingCompilationTestHelper(
        () -> CompilationTestHelper.newInstance(checker, clazz));
  }

  /** See {@link CompilationTestHelper#newInstance(ScannerSupplier, Class)}. */
  public static ReversingCompilationTestHelper newInstance(
      ScannerSupplier scannerSupplier, Class<?> clazz) {
    return new ReversingCompilationTestHelper(
        () -> CompilationTestHelper.newInstance(scannerSupplier, clazz));
  }

  /** See {@link CompilationTestHelper#addSourceLines}. */
  public ReversingCompilationTestHelper addSourceLines(String path, String... lines) {
    sources.add(helper -> helper.addSourceLines(path, lines));
    return this;
  }

  /** See {@link CompilationTestHelper#setArgs(List)}. */
  public ReversingCompilationTestHelper setArgs(List<String> args) {
    settings.add(helper -> helper.setArgs(args));
    return this;
  }

  /** See {@link CompilationTestHelper#setArgs(String...)}. */
  public ReversingCompilationTestHelper setArgs(String... args) {
    return setArgs(asList(args));
  }

  /** See {@link CompilationTestHelper#expectErrorMessage}. */
  public ReversingCompilationTestHelper expectErrorMessage(
      String key, Predicate<? super String> matcher) {
    settings.add(helper -> helper.expectErrorMessage(key, matcher));
    return this;
  }

  /**
   * Compiles the sources in the given order and, when the check is enabled and there is more than
   * one source, in reverse order, asserting the test's expectations after each compilation.
   *
   * @throws AssertionError if either compilation fails the expectations; a failure of the reversed
   *     compilation says so in its message and carries the upstream failure as its cause
   */
  public void doTest() {
    checkState(!run, "doTest should only be called once");
    run = true;
    compile(sources);
    if (isEnabled() && sources.size() > 1) {
      try {
        compile(Lists.reverse(sources));
      } catch (AssertionError e) {
        throw new AssertionError(
            "The test passes with its sources in the given order and fails with them reversed;"
                + " a diagnostic depends on the order of the compilation units",
            e);
      }
    }
  }

  /** Replays the recorded calls into a new upstream helper, adding {@code order}, and runs it. */
  private void compile(List<UnaryOperator<CompilationTestHelper>> order) {
    CompilationTestHelper helper = factory.get();
    for (UnaryOperator<CompilationTestHelper> call : Iterables.concat(settings, order)) {
      helper = call.apply(helper);
    }
    helper.doTest();
  }

  /** Returns whether the reversed compilation runs in this JVM. */
  private static boolean isEnabled() {
    return Boolean.getBoolean("nullaway.permute.sources");
  }
}
