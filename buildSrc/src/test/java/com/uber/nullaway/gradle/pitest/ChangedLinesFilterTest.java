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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.uber.nullaway.gradle.ChangedLinesFile;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.ServiceLoader;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.pitest.classinfo.ClassName;
import org.pitest.mutationtest.build.InterceptorType;
import org.pitest.mutationtest.build.MutationInterceptorFactory;
import org.pitest.mutationtest.engine.Location;
import org.pitest.mutationtest.engine.MutationDetails;
import org.pitest.mutationtest.engine.MutationIdentifier;

/**
 * A mutant is kept when its line is a changed line of the source file its class records, found
 * through the package of the class and the file name.
 */
class ChangedLinesFilterTest {

  private static final ChangedLinesFilter FILTER =
      new ChangedLinesFilter(
          Map.of("com/x/A.java", lines(10, 11), "B.java", lines(5)));

  private static NavigableSet<Integer> lines(Integer... numbers) {
    return new TreeSet<>(List.of(numbers));
  }

  private static MutationDetails mutant(String className, String file, int line) {
    Location location = new Location(ClassName.fromString(className), "m", "()V");
    return new MutationDetails(
        new MutationIdentifier(location, 0, "NEGATE_CONDITIONALS"), file, "negated", line, 0);
  }

  /** Returns the lines of the mutants the filter keeps out of one mutant at the given line. */
  private static List<Integer> kept(String className, String file, int line) {
    return FILTER.intercept(List.of(mutant(className, file, line)), null).stream()
        .map(MutationDetails::getLineNumber)
        .toList();
  }

  @Test
  void aMutantOnAChangedLineIsKept() {
    assertEquals(List.of(11), kept("com.x.A", "A.java", 11));
  }

  @Test
  void aMutantOnTheLineAfterTheChangeIsDropped() {
    assertEquals(List.of(), kept("com.x.A", "A.java", 12));
  }

  @Test
  void aMutantOnTheLineBeforeTheChangeIsDropped() {
    assertEquals(List.of(), kept("com.x.A", "A.java", 9));
  }

  /**
   * PIT's pre-scan runs only {@code PRE_SCAN_*} interceptors and skips the coverage pass when it
   * finds no mutant, so a filter of another type leaves a change with no mutable line paying for a
   * run of the whole suite.
   */
  @Test
  void theFilterRunsInThePreScan() {
    assertEquals(InterceptorType.PRE_SCAN_FILTER, FILTER.type());
  }

  @Test
  void pitFindsTheFactoryThroughServiceLoader() {
    List<String> factories = new ArrayList<>();
    ServiceLoader.load(MutationInterceptorFactory.class)
        .forEach(factory -> factories.add(factory.getClass().getName()));

    assertTrue(
        factories.contains(ChangedLinesFilterFactory.class.getName()),
        () -> "registered factories: " + factories);
  }

  /** Without the file, the factory would have no lines to keep, and PIT would mutate every line. */
  @Test
  void theFactoryRefusesToStartWithoutTheChangedLinesFile() {
    String previous = System.clearProperty(ChangedLinesFile.PITEST_PROPERTY);
    try {
      assertThrows(
          IllegalStateException.class,
          () -> new ChangedLinesFilterFactory().createInterceptor(null));
    } finally {
      if (previous != null) {
        System.setProperty(ChangedLinesFile.PITEST_PROPERTY, previous);
      }
    }
  }

  @Test
  void aNestedClassIsMatchedThroughTheFileItRecords() {
    assertEquals(List.of(10), kept("com.x.A$Inner", "A.java", 10));
  }

  @Test
  void aFileOfTheSameNameInAnotherPackageIsDropped() {
    assertEquals(List.of(), kept("com.y.A", "A.java", 10));
  }

  @Test
  void aClassInTheDefaultPackageIsMatchedByFileNameAlone() {
    assertEquals(List.of(5), kept("B", "B.java", 5));
  }
}
