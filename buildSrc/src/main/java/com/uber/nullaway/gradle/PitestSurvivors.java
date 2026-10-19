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
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Renders the mutants a PIT run left alive, grouped by source file and line.
 *
 * <p>A mutant is live when its status is {@code SURVIVED} or {@code NO_COVERAGE}: the tests that
 * reached its line passed anyway, or no test reached it. {@code KILLED}, {@code TIMED_OUT}, and
 * {@code MEMORY_ERROR} mean the tests noticed the mutant and are counted as killed. Any other
 * status, such as {@code NON_VIABLE} for a mutant the JVM would not load, says nothing about the
 * tests and is counted under its own name.
 *
 * <p>Each line is printed once, with its source text, every live mutant on it, the number of tests
 * PIT recorded as reaching it, and the few of them that reach the most live mutants of the report.
 * The tests come from PIT's {@code linecoverage.xml}, which maps a block of a method to the tests
 * that executed it; a mutant names the blocks it sits in.
 */
public final class PitestSurvivors {

  /**
   * What the reader of a report with live mutants does with each of them.
   *
   * <p>The report carries it rather than the instructions every session loads, since only the
   * reader of a report needs it.
   */
  static final String WHAT_TO_DO =
      "For each live mutant, decide which case it is:\n"
          + "  - the tests miss behavior the change defines: add or strengthen a test,"
          + " next to the tests named for its line\n"
          + "  - no caller can observe the difference, so the mutant is equivalent:"
          + " say so in the pull request\n"
          + "  - you cannot tell: report it as unresolved";

  /**
   * What a run in which no test reached any changed line means.
   *
   * <p>Code no test of the module runs and code whose tests fail under PIT produce the same report,
   * so the reader is told both and sent to the list of known cases.
   */
  static final String NOTHING_REACHED =
      "No test reached any of the changed lines. Either no test of this module exercises this code,"
          + " or the tests that do failed under PIT, which leaves them out silently; the"
          + " \"Mutation testing\" section of CONTRIBUTING.md lists the known cases.";

  /**
   * Why a changed line can have no mutant, for a reader who expected one.
   *
   * <p>It reflects the {@code avoidCallsTo} list the pitestDiff task passes to PIT.
   */
  static final String AVOIDED_CALLS =
      "PIT generates no mutant on a line that calls Verify or Preconditions.";

  /** How many of the tests reaching a line are named. */
  static final int TESTS_SHOWN = 3;

  private static final Pattern DESCRIPTION_SUFFIX = Pattern.compile("\\s+for \\S+::\\S+$");

  private static final Pattern LAMBDA = Pattern.compile("lambda\\$(.+)\\$\\d+");

  /** One live mutant, as the report prints it. */
  private record Mutant(String description, String method, Set<String> tests) {}

  /** The live mutants of one source line, and the tests that reach it. */
  private record Line(int number, String source, List<Mutant> mutants, Set<String> tests) {}

  /** The live mutants of one source file. */
  private record FileMutants(String path, List<Line> lines) {
    int mutantCount() {
      return lines.stream().mapToInt(line -> line.mutants().size()).sum();
    }
  }

  private final String scope;
  private final Comparator<String> testOrder;
  private final Map<String, Integer> statusCounts;
  private final int total;
  private final List<FileMutants> files;

  private PitestSurvivors(
      String scope,
      Comparator<String> testOrder,
      Map<String, Integer> statusCounts,
      int total,
      List<FileMutants> files) {
    this.scope = scope;
    this.testOrder = testOrder;
    this.statusCounts = statusCounts;
    this.total = total;
    this.files = files;
  }

