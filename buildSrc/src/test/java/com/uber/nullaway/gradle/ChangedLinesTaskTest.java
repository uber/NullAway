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
package com.uber.nullaway.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The changed-lines file of a module keeps the files under its source roots, keyed by the path
 * relative to the root that holds them.
 */
class ChangedLinesTaskTest {

  private static final NavigableSet<Integer> LINES = new TreeSet<>(List.of(4));

  @Test
  void aFileUnderASourceRootIsKeyedByItsPathBelowTheRoot() {
    assertEquals(
        Map.of("com/x/A.java", LINES),
        ChangedLinesTask.underSourceRoots(
            Map.of("m/src/main/java/com/x/A.java", LINES), List.of("m/src/main/java")));
  }

  @Test
  void aFileOutsideEverySourceRootIsLeftOut() {
    assertEquals(
        Map.of(),
        ChangedLinesTask.underSourceRoots(
            Map.of("m/src/test/java/com/x/ATest.java", LINES), List.of("m/src/main/java")));
  }

  @Test
  void aRootIsMatchedOnlyAsAWholeDirectory() {
    assertEquals(
        Map.of(),
        ChangedLinesTask.underSourceRoots(
            Map.of("m/src/main/java2/com/x/A.java", LINES), List.of("m/src/main/java")));
  }
}
