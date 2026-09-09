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

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A diff produced with {@code --unified=0} and the {@code a/} and {@code b/} prefixes yields the
 * post-image line numbers of every file it adds to or rewrites.
 *
 * <p>Each test spells the diff out rather than running git, so that the input the parser sees is
 * the input the reader sees. The shapes come from real git output; {@code UnifiedDiffFromGitTest}
 * checks that git still produces them.
 */
class UnifiedDiffTest {

  private static final String HEADERS = "diff --git a/a/B.java b/a/B.java\n--- a/a/B.java\n";

  private static Set<Integer> lines(Integer... numbers) {
    return new TreeSet<>(Arrays.asList(numbers));
  }

  @Nested
  class HunkHeaders {

    @Test
    void aHunkWithNoCountCoversTheOneLineItNames() {
      assertEquals(
          Map.of("a/B.java", lines(7)),
          UnifiedDiff.parse(HEADERS + "+++ b/a/B.java\n@@ -3 +7 @@\n+x\n"));
    }

    @Test
    void aHunkWithACountCoversThatManyLinesFromItsStart() {
      assertEquals(
          Map.of("a/B.java", lines(7, 8, 9)),
          UnifiedDiff.parse(HEADERS + "+++ b/a/B.java\n@@ -3,0 +7,3 @@\n+x\n+y\n+z\n"));
    }

    @Test
    void aHunkOfCountZeroDeletesAndSoCoversNoLine() {
      assertEquals(
          Map.of(), UnifiedDiff.parse(HEADERS + "+++ b/a/B.java\n@@ -3,2 +2,0 @@\n-x\n-y\n"));
    }

    @Test
    void aHunkHeaderCarryingAFunctionNameIsStillRead() {
      assertEquals(
          Map.of("a/B.java", lines(7)),
          UnifiedDiff.parse(HEADERS + "+++ b/a/B.java\n@@ -3 +7 @@ void probe() {\n+x\n"));
    }

    @Test
    void twoHunksOfOneFileCollectIntoOneLineSet() {
      assertEquals(
          Map.of("a/B.java", lines(7, 20, 21)),
          UnifiedDiff.parse(
              HEADERS + "+++ b/a/B.java\n@@ -3 +7 @@\n+x\n@@ -18,0 +20,2 @@\n+y\n+z\n"));
    }
  }

  @Nested
  class FileHeaders {

    @Test
    void hunksOfTwoFilesStayApart() {
      assertEquals(
          Map.of("a/B.java", lines(1), "a/C.java", lines(4)),
          UnifiedDiff.parse(
              HEADERS
                  + "+++ b/a/B.java\n@@ -1 +1 @@\n+x\n"
                  + "diff --git a/a/C.java b/a/C.java\n--- a/a/C.java\n"
                  + "+++ b/a/C.java\n@@ -1 +4 @@\n+y\n"));
    }

    @Test
    void aDeletedFileContributesNoLine() {
      assertEquals(
          Map.of(),
          UnifiedDiff.parse(HEADERS + "+++ /dev/null\n@@ -1,2 +0,0 @@\n-x\n-y\n"));
    }

    @Test
    void anAddedFileIsReadThroughTheDevNullSourceHeader() {
      assertEquals(
          Map.of("a/B.java", lines(1, 2)),
          UnifiedDiff.parse(
              "diff --git a/a/B.java b/a/B.java\n--- /dev/null\n"
                  + "+++ b/a/B.java\n@@ -0,0 +1,2 @@\n+x\n+y\n"));
    }

    /**
     * An added line reading {@code ++ x} arrives as {@code +++ x}, and reading that as a file
     * header used to key the rest of the file's hunks under the path {@code + x}, where no source
     * root claims them and they leave the report silently.
     */
    @Test
    void anAddedLineThatLooksLikeAFileHeaderStaysContentOfTheFileItIsIn() {
      assertEquals(
          Map.of("a/B.java", lines(7, 20)),
          UnifiedDiff.parse(
              HEADERS + "+++ b/a/B.java\n@@ -3 +7 @@\n+++ tricky\n@@ -18 +20 @@\n+y\n"));
    }

    /**
     * {@code ++ b/x.java} is the one added line the prefix check alone does not tell from a header,
     * so this is what the pairing with the {@code ---} line above it is for.
     */
    @Test
    void anAddedLineCarryingATargetPrefixStaysContentOfTheFileItIsIn() {
      assertEquals(
          Map.of("a/B.java", lines(7, 20)),
          UnifiedDiff.parse(
              HEADERS + "+++ b/a/B.java\n@@ -3 +7 @@\n+++ b/fake.java\n@@ -18 +20 @@\n+y\n"));
    }

    @Test
    void aDeletedLineThatLooksLikeASourceHeaderDoesNotOpenAFile() {
      assertEquals(
          Map.of("a/B.java", lines(7, 20)),
          UnifiedDiff.parse(
              HEADERS + "+++ b/a/B.java\n@@ -3 +7 @@\n--- a/decoy\n@@ -18 +20 @@\n+y\n"));
    }

    /**
     * Replacing a line {@code -- a/x} with a line {@code ++ b/y} puts a whole well-formed header
     * pair inside a hunk, which the pairing rule alone cannot tell from a real one; only the
     * {@code diff --git} line git opens a real header section with can.
     */
    @Test
    void aDeletedAndAnAddedLineFormingAHeaderPairDoNotOpenAFile() {
      assertEquals(
          Map.of("a/B.java", lines(7, 20)),
          UnifiedDiff.parse(
              HEADERS
                  + "+++ b/a/B.java\n@@ -3 +7 @@\n--- a/x\n+++ b/y\n@@ -18 +20 @@\n+y\n"));
    }

    /**
     * git prefixes content with {@code +}, {@code -}, or a space, and {@code diff} starts with
     * none of those, so a line whose own text is a {@code diff --git} header cannot arrive looking
     * like one. This is what the {@code ---} and {@code +++} shapes do not have.
     */
    @Test
    void anAddedLineWhoseTextIsADiffGitHeaderStaysContentOfTheFileItIsIn() {
      assertEquals(
          Map.of("a/B.java", lines(7, 20)),
          UnifiedDiff.parse(
              HEADERS
                  + "+++ b/a/B.java\n@@ -3 +7 @@\n+diff --git a/x b/x\n@@ -18 +20 @@\n+y\n"));
    }

    @Test
    void aHeaderPairWithNoDiffGitLineAboveItOpensNoFile() {
      assertEquals(
          Map.of(), UnifiedDiff.parse("--- a/a/B.java\n+++ b/a/B.java\n@@ -3 +7 @@\n+x\n"));
    }
  }

  @Nested
  class EmptyInput {

    @Test
    void anEmptyDiffNamesNoFile() {
      assertEquals(Map.of(), UnifiedDiff.parse(""));
    }

    @Test
    void aFileWithNoHunkIsNotNamed() {
      assertEquals(Map.of(), UnifiedDiff.parse(HEADERS + "+++ b/a/B.java\n"));
    }
  }
}
