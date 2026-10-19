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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A mutants file holds one mutant per line and reads back to the mutants it was written from. */
class MutantsFileTest {

  @TempDir Path directory;

  @Test
  void aMutantWithItsTestsReadsBackUnchanged() throws IOException {
    MutantsFile.Mutant mutant =
        new MutantsFile.Mutant(
            "SURVIVED",
            "com.x.A$Inner",
            "m",
            "A.java",
            12,
            "negated conditional",
            List.of("com.x.T.a(com.x.T)", "com.x.T.b(com.x.T)"));
    Path file = directory.resolve(MutantsFile.NAME);

    Files.writeString(file, MutantsFile.line(mutant) + "\n");

    assertEquals(List.of(mutant), MutantsFile.read(file));
  }

  @Test
  void aTabInADescriptionIsWrittenAsASpace() {
    assertEquals(
        "KILLED\tcom.x.A\tm\tA.java\t3\tremoved call to a b",
        MutantsFile.line(
            new MutantsFile.Mutant(
                "KILLED", "com.x.A", "m", "A.java", 3, "removed call to a\tb", List.of())));
  }
}