  /**
   * Reads a PIT run.
   *
   * @param scope what the run measured against, printed in the first line
   * @param mutationsXml the {@code mutations.xml} PIT wrote, or a missing file where PIT generated no
   *     mutant on the changed lines and wrote no report
   * @param lineCoverageXml the {@code linecoverage.xml} PIT wrote, or a missing file, in which
   *     case no test is named
   * @param repository the directory the printed paths are relative to
   * @param sourceRoots absolute source directories, searched in order for the mutated file
   */
  public static PitestSurvivors read(
      String scope,
      File mutationsXml,
      File lineCoverageXml,
      File repository,
      List<File> sourceRoots)
      throws IOException {
    if (!mutationsXml.isFile()) {
      return new PitestSurvivors(
          scope, Comparator.naturalOrder(), new LinkedHashMap<>(), 0, new ArrayList<>());
    }
    NodeList mutations = parse(mutationsXml).getElementsByTagName("mutation");
    Map<String, Integer> statusCounts = new LinkedHashMap<>();
    Map<String, TreeMap<Integer, List<Mutant>>> byFile = new HashMap<>();
    // The test sets of the live mutants, keyed by each block they sit in, for the coverage pass to
    // fill. A mutant in several blocks is reached by the tests of any of them.
    Map<String, List<Set<String>>> testSetsByBlock = new HashMap<>();
    for (int i = 0; i < mutations.getLength(); i++) {
      Element mutation = (Element) mutations.item(i);
      String status = mutation.getAttribute("status");
      statusCounts.merge(status, 1, Integer::sum);
      if (!isLive(status)) {
        continue;
      }
      String mutatedClass = text(mutation, "mutatedClass");
      String method = text(mutation, "mutatedMethod");
      Set<String> tests = new TreeSet<>();
      NodeList blocks = mutation.getElementsByTagName("block");
      for (int b = 0; b < blocks.getLength(); b++) {
        String key =
            blockKey(
                mutatedClass,
                method + text(mutation, "methodDescription"),
                blocks.item(b).getTextContent().trim());
        testSetsByBlock.computeIfAbsent(key, unused -> new ArrayList<>()).add(tests);
      }
      int packageEnd = mutatedClass.lastIndexOf('.');
      String relative =
          (packageEnd < 0 ? "" : mutatedClass.substring(0, packageEnd).replace('.', '/') + "/")
              + text(mutation, "sourceFile");
      String description =
          (status.equals("NO_COVERAGE") ? "no test reaches it: " : "survived: ")
              + DESCRIPTION_SUFFIX.matcher(text(mutation, "description")).replaceFirst("");
      byFile
          .computeIfAbsent(relative, unused -> new TreeMap<>())
          .computeIfAbsent(
              Integer.parseInt(text(mutation, "lineNumber")), unused -> new ArrayList<>())
          .add(new Mutant(description, readableMethod(method), tests));
    }
    if (lineCoverageXml.isFile() && !testSetsByBlock.isEmpty()) {
      addReachingTests(lineCoverageXml, testSetsByBlock);
    }
    List<FileMutants> files = new ArrayList<>();
    for (Map.Entry<String, TreeMap<Integer, List<Mutant>>> file : byFile.entrySet()) {
      File source = locate(sourceRoots, file.getKey());
      List<String> text = source == null ? List.of() : Files.readAllLines(source.toPath());
      List<Line> lines = new ArrayList<>();
      for (Map.Entry<Integer, List<Mutant>> line : file.getValue().entrySet()) {
        int number = line.getKey();
        Set<String> tests = new TreeSet<>();
        line.getValue().forEach(mutant -> tests.addAll(mutant.tests()));
        lines.add(
            new Line(
                number,
                number >= 1 && number <= text.size() ? text.get(number - 1).trim() : "",
                line.getValue(),
                tests));
      }
      files.add(
          new FileMutants(
              source == null ? file.getKey() : relativize(repository, source), lines));
    }
    files.sort(
        Comparator.comparingInt(FileMutants::mutantCount)
            .reversed()
            .thenComparing(FileMutants::path));
    // A line most of the suite reaches is reached by tests unrelated to the change, so the tests
    // named for it are the ones that reach the most live mutants of the whole report.
    Map<String, Integer> liveMutantsReached = new HashMap<>();
    for (TreeMap<Integer, List<Mutant>> lines : byFile.values()) {
      for (List<Mutant> mutants : lines.values()) {
        for (Mutant mutant : mutants) {
          mutant.tests().forEach(test -> liveMutantsReached.merge(test, 1, Integer::sum));
        }
      }
    }
    Comparator<String> testOrder =
        Comparator.<String>comparingInt(test -> liveMutantsReached.getOrDefault(test, 0))
            .reversed()
            .thenComparing(Comparator.naturalOrder());
    return new PitestSurvivors(scope, testOrder, statusCounts, mutations.getLength(), files);
  }

