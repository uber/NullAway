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

import com.uber.nullaway.gradle.ChangedLinesFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.NavigableSet;
import org.pitest.mutationtest.build.InterceptorParameters;
import org.pitest.mutationtest.build.MutationInterceptor;
import org.pitest.mutationtest.build.MutationInterceptorFactory;
import org.pitest.plugin.Feature;

/**
 * Creates a {@link ChangedLinesFilter} over the {@link ChangedLinesFile} that the system property
 * {@value #PROPERTY} names.
 *
 * <p>PIT finds this factory through {@link java.util.ServiceLoader} on its own classpath, and only
 * the {@code pitestDiff} task puts it there, together with the property. The feature is on by
 * default so that the task need not also pass {@code --features}, and a missing property fails
 * the run rather than letting it mutate every line of the target classes.
 */
public final class ChangedLinesFilterFactory implements MutationInterceptorFactory {

  private static final String PROPERTY = ChangedLinesFile.PITEST_PROPERTY;

  @Override
  public String description() {
    return "Keeps only the mutants on lines the branch changed";
  }

  @Override
  public Feature provides() {
    return Feature.named("NULLAWAY_CHANGED_LINES")
        .withOnByDefault(true)
        .withDescription(description() + ", as listed in the file -D" + PROPERTY + " names");
  }

  @Override
  public MutationInterceptor createInterceptor(InterceptorParameters params) {
    String file = System.getProperty(PROPERTY);
    if (file == null) {
      throw new IllegalStateException(
          "System property " + PROPERTY + " is not set; it must name the changed-lines file");
    }
    Map<String, NavigableSet<Integer>> lines;
    try {
      lines = ChangedLinesFile.read(Path.of(file));
    } catch (IOException e) {
      throw new UncheckedIOException("Cannot read the changed-lines file " + file, e);
    }
    return new ChangedLinesFilter(lines);
  }
}
