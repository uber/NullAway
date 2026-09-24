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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** The per-line counters a set of JaCoCo XML reports records, keyed by package-qualified path. */
final class JacocoLines {

  /** Instruction and branch counters of one source line. */
  static final class Counters {
    final int coveredInstructions;
    final int coveredBranches;
    final int totalBranches;

    Counters(int coveredInstructions, int coveredBranches, int totalBranches) {
      this.coveredInstructions = coveredInstructions;
      this.coveredBranches = coveredBranches;
      this.totalBranches = totalBranches;
    }

    boolean isExecuted() {
      return coveredInstructions > 0;
    }

    boolean hasUntakenBranch() {
      return totalBranches > 0 && coveredBranches < totalBranches;
    }
  }

  private final Map<String, Map<Integer, Counters>> bySourceFile;

  private JacocoLines(Map<String, Map<Integer, Counters>> bySourceFile) {
    this.bySourceFile = bySourceFile;
  }

  /**
   * Returns the counters the given reports record, merged by taking the larger of each counter.
   *
   * <p>JaCoCo numbers no branch, so two reports covering the same line give no way to tell one
   * branch reached twice from two branches reached once. Taking the larger counter reports a branch
   * one report reached and another did not as partly taken, which asks for a test that already
   * exists rather than hiding one that is missing.
   *
   * @throws IOException if a report cannot be read or is not well-formed XML
   */
  static JacocoLines read(List<File> reports) throws IOException {
    DocumentBuilder builder;
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // A JaCoCo report declares report.dtd as a relative system id, and JaCoCo writes no such
      // file beside it, so a parser that loads it fails with FileNotFoundException.
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      builder = factory.newDocumentBuilder();
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException("the platform XML parser rejected its own configuration", e);
    }
    Map<String, Map<Integer, Counters>> merged = new HashMap<>();
    for (File report : reports) {
      NodeList packages;
      try {
        packages = builder.parse(report).getElementsByTagName("package");
      } catch (SAXException e) {
        throw new IOException("cannot parse the JaCoCo report at " + report, e);
      }
      for (int p = 0; p < packages.getLength(); p++) {
        Element packageElement = (Element) packages.item(p);
        String packageName = packageElement.getAttribute("name");
        NodeList sourceFiles = packageElement.getElementsByTagName("sourcefile");
        for (int s = 0; s < sourceFiles.getLength(); s++) {
          Element sourceFile = (Element) sourceFiles.item(s);
          Map<Integer, Counters> lines =
              merged.computeIfAbsent(
                  packageName + "/" + sourceFile.getAttribute("name"), unused -> new HashMap<>());
          NodeList lineElements = sourceFile.getElementsByTagName("line");
          for (int l = 0; l < lineElements.getLength(); l++) {
            Element line = (Element) lineElements.item(l);
            int number = attribute(line, "nr");
            int coveredBranches = attribute(line, "cb");
            Counters previous = lines.get(number);
            Counters current =
                new Counters(
                    attribute(line, "ci"),
                    coveredBranches,
                    coveredBranches + attribute(line, "mb"));
            lines.put(number, previous == null ? current : larger(previous, current));
          }
        }
      }
    }
    return new JacocoLines(merged);
  }

  /** Returns the counters recorded for one line, or null where the report mentions no such line. */
  Counters get(String sourceFileKey, int line) {
    Map<Integer, Counters> lines = bySourceFile.get(sourceFileKey);
    return lines == null ? null : lines.get(line);
  }

  /** Returns whether any report read here mentions the given package-qualified source path. */
  boolean covers(String sourceFileKey) {
    return bySourceFile.containsKey(sourceFileKey);
  }

  private static Counters larger(Counters first, Counters second) {
    return new Counters(
        Math.max(first.coveredInstructions, second.coveredInstructions),
        Math.max(first.coveredBranches, second.coveredBranches),
        Math.max(first.totalBranches, second.totalBranches));
  }

  private static int attribute(Element element, String name) {
    String value = element.getAttribute(name);
    return value.isEmpty() ? 0 : Integer.parseInt(value);
  }
}
