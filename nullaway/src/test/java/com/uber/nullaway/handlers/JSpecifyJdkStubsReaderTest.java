/*
 * Copyright (C) 2026. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.nullaway.handlers;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.Annotation.NONNULL;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.Annotation.NULLABLE;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.ARRAY_ELEMENT;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.TYPE_ARGUMENT;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.WILDCARD_BOUND;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSetMultimap;
import com.uber.nullaway.libmodel.NestedAnnotationInfo;
import com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class JSpecifyJdkStubsReaderTest {

  private static final String NULLABLE_NAME = "org.jspecify.annotations.Nullable";
  private static final String NON_NULL_NAME = "org.jspecify.annotations.NonNull";

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void methodKeysRenderTypesAsJavacRendersMethodSymbols() throws IOException {
    StubxCacheUtil cache =
        readStubs(
            "p/Box.java",
            """
            package p;
            import java.util.List;
            import java.util.Map;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            public class Box<T> {
              public @Nullable T get() { return null; }
              public <C extends Comparable<C>> @Nullable C max(List<?> items) { return null; }
              public @Nullable String größe() { return null; }
              public <K> Map.@Nullable Entry<K, ? super T> entry(K key) { return null; }
              public void log(String format, Object @Nullable ... args) {}
              public void fill(int @Nullable [][] grid) {}
              public void merge(@Nullable String key, @NonNull String value) {}
              public String unannotated(String s) { return s; }
            }
            """);

    assertThat(cache.getArgAnnotCache().get("p.Box"))
        .containsExactly(
            "p.Box:T get()",
            Map.of(-1, Set.of(NULLABLE_NAME)),
            "p.Box:C <C>max(java.util.List<?>)",
            Map.of(-1, Set.of(NULLABLE_NAME)),
            "p.Box:java.lang.String größe()",
            Map.of(-1, Set.of(NULLABLE_NAME)),
            "p.Box:java.util.Map.Entry<K,? super T> <K>entry(K)",
            Map.of(-1, Set.of(NULLABLE_NAME)),
            "p.Box:void log(java.lang.String,java.lang.Object...)",
            Map.of(1, Set.of(NULLABLE_NAME)),
            "p.Box:void fill(int[][])",
            Map.of(0, Set.of(NULLABLE_NAME)),
            "p.Box:void merge(java.lang.String,java.lang.String)",
            Map.of(0, Set.of(NULLABLE_NAME), 1, Set.of(NON_NULL_NAME)));
  }

  @Test
  public void aMethodNameLongerThan255BytesIsReadWhole() throws IOException {
    String name = "m".repeat(300);
    StubxCacheUtil cache =
        readStubs(
            "p/Box.java",
            """
            package p;
            import org.jspecify.annotations.Nullable;
            public class Box {
              public @Nullable String %s() { return null; }
            }
            """
                .formatted(name));

    assertThat(cache.getArgAnnotCache().get("p.Box").keySet())
        .containsExactly("p.Box:java.lang.String " + name + "()");
  }

  @Test
  public void annotationsOtherThanJSpecifyOnesAreIgnored() throws IOException {
    StubxCacheUtil cache =
        readStubs(
            "p/Box.java",
            """
            package p;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import org.jspecify.annotations.Nullable;
            public class Box {
              @Target(ElementType.TYPE_USE)
              @Retention(RetentionPolicy.RUNTIME)
              @interface Tag {}
              public void accept(@Tag String tagged, @Nullable String nullable) {}
            }
            """);

    assertThat(cache.getArgAnnotCache())
        .containsExactly(
            "p.Box",
            Map.of(
                "p.Box:void accept(java.lang.String,java.lang.String)",
                Map.of(1, Set.of(NULLABLE_NAME))));
  }

  @Test
  public void classFileContentsAroundTheAnnotationsAreSkipped() throws IOException {
    StubxCacheUtil cache =
        readStubs(
            "p/Box.java",
            """
            package p;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import java.util.List;
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;
            @Box.Meta(
                text = "text",
                kind = ElementType.FIELD,
                nested = @Retention(RetentionPolicy.CLASS),
                numbers = {1, 2},
                type = String.class)
            @NullMarked
            public class Box implements java.io.Serializable, Runnable {
              @Target({ElementType.TYPE, ElementType.TYPE_USE})
              @Retention(RetentionPolicy.RUNTIME)
              @interface Meta {
                String text();
                ElementType kind();
                Retention nested();
                int[] numbers();
                Class<?> type();
              }
              @Override
              public void run() {}
              public @Meta(
                      text = "text",
                      kind = ElementType.FIELD,
                      nested = @Retention(RetentionPolicy.CLASS),
                      numbers = {1, 2},
                      type = String.class)
                  @Nullable Object constants() {
                Runnable lambda = () -> {};
                return List.of("text", 1L << 40, 1.5, lambda, (Runnable) this::run);
              }
            }
            """);

    assertThat(cache.getNullMarkedClassesCache()).containsExactly("p.Box");
    assertThat(cache.getArgAnnotCache())
        .containsExactly(
            "p.Box",
            Map.of("p.Box:java.lang.Object constants()", Map.of(-1, Set.of(NULLABLE_NAME))));
  }

  @Test
  public void constructorKeysUseTheSimpleNameAndOmitTheEnclosingInstanceAndTheResult()
      throws IOException {
    StubxCacheUtil cache =
        readStubs(
            "p/Outer.java",
            """
            package p;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            public class Outer {
              public static class Nested {
                public @Nullable Nested(@Nullable String s) {}
              }
              public class Inner {
                public @Nullable Inner(@Nullable String s) {}
                public Inner(List<String> list, @Nullable String s) {}
              }
            }
            """);

    assertThat(cache.getArgAnnotCache())
        .containsExactly(
            "p.Outer.Nested",
            Map.of("p.Outer.Nested: Nested(java.lang.String)", Map.of(0, Set.of(NULLABLE_NAME))),
            "p.Outer.Inner",
            Map.of(
                "p.Outer.Inner: Inner(java.lang.String)",
                Map.of(0, Set.of(NULLABLE_NAME)),
                "p.Outer.Inner: Inner(java.util.List<java.lang.String>,java.lang.String)",
                Map.of(1, Set.of(NULLABLE_NAME))));
  }

  @Test
  public void nestedAnnotationsBecomeTypePaths() throws IOException {
    StubxCacheUtil cache =
        readStubs(
            "p/Box.java",
            """
            package p;
            import java.util.List;
            import java.util.Map;
            import org.jspecify.annotations.NonNull;
            import org.jspecify.annotations.Nullable;
            public class Box<T> {
              public class Inner<U, V> {}
              public static class Flat {
                public class In<W> {}
              }
              public List<@Nullable T> copy(
                  List<? super @Nullable T> sink,
                  List<? extends @NonNull T> source,
                  @Nullable String[] names,
                  Map.Entry<@Nullable String, T> entry,
                  Box<T>.Inner<String, @Nullable String> inner,
                  Flat.In<@Nullable String> flat) {
                return null;
              }
            }
            """);

    String copy =
        "p.Box:java.util.List<T> copy(java.util.List<? super T>,java.util.List<? extends T>,"
            + "java.lang.String[],java.util.Map.Entry<java.lang.String,T>,"
            + "p.Box<T>.Inner<java.lang.String,java.lang.String>,p.Box.Flat.In<java.lang.String>)";
    assertThat(cache.getNestedAnnotationInfoCache().keySet()).containsExactly(copy);
    assertThat(cache.getNestedAnnotationInfoCache().get(copy))
        .containsExactlyEntriesIn(
            ImmutableSetMultimap.<Integer, NestedAnnotationInfo>builder()
                .put(-1, nested(NULLABLE, new TypePathEntry(TYPE_ARGUMENT, 0)))
                .put(
                    0,
                    nested(
                        NULLABLE,
                        new TypePathEntry(TYPE_ARGUMENT, 0),
                        new TypePathEntry(WILDCARD_BOUND, 1)))
                .put(
                    1,
                    nested(
                        NONNULL,
                        new TypePathEntry(TYPE_ARGUMENT, 0),
                        new TypePathEntry(WILDCARD_BOUND, 0)))
                .put(2, nested(NULLABLE, new TypePathEntry(ARRAY_ELEMENT, -1)))
                .put(3, nested(NULLABLE, new TypePathEntry(TYPE_ARGUMENT, 0)))
                .put(4, nested(NULLABLE, new TypePathEntry(TYPE_ARGUMENT, 1)))
                .put(5, nested(NULLABLE, new TypePathEntry(TYPE_ARGUMENT, 0)))
                .build());
    assertThat(cache.getArgAnnotCache()).isEmpty();
  }

  @Test
  public void onlyAnnotationsOnTheInnermostClassOfAnInnerClassTypeAreRead() throws IOException {
    StubxCacheUtil cache =
        readStubs(
            "p/Outer.java",
            """
            package p;
            import org.jspecify.annotations.Nullable;
            public class Outer<T> {
              public class Inner<U> {
                public class Deep {}
              }
              public class Plain {}
              public static class Flat {
                public class In {}
                public static class Stat {
                  public class Leaf {}
                }
              }
              public static class Holder<
                  A extends @Nullable Outer<String>.Plain, B extends Outer<String>.@Nullable Plain> {
                public @Nullable Flat.In enclosingField;
                public Flat.@Nullable In innerField;
                public <M extends @Nullable Outer<String>.Plain, N extends Outer<String>.@Nullable Plain>
                    void bounds(M m, N n) {}
                public @Nullable Outer<String>.Plain enclosing(
                    Outer<@Nullable String>.Inner<Integer> argumentOfEnclosing,
                    Outer<String>.@Nullable Inner<String>.Deep middle,
                    @Nullable Flat.In enclosingOfOneSegment) {
                  return null;
                }
                public Outer<String>.@Nullable Plain inner(
                    Outer<String>.Inner<@Nullable Integer> argumentOfInner,
                    Outer<String>.Inner<String>.@Nullable Deep deep,
                    Flat.Stat.@Nullable Leaf belowStatic) {
                  return null;
                }
              }
            }
            """);

    String inner =
        "p.Outer.Holder:p.Outer<java.lang.String>.Plain inner("
            + "p.Outer<java.lang.String>.Inner<java.lang.Integer>,"
            + "p.Outer<java.lang.String>.Inner<java.lang.String>.Deep,p.Outer.Flat.Stat.Leaf)";
    assertThat(cache.getArgAnnotCache())
        .containsExactly(
            "p.Outer.Holder",
            Map.of(
                inner,
                Map.of(
                    -1,
                    Set.of(NULLABLE_NAME),
                    1,
                    Set.of(NULLABLE_NAME),
                    2,
                    Set.of(NULLABLE_NAME))));
    assertThat(cache.getNestedAnnotationInfoCache())
        .containsExactly(
            inner,
            ImmutableSetMultimap.of(0, nested(NULLABLE, new TypePathEntry(TYPE_ARGUMENT, 0))));
    assertThat(cache.getNullableFieldsCache()).containsExactly("p.Outer$Holder", "innerField");
    assertThat(cache.getUpperBoundCache()).containsExactly("p.Outer.Holder", 1);
    assertThat(cache.getMethodTypeParamNullableUpperBoundCache())
        .containsExactly("p.Outer.Holder:void <M,N>bounds(M,N)", 1);
  }

  @Test
  public void onlyTypeParametersBoundedByNullableHaveNullableUpperBounds() throws IOException {
    StubxCacheUtil cache =
        readStubs(
            "p/Pair.java",
            """
            package p;
            import org.jspecify.annotations.Nullable;
            public class Pair<A, B extends @Nullable Object> {
              public <X extends @Nullable Object, Y> @Nullable X pick(Y y) { return null; }
            }
            """);

    assertThat(cache.getUpperBoundCache()).containsExactly("p.Pair", 1);
    assertThat(cache.getMethodTypeParamNullableUpperBoundCache())
        .containsExactly("p.Pair:X <X,Y>pick(Y)", 0);
  }

  @Test
  public void onlyNullMarkedClassesAreNullMarked() throws IOException {
    StubxCacheUtil cache =
        readStubs(
            "p/Outer.java",
            """
            package p;
            import org.jspecify.annotations.NullMarked;
            public class Outer {
              @NullMarked
              public static class Marked {}
            }
            """);

    assertThat(cache.getNullMarkedClassesCache()).containsExactly("p.Outer.Marked");
  }

  @Test
  public void nullableFieldsAreKeyedByFlatClassName() throws IOException {
    StubxCacheUtil cache =
        readStubs(
            "p/Outer.java",
            """
            package p;
            import java.util.List;
            import org.jspecify.annotations.Nullable;
            public class Outer {
              public static class Nested {
                public @Nullable String nullable;
                public String plain;
                public List<@Nullable String> nullableElements;
              }
            }
            """);

    assertThat(cache.getNullableFieldsCache()).containsExactly("p.Outer$Nested", "nullable");
  }

  @Test
  public void entriesOtherThanClassFilesAreSkipped() throws IOException {
    byte[] jar =
        jar(
            Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes(UTF_8),
                "module-info.class", "not a class".getBytes(UTF_8)));
    StubxCacheUtil cache = newCache();

    JSpecifyJdkStubsReader.read(new ByteArrayInputStream(jar), "stubs.jar", cache);

    assertThat(cache.getArgAnnotCache()).isEmpty();
  }

  @Test
  public void aMalformedClassFileIsReportedWithItsEntryAndLocation() throws IOException {
    byte[] jar = jar(Map.of("p/Broken.class", "not a class".getBytes(UTF_8)));

    IOException error =
        assertThrows(
            IOException.class,
            () ->
                JSpecifyJdkStubsReader.read(
                    new ByteArrayInputStream(jar), "stubs.jar", newCache()));

    assertThat(error).hasMessageThat().contains("p/Broken.class");
    assertThat(error).hasMessageThat().contains("stubs.jar");
  }

  private static NestedAnnotationInfo nested(
      NestedAnnotationInfo.Annotation annotation, TypePathEntry... path) {
    return new NestedAnnotationInfo(annotation, ImmutableList.copyOf(path));
  }

  private static StubxCacheUtil newCache() {
    return new StubxCacheUtil("JSpecifyJdkStubsReaderTest", false);
  }

  /** Compiles one source file against JSpecify, packs its classes into a jar, and reads the jar. */
  private StubxCacheUtil readStubs(String path, String source) throws IOException {
    File classes = temporaryFolder.newFolder();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    JavaFileObject file =
        new SimpleJavaFileObject(URI.create("string:///" + path), JavaFileObject.Kind.SOURCE) {
          @Override
          public String getCharContent(boolean ignoreEncodingErrors) {
            return source;
          }
        };
    String jspecify = Path.of(URI.create(locationOf(Nullable.class))).toAbsolutePath().toString();
    Boolean compiled =
        compiler
            .getTask(
                null,
                null,
                null,
                List.of("-classpath", jspecify, "-d", classes.getPath()),
                null,
                List.of(file))
            .call();
    assertWithMessage("compilation of %s", path).that(compiled).isTrue();

    Map<String, byte[]> entries = new TreeMap<>();
    try (Stream<Path> files = Files.walk(classes.toPath())) {
      for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
        entries.put(
            classes.toPath().relativize(f).toString().replace(File.separatorChar, '/'),
            Files.readAllBytes(f));
      }
    }
    StubxCacheUtil cache = newCache();
    JSpecifyJdkStubsReader.read(new ByteArrayInputStream(jar(entries)), path, cache);
    return cache;
  }

  private static String locationOf(Class<?> type) {
    return type.getProtectionDomain().getCodeSource().getLocation().toString();
  }

  private static byte[] jar(Map<String, byte[]> entries) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }
}
