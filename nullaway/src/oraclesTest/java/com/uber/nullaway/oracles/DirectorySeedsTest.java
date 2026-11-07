package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A unit read from a directory gets a path javac accepts for its public type, a sample directory
 * becomes one seed, and a seed that mentions {@code NullnessUnspecified} gets a declaration of it.
 */
public class DirectorySeedsTest {

  @Rule public final TemporaryFolder folder = new TemporaryFolder();

  private Path write(String relative, String text) throws IOException {
    Path file = folder.getRoot().toPath().resolve(relative);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, text);
  }

  @Test
  public void aPublicTypeInAPackageGetsItsOwnPath() {
    assertThat(
            DirectorySeeds.pathOf(
                "package com.uber;\nimport x.Y;\npublic final class Foo {}", "Bar.java"))
        .isEqualTo("com/uber/Foo.java");
  }

  @Test
  public void aUnitWithoutAPublicTypeKeepsItsFileName() {
    assertThat(DirectorySeeds.pathOf("package p;\nclass Foo {}", "Bar.java"))
        .isEqualTo("p/Bar.java");
  }

  @Test
  public void aSampleDirectoryIsOneSeedBesideEachTopLevelFile() throws IOException {
    write("Top.java", "class Top {}");
    write("multi/p/A.java", "package p; public class A {}");
    write("multi/p/B.java", "package p; public class B extends A {}");
    assertThat(
            DirectorySeeds.samples(folder.getRoot().toPath()).stream()
                .map(s -> s.name() + " " + s.sources().stream().map(Source::path).toList())
                .toList())
        .containsExactly("Top.java [Top.java]", "multi/ [p/A.java, p/B.java]")
        .inOrder();
  }

  @Test
  public void unitsWithoutPublicTypesInOnePackageKeepDistinctPaths() throws IOException {
    // They were all named p/Test.java, and relations mapped every unit to the first of them.
    write("dir/p/A.java", "package p; class A {}");
    write("dir/p/B.java", "package p; class B {}");
    assertThat(
            DirectorySeeds.samples(folder.getRoot().toPath()).get(0).sources().stream()
                .map(Source::path)
                .toList())
        .containsExactly("p/A.java", "p/B.java");
  }

  @Test
  public void aSeedThatMentionsNullnessUnspecifiedGetsItsDeclaration() throws IOException {
    write("U.java", "class U { @NullnessUnspecified Object o; }");
    assertThat(DirectorySeeds.samples(folder.getRoot().toPath()).get(0).sources().get(1).path())
        .isEqualTo("org/jspecify/annotations/NullnessUnspecified.java");
  }

  @Test
  public void aNullMarkedVariantFollowsEachFileThatLacksTheAnnotation() throws IOException {
    write("T.java", "class T {}");
    assertThat(
            DirectorySeeds.perFile(folder.getRoot().toPath(), true).stream()
                .map(s -> s.name() + ": " + s.sources().get(0).text())
                .toList())
        .containsExactly(
            "T.java: class T {}",
            "T.java null-marked: " + "@org.jspecify.annotations.NullMarked class T {}")
        .inOrder();
  }
}
