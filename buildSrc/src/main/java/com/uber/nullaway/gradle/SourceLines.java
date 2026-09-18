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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The text of the working tree's sources, read at most once per file.
 *
 * <p>The report quotes a line beside its counters, so that the reader sees the code to test without
 * opening the file, and reads the same text to decide whether the line carries an ignore marker.
 */
final class SourceLines {

  private final File repositoryRoot;

  private final Map<String, List<String>> byPath = new HashMap<>();

  private SourceLines(File repositoryRoot) {
    this.repositoryRoot = repositoryRoot;
  }

  /** Returns the text of the files under the given repository, each read as it is asked for. */
  static SourceLines under(File repositoryRoot) {
    return new SourceLines(repositoryRoot);
  }

  /**
   * Returns one line as the working tree holds it, or an empty string where there is no such line.
   *
   * <p>A file this JVM cannot read or decode, and a number past the end of the file, both give an
   * empty string. The counters beside the quote are the report's subject, so a line that cannot be
   * quoted is still reported, without its text.
   *
   * @param path repository-relative path, as a git diff spells it
   * @param number 1-based line number, as JaCoCo numbers lines
   */
  String get(String path, int number) {
    List<String> lines = byPath.computeIfAbsent(path, this::read);
    return number >= 1 && number <= lines.size() ? lines.get(number - 1) : "";
  }

  private List<String> read(String path) {
    try {
      return Files.readAllLines(repositoryRoot.toPath().resolve(path));
    } catch (IOException | InvalidPathException e) {
      return List.of();
    }
  }
}
