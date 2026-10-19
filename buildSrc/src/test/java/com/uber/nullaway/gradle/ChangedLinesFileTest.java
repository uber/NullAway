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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pitest.util.Glob;

/**
 * A changed-lines file holds one source file per line, its changed lines written as comma-separated
 * ranges after a tab, and reads back to the line numbers it was written from.
 */
class ChangedLinesFileTest {

  @TempDir Path directory;

  private static NavigableSet<Integer> lines(Integer... numbers) {
    return new TreeSet<>(Arrays.asList(numbers));
  }

  @Test
  void consecutiveLinesCollapseIntoOneRange() {
    assertEquals("3-5", ChangedLinesFile.ranges(lines(3, 4, 5)));
  }

  @Test
  void twoConsecutiveLinesAreARange() {
    assertEquals("3-4", ChangedLinesFile.ranges(lines(3, 4)));
  }

  @Test
  void aGapStartsANewRange() {
    assertEquals("3,5-6,9", ChangedLinesFile.ranges(lines(3, 5, 6, 9)));
  }

  @Test
  void aFileReadsBackToTheLinesItWasWrittenFrom() throws IOException {
    Path file = directory.resolve("changed-lines.txt");
    Map<String, NavigableSet<Integer>> written =
        Map.of("a/B.java", lines(1, 2, 3, 7), "C.java", lines(12));

    ChangedLinesFile.write(file, List.of("against origin/master (abc)"), written);

    assertEquals(written, ChangedLinesFile.read(file));
  }

  @Test
  void aFileWithNoChangedLineIsLeftOut() throws IOException {
    Path file = directory.resolve("changed-lines.txt");

    ChangedLinesFile.write(
        file, List.of(), Map.of("a/Empty.java", lines(), "a/B.java", lines(4)));

    assertEquals(Map.of("a/B.java", lines(4)), ChangedLinesFile.read(file));
  }

  @Test
  void theHeaderReadsBackToTheFirstCommentLine() throws IOException {
    Path file = directory.resolve("changed-lines.txt");

    ChangedLinesFile.write(
        file, List.of("against origin/master (abc)", "second"), Map.of("B.java", lines(1)));

    assertEquals("against origin/master (abc)", ChangedLinesFile.readHeader(file));
  }

  /** Returns the classes among the names that PIT selects with the patterns for the changed lines. */
  private static List<String> targeted(Map<String, NavigableSet<Integer>> changed, String... names) {
    List<String> patterns = ChangedLinesFile.targetClasses(changed);
    return Arrays.stream(names)
        .filter(name -> patterns.stream().anyMatch(pattern -> new Glob(pattern).matches(name)))
        .toList();
  }

  /**
   * A second top-level class declared in a changed file, such as {@code Helper} in {@code A.java},
   * has a name of its own, so the whole package is targeted.
   */
  @Test
  void everyClassOfTheChangedFilesPackageIsTargetedAndNoOther() {
    assertEquals(
        List.of("com.x.A", "com.x.A$Inner", "com.x.Helper"),
        targeted(
            Map.of("com/x/A.java", lines(3)),
            "com.x.A",
            "com.x.A$Inner",
            "com.x.Helper",
            "com.x.sub.C",
            "com.y.B",
            "com.xy.D"));
  }

  @Test
  void aChangedFileInTheDefaultPackageTargetsOnlyThatPackage() {
    assertEquals(List.of("A", "Helper"), targeted(Map.of("A.java", lines(1)), "A", "Helper", "com.x.B"));
  }

  @Test
  void packageAndModuleInfoAreNotTargeted() {
    assertEquals(
        List.of(),
        ChangedLinesFile.targetClasses(
            Map.of("com/x/package-info.java", lines(1), "module-info.java", lines(2))));
  }

  @Test
  void commentsAndBlankLinesAreSkipped() throws IOException {
    Path file = directory.resolve("changed-lines.txt");
    Files.writeString(file, "# against origin/master\n\na/B.java\t3-5,9\n");

    assertEquals(Map.of("a/B.java", lines(3, 4, 5, 9)), ChangedLinesFile.read(file));
  }
}
