package com.uber.nullaway.oracles;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads seeds from Java sources outside the NullAway tests, such as the JSpecify samples and the
 * javac tests in the JDK repository.
 *
 * <p>A unit's path comes from its package and its first public type, as {@link #pathOf} derives it,
 * so that javac accepts the unit wherever the file lies. A seed that mentions {@code
 * NullnessUnspecified}, which the JSpecify samples use and the JSpecify 1.0 jar lacks, gets one
 * more unit that declares that annotation.
 */
final class DirectorySeeds {

  /** A declaration of {@code @NullnessUnspecified} as a type-use annotation with no members. */
  private static final Source NULLNESS_UNSPECIFIED =
      new Source(
          "org/jspecify/annotations/NullnessUnspecified.java",
          """
          package org.jspecify.annotations;
          import java.lang.annotation.ElementType;
          import java.lang.annotation.Retention;
          import java.lang.annotation.RetentionPolicy;
          import java.lang.annotation.Target;
          @Retention(RetentionPolicy.RUNTIME)
          @Target(ElementType.TYPE_USE)
          public @interface NullnessUnspecified {}
          """);

  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

  private static final Pattern PUBLIC_TYPE =
      Pattern.compile(
          "\\bpublic\\s+(?:(?:final|abstract|sealed|non-sealed|static|strictfp)\\s+|@[\\w.]+\\s+)*"
              + "(?:class|interface|enum|record|@interface)\\s+(\\w+)");

  private DirectorySeeds() {}

  /**
   * Returns {@code <package dirs>/<first public type>.java} for {@code text}, or {@code <package
   * dirs>/<fileName>} when the unit declares no public type, so that units of one seed keep
   * distinct paths.
   */
  static String pathOf(String text, String fileName) {
    Matcher pkg = PACKAGE.matcher(text);
    Matcher type = PUBLIC_TYPE.matcher(text);
    String dir = pkg.find() ? pkg.group(1).replace('.', '/') + "/" : "";
    return dir + (type.find() ? type.group(1) + ".java" : fileName);
  }

  /**
   * Returns one seed per {@code .java} file under {@code root}, at any depth, in path order. With
   * {@code nullMarked}, each seed is followed by its {@link AnnotationMutator#nullMarked} variant
   * when that differs.
   */
  static ImmutableList<Seed> perFile(Path root, boolean nullMarked) {
    ImmutableList.Builder<Seed> seeds = ImmutableList.builder();
    for (Path file : javaFiles(root, Integer.MAX_VALUE)) {
      Seed seed = seed(root.relativize(file).toString(), List.of(file));
      seeds.add(seed);
      if (nullMarked) {
        Seed marked = AnnotationMutator.nullMarked(seed);
        if (!marked.sources().equals(seed.sources())) {
          seeds.add(new Seed(seed.name() + " null-marked", marked.sources()));
        }
      }
    }
    return seeds.build();
  }

  /**
   * Returns the seeds of a directory laid out as the JSpecify samples are: each {@code .java} file
   * directly in {@code root} is one seed, and each subdirectory is one seed of every {@code .java}
   * file under it.
   */
  static ImmutableList<Seed> samples(Path root) {
    ImmutableList.Builder<Seed> seeds = ImmutableList.builder();
    for (Path file : javaFiles(root, 1)) {
      seeds.add(seed(file.getFileName().toString(), List.of(file)));
    }
    try (Stream<Path> children = Files.list(root)) {
      for (Path dir : children.filter(Files::isDirectory).sorted().toList()) {
        List<Path> files = javaFiles(dir, Integer.MAX_VALUE);
        if (!files.isEmpty()) {
          seeds.add(seed(dir.getFileName() + "/", files));
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return seeds.build();
  }

  /**
   * Returns the {@code .java} files under {@code root}, at most {@code depth} levels down, sorted.
   */
  private static List<Path> javaFiles(Path root, int depth) {
    try (Stream<Path> walk = Files.walk(root, depth)) {
      return walk.filter(p -> p.toString().endsWith(".java") && Files.isRegularFile(p))
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Returns a seed named {@code name} of {@code files}, plus a declaration of {@code
   * NullnessUnspecified} if one of them mentions it.
   */
  private static Seed seed(String name, List<Path> files) {
    ImmutableList.Builder<Source> sources = ImmutableList.builder();
    boolean unspecified = false;
    for (Path file : files) {
      String text;
      try {
        // Some javac tests are not UTF-8; a replaced character only makes the unit invalid.
        text = new String(Files.readAllBytes(file), UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      unspecified |= text.contains("NullnessUnspecified");
      sources.add(new Source(pathOf(text, file.getFileName().toString()), text));
    }
    if (unspecified) {
      sources.add(NULLNESS_UNSPECIFIED);
    }
    return new Seed(name, sources.build());
  }
}