  /**
   * Writes the whole report to a file and returns the text to print, cut to the limits.
   *
   * <p>The printed text ends with the path of that file and of each path in {@code otherPaths}, so
   * that a reader of a cut report knows where the rest is.
   *
   * @param outputFile the file the whole report is written to
   * @param otherPaths further files to name after the report, relative to the repository; a path
   *     that names no file is not printed
   */
  public String writeAndRender(
      File repository,
      File outputFile,
      int maxFiles,
      int maxLinesPerFile,
      int maxLines,
      boolean showAll,
      List<String> otherPaths)
      throws IOException {
    List<String> paths = new ArrayList<>();
    paths.add(relativize(repository, outputFile));
    // A run that analyzed no mutant leaves no HTML report, and a path that names no file is left out.
    otherPaths = otherPaths.stream().filter(path -> new File(repository, path).exists()).toList();
    paths.addAll(otherPaths);
    Files.createDirectories(outputFile.toPath().getParent());
    Files.writeString(
        outputFile.toPath(), render(DiffCoverageReport.Limits.NONE, otherPaths) + "\n");
    return render(
        showAll
            ? DiffCoverageReport.Limits.NONE
            : new DiffCoverageReport.Limits(maxFiles, maxLinesPerFile, maxLines),
        paths);
  }

  /** Returns whether a mutant with this status went unnoticed by the tests. */
  private static boolean isLive(String status) {
    return status.equals("SURVIVED") || status.equals("NO_COVERAGE");
  }

  /**
   * Returns the report text.
   *
   * @param limits how much of the report to print; {@link DiffCoverageReport.Limits#NONE} prints
   *     it whole
   * @param fullReportPaths repository-relative paths of the files that hold more, printed last
   */
  String render(DiffCoverageReport.Limits limits, List<String> fullReportPaths) {
    StringBuilder out = new StringBuilder();
    out.append("Mutation testing of changed lines ").append(scope).append('\n');
    if (total == 0) {
      out.append("  no mutant: PIT generated none on the changed lines");
    } else {
      out.append("  ").append(total).append(total == 1 ? " mutant: " : " mutants: ").append(counts());
    }
    int liveLines = files.stream().mapToInt(file -> file.lines().size()).sum();
    if (liveLines > 0) {
      out.append("\n  ordered by live mutants per file, then by line");
    }
    if (total > 0 && statusCounts.getOrDefault("NO_COVERAGE", 0) == total) {
      out.append("\n\n").append(NOTHING_REACHED);
    }
    int filesLeft = limits.files;
    int rowsLeft = limits.lines;
    int filesShown = 0;
    for (FileMutants file : files) {
      if (filesLeft == 0 || rowsLeft == 0) {
        break;
      }
      filesLeft--;
      filesShown++;
      out.append("\n\n  ").append(file.path());
      int allowed = Math.min(limits.linesPerFile, rowsLeft);
      int shown = 0;
      for (Line line : file.lines()) {
        if (shown == allowed) {
          break;
        }
        shown++;
        appendLine(out, line);
      }
      rowsLeft -= shown;
      if (shown < file.lines().size()) {
        int more = file.lines().size() - shown;
        out.append("\n    ").append(more).append(more == 1 ? " more line" : " more lines");
      }
    }
    if (filesShown < files.size()) {
      int more = files.size() - filesShown;
      out.append("\n\n  ").append(more).append(more == 1 ? " more file" : " more files");
    }
    if (liveLines > 0) {
      out.append("\n\nLive mutants on ")
          .append(liveLines)
          .append(liveLines == 1 ? " line" : " lines")
          .append(" in ")
          .append(files.size())
          .append(files.size() == 1 ? " file." : " files.")
          .append("\n\n")
          .append(WHAT_TO_DO);
    }
    if (liveLines > 0 || total == 0) {
      out.append("\n\n").append(AVOIDED_CALLS);
    }
    if (!fullReportPaths.isEmpty()) {
      out.append("\n\nFull report:");
      for (String path : fullReportPaths) {
        out.append("\n  ").append(path);
      }
    }
    return out.toString();
  }

  /** The statuses that mean a test noticed the mutant. */
  private static final Set<String> KILLED = Set.of("KILLED", "TIMED_OUT", "MEMORY_ERROR");

  /** Returns the status counts, killed first, in the words the report uses. */
  private String counts() {
    int killed = 0;
    for (String status : KILLED) {
      killed += statusCounts.getOrDefault(status, 0);
    }
    List<String> parts = new ArrayList<>();
    parts.add(killed + " killed");
    parts.add(statusCounts.getOrDefault("SURVIVED", 0) + " survived");
    parts.add(statusCounts.getOrDefault("NO_COVERAGE", 0) + " not reached by any test");
    for (Map.Entry<String, Integer> entry : statusCounts.entrySet()) {
      if (!isLive(entry.getKey()) && !KILLED.contains(entry.getKey())) {
        parts.add(entry.getValue() + " " + entry.getKey());
      }
    }
    return String.join(", ", parts);
  }

