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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A line of the working tree comes back as the file holds it, and a line that is not there comes
 * back empty.
 *
 * <p>The report quotes what this returns beside counters it has already taken from JaCoCo, so a
 * file it cannot read costs the quote and not the measurement.
 */
class SourceLinesTest {

  private static final String PATH = "src/main/java/A.java";

  @TempDir Path directory;

  /** Writes the sample file with the given lines, the first of which is line 1. */
  private void write(String... lines) throws IOException {
    Path file = directory.resolve(PATH);
    Files.createDirectories(file.getParent());
    Files.writeString(file, String.join("\n", lines) + "\n");
  }

  private SourceLines workingTree() {
    return SourceLines.under(directory.toFile());
  }

  /** The report strips and cuts the text itself, so it arrives here as the file holds it. */
  @Test
  void aLineComesBackWithItsIndentation() throws IOException {
    write("class A {", "    return null;");
    assertEquals("    return null;", workingTree().get(PATH, 2));
  }

  /**
   * An edit that shortens a file after the run leaves the report naming lines past its end, which
   * is the case the stale-source warning is printed for.
   */
  @Test
  void aNumberPastTheEndOfTheFileHasNoText() throws IOException {
    write("class A {", "}");
    assertEquals("", workingTree().get(PATH, 7));
  }

  @Test
  void theLineBeforeTheFirstHasNoText() throws IOException {
    write("class A {}");
    assertEquals("", workingTree().get(PATH, 0));
  }

  @Test
  void aFileThatIsNotThereHasNoText() {
    assertEquals("", workingTree().get(PATH, 1));
  }

  @Test
  void aFileThisJvmCannotDecodeHasNoText() throws IOException {
    Path file = directory.resolve(PATH);
    Files.createDirectories(file.getParent());
    Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28, '\n'});
    assertEquals("", workingTree().get(PATH, 1));
  }

  /**
   * A report quotes many lines of the same file, and each read of a source file competes with the
   * test run that produced the report for the same disk.
   */
  @Test
  void aFileIsReadOnceHoweverManyOfItsLinesAreQuoted() throws IOException {
    write("class A {", "    return null;");
    SourceLines sources = workingTree();
    sources.get(PATH, 1);
    Files.delete(directory.resolve(PATH));
    assertEquals("    return null;", sources.get(PATH, 2));
  }
}
