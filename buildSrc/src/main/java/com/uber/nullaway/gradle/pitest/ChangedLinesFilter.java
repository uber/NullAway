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
package com.uber.nullaway.gradle.pitest;

import java.util.Collection;
import java.util.Map;
import java.util.NavigableSet;
import java.util.stream.Collectors;
import org.pitest.bytecode.analysis.ClassTree;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.engine.Mutater;
import org.pitest.mutationtest.engine.MutationDetails;

/**
 * Drops every mutant whose line the branch did not change.
 *
 * <p>A mutant is matched to its source file through the package of its class and the file name
 * the class records, which is how a {@link com.uber.nullaway.gradle.ChangedLinesFile} keys its
 * entries. PIT numbers a mutant by the line of the instruction it changes, so a statement that
 * spans several lines keeps its mutants only where the changed line holds that instruction.
 *
 * <p>The filter runs in PIT's pre-scan as well as in the main scan. A dropped mutant costs no test
 * run, and where no mutant is left on any changed line, PIT skips its coverage pass and writes no
 * report.
 */
final class ChangedLinesFilter implements MutationInterceptor {

  private final Map<String, NavigableSet<Integer>> changed;

  /**
   * @param changed the changed line numbers, keyed by path relative to the source root
   */
  ChangedLinesFilter(Map<String, NavigableSet<Integer>> changed) {
    this.changed = changed;
  }

  @Override
  public InterceptorType type() {
    return InterceptorType.PRE_SCAN_FILTER;
  }

  @Override
  public void begin(ClassTree clazz) {}

  @Override
  public Collection<MutationDetails> intercept(
      Collection<MutationDetails> mutations, Mutater mutater) {
    return mutations.stream().filter(this::onChangedLine).collect(Collectors.toList());
  }

  @Override
  public void end() {}

  private boolean onChangedLine(MutationDetails mutation) {
    String packagePath = mutation.getClassName().getPackage().asInternalName();
    String path =
        packagePath.isEmpty()
            ? mutation.getFilename()
            : packagePath + "/" + mutation.getFilename();
    NavigableSet<Integer> lines = changed.get(path);
    return lines != null && lines.contains(mutation.getLineNumber());
  }
}