  private void appendLine(StringBuilder out, Line line) {
    out.append("\n    ").append(line.number()).append(": ").append(line.source());
    for (Mutant mutant : line.mutants()) {
      out.append("\n        ").append(mutant.description()).append(", in ").append(mutant.method());
    }
    if (!line.tests().isEmpty()) {
      List<String> tests = new ArrayList<>(line.tests());
      tests.sort(testOrder);
      out.append("\n        reached by ")
          .append(tests.size())
          .append(tests.size() == 1 ? " test" : " tests")
          .append(tests.size() > TESTS_SHOWN ? ", among them:" : ":");
      for (String test : tests.subList(0, Math.min(TESTS_SHOWN, tests.size()))) {
        out.append("\n          ").append(test);
      }
    }
  }

  /** Returns the method name as a reader finds it in the source. */
  static String readableMethod(String method) {
    if (method.equals("<init>")) {
      return "a constructor";
    }
    if (method.equals("<clinit>")) {
      return "the static initializer";
    }
    Matcher lambda = LAMBDA.matcher(method);
    return lambda.matches() ? "a lambda in " + lambda.group(1) : method;
  }

  /**
   * Adds to each test set the tests that PIT's {@code linecoverage.xml} records for its block.
   *
   * <p>The file lists every block of the target classes with every test that executed it, which
   * for a change to a class most tests reach runs to tens of megabytes. It is streamed, and only
   * the blocks the map names are read, so memory holds the tests of the live mutants alone.
   *
   * <p>PIT names a test {@code pkg.Class.method(pkg.Class)}; the report keeps {@code Class.method},
   * which a {@code --tests '*Class.method'} filter takes and a search for the class finds.
   *
   * @param testSetsByBlock the sets to fill, keyed by {@link #blockKey}
   */
  static void addReachingTests(File lineCoverageXml, Map<String, List<Set<String>>> testSetsByBlock)
      throws IOException {
    XMLInputFactory factory = XMLInputFactory.newInstance();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try (InputStream input = Files.newInputStream(lineCoverageXml.toPath())) {
      XMLStreamReader reader = factory.createXMLStreamReader(input);
      try {
        List<Set<String>> current = null;
        while (reader.hasNext()) {
          if (reader.next() != XMLStreamConstants.START_ELEMENT) {
            continue;
          }
          if (reader.getLocalName().equals("block")) {
            current =
                testSetsByBlock.get(
                    blockKey(
                        reader.getAttributeValue(null, "classname"),
                        reader.getAttributeValue(null, "method"),
                        reader.getAttributeValue(null, "number")));
          } else if (current != null && reader.getLocalName().equals("test")) {
            String name = shortTestName(reader.getAttributeValue(null, "name"));
            for (Set<String> tests : current) {
              tests.add(name);
            }
          }
        }
      } finally {
        reader.close();
      }
    } catch (XMLStreamException e) {
      throw new IOException("Cannot parse " + lineCoverageXml, e);
    }
  }

  /** Returns {@code Class.method} for a test PIT names {@code pkg.Class.method(pkg.Class)}. */
  private static String shortTestName(String name) {
    int paren = name.indexOf('(');
    String qualified = paren < 0 ? name : name.substring(0, paren);
    int methodStart = qualified.lastIndexOf('.');
    int classStart = qualified.lastIndexOf('.', methodStart - 1);
    return qualified.substring(classStart + 1);
  }

  private static String blockKey(String className, String methodWithDescriptor, String block) {
    return className + "#" + methodWithDescriptor + "#" + block;
  }

  private static Document parse(File xml) throws IOException {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      return factory.newDocumentBuilder().parse(xml);
    } catch (ParserConfigurationException | SAXException e) {
      throw new IOException("Cannot parse " + xml, e);
    }
  }

  private static String text(Element parent, String tag) {
    NodeList nodes = parent.getElementsByTagName(tag);
    return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
  }

  /** Returns the first file under the source roots with this relative path, or null. */
  private static File locate(List<File> sourceRoots, String path) {
    for (File root : sourceRoots) {
      File source = new File(root, path);
      if (source.isFile()) {
        return source;
      }
    }
    return null;
  }

  private static String relativize(File repository, File file) {
    return repository.toPath().toAbsolutePath().normalize()
        .relativize(file.toPath().toAbsolutePath().normalize())
        .toString();
  }
}
