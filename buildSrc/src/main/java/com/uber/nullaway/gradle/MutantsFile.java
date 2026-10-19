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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Writes and reads the mutants of one PIT run as a text file, one mutant per line.
 *
 * <p>A line holds tab-separated fields: the status PIT gave the mutant, the mutated class, the
 * method, the source file the class records, the line number, the description, and then the tests
 * PIT recorded as covering the mutant, one per field. A tab or a line break inside a field is
 * written as a space.
 *
 * <p>The PIT listener in {@code com.uber.nullaway.gradle.pitest} writes the file inside the PIT
 * process and the build reads it, so this class depends on nothing but the JDK.
 */
public final class MutantsFile {

  /** The name of the file in PIT's report directory. */
  public static final String NAME = "mutants.tsv";

  /**
   * The {@code --outputFormats} name that makes PIT write the file.
   *
   * <p>It is declared here rather than beside the listener because the build reads it too, and the
   * build cannot load a class that implements a PIT interface.
   */
  public static final String LISTENER_FORMAT = "NULLAWAY_MUTANTS";

  /**
   * One mutant as the file holds it.
   *
   * @param status the PIT status name, such as {@code SURVIVED} or {@code NO_COVERAGE}
   * @param mutatedClass the binary name of the mutated class, such as {@code com.x.A$Inner}
   * @param tests the tests PIT recorded as covering the mutant, as PIT names them
   */
  public record Mutant(
      String status,
      String mutatedClass,
      String method,
      String sourceFile,
      int lineNumber,
      String description,
      List<String> tests) {}

  private MutantsFile() {}

  /** Returns the line the file holds for one mutant, without the line break. */
  public static String line(Mutant mutant) {
    List<String> fields = new ArrayList<>();
    fields.add(mutant.status());
    fields.add(mutant.mutatedClass());
    fields.add(mutant.method());
    fields.add(mutant.sourceFile());
    fields.add(Integer.toString(mutant.lineNumber()));
    fields.add(mutant.description());
    fields.addAll(mutant.tests());
    List<String> cleaned = new ArrayList<>();
    for (String field : fields) {
      cleaned.add(field.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
    }
    return String.join("\t", cleaned);
  }

  /** Returns the mutants the file holds, in the order PIT reported them. */
  public static List<Mutant> read(Path file) throws IOException {
    List<Mutant> mutants = new ArrayList<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (line.isEmpty()) {
        continue;
      }
      String[] fields = line.split("\t", -1);
      if (fields.length < 6) {
        throw new IOException("Fewer than 6 fields in " + file + " line: " + line);
      }
      mutants.add(
          new Mutant(
              fields[0],
              fields[1],
              fields[2],
              fields[3],
              Integer.parseInt(fields[4]),
              fields[5],
              List.copyOf(Arrays.asList(fields).subList(6, fields.length))));
    }
    return mutants;
  }
}
