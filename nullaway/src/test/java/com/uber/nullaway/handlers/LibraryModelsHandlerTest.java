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
import static com.uber.nullaway.LibraryModels.MethodRef.methodRef;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.ARRAY_ELEMENT;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.TYPE_ARGUMENT;
import static com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry.Kind.WILDCARD_BOUND;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.io.ByteStreams;
import com.uber.nullaway.Config;
import com.uber.nullaway.LibraryModels;
import com.uber.nullaway.LibraryModels.MethodRef;
import com.uber.nullaway.LibraryModels.PolyNullLocation;
import com.uber.nullaway.LibraryModels.PolyNullLocation.Parameter;
import com.uber.nullaway.LibraryModels.PolyNullLocation.Receiver;
import com.uber.nullaway.LibraryModels.PolyNullLocation.Return;
import com.uber.nullaway.libmodel.NestedAnnotationInfo.TypePathEntry;
import com.uber.nullaway.testlibrarymodels.TestLibraryModels;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Objects;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LibraryModelsHandlerTest {

  private static final String RESOURCE_NAME = "model.astubx";

  private static final MethodRef OPTIONAL_OR_ELSE_GET =
      methodRef("java.util.Optional", "orElseGet(java.util.function.Supplier<? extends T>)");

  private static final PolyNullLocation OPTIONAL_SUPPLIER_RESULT =
      new PolyNullLocation(
          new Parameter(0),
          ImmutableList.of(
              new TypePathEntry(TYPE_ARGUMENT, 0), new TypePathEntry(WILDCARD_BOUND, 0)));

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void polyNullLocationPositionsRepresentReceiverParameterAndReturn() {
    TypePathEntry typeArgument = new TypePathEntry(TYPE_ARGUMENT, 0);
    TypePathEntry arrayElement = new TypePathEntry(ARRAY_ELEMENT, 0);
    assertThat(new PolyNullLocation(new Receiver(), ImmutableList.of(typeArgument)).position())
        .isInstanceOf(Receiver.class);
    assertThat(new PolyNullLocation(new Parameter(0), ImmutableList.of()).position())
        .isEqualTo(new Parameter(0));
    assertThat(new PolyNullLocation(new Return(), ImmutableList.of(arrayElement)).typePath())
        .containsExactly(arrayElement);
    assertThrows(IllegalArgumentException.class, () -> new Parameter(-1));
  }

  @Test
  public void rejectsPolyNullReceiverLocationUntilInferenceSupportsIt() {
    MethodRef method = methodRef("example.Container", "get()");
    TestLibraryModels provider =
        new TestLibraryModels() {
          @Override
          public ImmutableSetMultimap<MethodRef, PolyNullLocation> polyNullLocations() {
            return ImmutableSetMultimap.of(
                method,
                new PolyNullLocation(
                    new Receiver(), ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, 0))));
          }
        };
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new LibraryModelsHandler.CombinedLibraryModels(
                    ImmutableList.of(provider), polyNullModelsConfig()));
    assertThat(error).hasMessageThat().contains("receiver locations are not yet supported");
    assertThat(error).hasMessageThat().contains("example.Container");
  }

  @Test
  public void rejectsTopLevelOnlyPolyNullModelsPerMethod() {
    MethodRef method = methodRef("example.Container", "identity(java.lang.Object)");
    MethodRef nestedMethod = methodRef("example.Container", "nested()");
    PolyNullLocation parameter = new PolyNullLocation(new Parameter(0), ImmutableList.of());
    PolyNullLocation result = new PolyNullLocation(new Return(), ImmutableList.of());
    for (ImmutableSet<PolyNullLocation> locations :
        ImmutableList.of(
            ImmutableSet.of(parameter),
            ImmutableSet.of(result),
            ImmutableSet.of(parameter, result))) {
      ImmutableSetMultimap<MethodRef, PolyNullLocation> models =
          ImmutableSetMultimap.<MethodRef, PolyNullLocation>builder()
              .putAll(method, locations)
              .put(
                  nestedMethod,
                  new PolyNullLocation(
                      new Return(), ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, 0))))
              .build();
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  new LibraryModelsHandler.CombinedLibraryModels(
                      ImmutableList.of(polyNullProvider(models)), polyNullModelsConfig()));
      assertThat(error).hasMessageThat().contains("must include at least one nested location");
      assertThat(error).hasMessageThat().contains("identity");
    }
  }

  @Test
  public void rejectsTopLevelOnlyPolyNullModelsEvenWhenSkipped() {
    MethodRef method = methodRef("example.Container", "identity(java.lang.Object)");
    Config config = polyNullModelsConfig();
    when(config.isSkippedLibraryModel("example.Container.identity")).thenReturn(true);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new LibraryModelsHandler.CombinedLibraryModels(
                    ImmutableList.of(
                        polyNullProvider(
                            ImmutableSetMultimap.of(
                                method, new PolyNullLocation(new Return(), ImmutableList.of())))),
                    config));
    assertThat(error).hasMessageThat().contains("must include at least one nested location");
    assertThat(error).hasMessageThat().contains("identity");
  }

  @Test
  public void acceptsIdenticalPolyNullLocationsFromTwoProvidersForSameMethod() {
    TestLibraryModels first = new TestLibraryModels();
    TestLibraryModels second =
        polyNullProvider(
            ImmutableSetMultimap.<MethodRef, PolyNullLocation>builder()
                .putAll(ImmutableList.copyOf(first.polyNullLocations().entries()).reverse())
                .build());
    LibraryModelsHandler.CombinedLibraryModels combined =
        new LibraryModelsHandler.CombinedLibraryModels(
            ImmutableList.of(first, second), polyNullModelsConfig());
    assertThat(combined.polyNullLocations()).containsExactlyEntriesIn(first.polyNullLocations());
  }

  @Test
  public void rejectsConflictingPolyNullLocationsFromTwoProvidersForSameMethod() {
    MethodRef method =
        methodRef(
            "com.uber.lib.unannotated.PolyNullMethods",
            "first(java.util.List<java.lang.Object>,java.util.List<java.lang.Object>)");
    TestLibraryModels first = new TestLibraryModels();
    TestLibraryModels second =
        polyNullProvider(
            ImmutableSetMultimap.of(
                method,
                new PolyNullLocation(
                    new Parameter(0), ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, 0)))));
    for (ImmutableList<LibraryModels> providers :
        ImmutableList.of(
            ImmutableList.<LibraryModels>of(first, second),
            ImmutableList.<LibraryModels>of(second, first))) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  new LibraryModelsHandler.CombinedLibraryModels(providers, polyNullModelsConfig()),
              "providers: " + providers);
      assertWithMessage("providers: %s", providers)
          .that(error)
          .hasMessageThat()
          .contains("Conflicting PolyNull library model providers");
      assertWithMessage("providers: %s", providers)
          .that(error)
          .hasMessageThat()
          .contains("PolyNullMethods");
      assertWithMessage("providers: %s", providers)
          .that(error)
          .hasMessageThat()
          .contains(first.getClass().getName());
      assertWithMessage("providers: %s", providers)
          .that(error)
          .hasMessageThat()
          .contains(second.getClass().getName());
    }
  }

  @Test
  public void onlyCustomPolyNullModelsAreLoadedWithoutJSpecifyJdkModels() {
    Config config = polyNullModelsConfig();
    when(config.isJSpecifyJDKModels()).thenReturn(false);
    LibraryModelsHandler.DefaultLibraryModels defaults =
        new LibraryModelsHandler.DefaultLibraryModels(config);
    TestLibraryModels user = new TestLibraryModels();
    LibraryModelsHandler.CombinedLibraryModels combined =
        new LibraryModelsHandler.CombinedLibraryModels(ImmutableList.of(defaults, user), config);

    assertThat(combined.polyNullLocations()).containsExactlyEntriesIn(user.polyNullLocations());
  }

  @Test
  public void userPolyNullModelsReplaceWholeBuiltInModelRegardlessOfProviderOrder() {
    Config config = polyNullModelsConfig();
    LibraryModelsHandler.DefaultLibraryModels defaults =
        new LibraryModelsHandler.DefaultLibraryModels(config);
    // Omit the built-in return location to check replacement rather than union.
    ImmutableSetMultimap<MethodRef, PolyNullLocation> userLocations =
        ImmutableSetMultimap.of(OPTIONAL_OR_ELSE_GET, OPTIONAL_SUPPLIER_RESULT);
    TestLibraryModels user = polyNullProvider(userLocations);
    MethodRef computeIfAbsent =
        methodRef(
            "java.util.Map",
            "computeIfAbsent(K,java.util.function.Function<? super K,? extends V>)");
    ImmutableSetMultimap<MethodRef, PolyNullLocation> expected =
        ImmutableSetMultimap.of(
            OPTIONAL_OR_ELSE_GET,
            OPTIONAL_SUPPLIER_RESULT,
            computeIfAbsent,
            new PolyNullLocation(
                new Parameter(1),
                ImmutableList.of(
                    new TypePathEntry(TYPE_ARGUMENT, 1), new TypePathEntry(WILDCARD_BOUND, 0))),
            computeIfAbsent,
            new PolyNullLocation(new Return(), ImmutableList.of()));
    for (ImmutableList<LibraryModels> providers :
        ImmutableList.of(ImmutableList.of(defaults, user), ImmutableList.of(user, defaults))) {
      LibraryModelsHandler.CombinedLibraryModels combined =
          new LibraryModelsHandler.CombinedLibraryModels(providers, config);
      assertWithMessage("providers: %s", providers)
          .that(combined.polyNullLocations())
          .containsExactlyEntriesIn(expected);
    }
  }

  @Test
  public void skipsConflictingPolyNullModelsFromBuiltInAndUserProviders() {
    Config config = polyNullModelsConfig();
    when(config.isSkippedLibraryModel("java.util.Optional.orElseGet")).thenReturn(true);
    LibraryModelsHandler.DefaultLibraryModels defaults =
        new LibraryModelsHandler.DefaultLibraryModels(config);
    TestLibraryModels first =
        polyNullProvider(ImmutableSetMultimap.of(OPTIONAL_OR_ELSE_GET, OPTIONAL_SUPPLIER_RESULT));
    TestLibraryModels second =
        polyNullProvider(
            ImmutableSetMultimap.<MethodRef, PolyNullLocation>builder()
                .putAll(
                    OPTIONAL_OR_ELSE_GET, defaults.polyNullLocations().get(OPTIONAL_OR_ELSE_GET))
                .build());
    LibraryModelsHandler.CombinedLibraryModels combined =
        new LibraryModelsHandler.CombinedLibraryModels(
            ImmutableList.of(defaults, first, second), config);
    assertThat(combined.polyNullLocations().get(OPTIONAL_OR_ELSE_GET)).isEmpty();
  }

  @Test
  public void rejectsConflictingUserPolyNullModelsEvenWhenOneMatchesBuiltInModel() {
    Config config = polyNullModelsConfig();
    LibraryModelsHandler.DefaultLibraryModels defaults =
        new LibraryModelsHandler.DefaultLibraryModels(config);
    TestLibraryModels first =
        polyNullProvider(ImmutableSetMultimap.of(OPTIONAL_OR_ELSE_GET, OPTIONAL_SUPPLIER_RESULT));
    TestLibraryModels second =
        polyNullProvider(
            ImmutableSetMultimap.<MethodRef, PolyNullLocation>builder()
                .putAll(
                    OPTIONAL_OR_ELSE_GET, defaults.polyNullLocations().get(OPTIONAL_OR_ELSE_GET))
                .build());
    for (ImmutableList<LibraryModels> providers :
        ImmutableList.of(
            ImmutableList.of(defaults, first, second),
            ImmutableList.of(defaults, second, first),
            ImmutableList.of(first, defaults, second),
            ImmutableList.of(second, defaults, first),
            ImmutableList.of(first, second, defaults),
            ImmutableList.of(second, first, defaults))) {
      IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class,
              () -> new LibraryModelsHandler.CombinedLibraryModels(providers, config),
              "providers: " + providers);
      assertWithMessage("providers: %s", providers)
          .that(error)
          .hasMessageThat()
          .contains("Conflicting PolyNull library model providers");
      assertWithMessage("providers: %s", providers)
          .that(error)
          .hasMessageThat()
          .contains("orElseGet");
    }
  }

  @Test
  public void rejectsPolyNullLocationsFromTwoHandlersForSameMethod() {
    Handler first = mock(Handler.class);
    Handler second = mock(Handler.class);
    PolyNullLocation location =
        new PolyNullLocation(new Return(), ImmutableList.of(new TypePathEntry(TYPE_ARGUMENT, 0)));
    when(first.onGetPolyNullLocations(null, null)).thenReturn(ImmutableSet.of(location));
    when(second.onGetPolyNullLocations(null, null)).thenReturn(ImmutableSet.of(location));
    CompositeHandler composite = new CompositeHandler(ImmutableList.of(first, second));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> composite.onGetPolyNullLocations(null, null));
    assertThat(error).hasMessageThat().contains("Multiple handlers provide PolyNull locations");
  }

  @Test
  public void rejectsTopLevelOnlyPolyNullLocationsFromHandler() {
    Handler handler = mock(Handler.class);
    when(handler.onGetPolyNullLocations(null, null))
        .thenReturn(
            ImmutableSet.of(
                new PolyNullLocation(new Parameter(0), ImmutableList.of()),
                new PolyNullLocation(new Return(), ImmutableList.of())));
    CompositeHandler composite = new CompositeHandler(ImmutableList.of(handler));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> composite.onGetPolyNullLocations(null, null));
    assertThat(error).hasMessageThat().contains("must include at least one nested location");
  }

  @Test
  public void rejectsPolyNullReceiverLocationFromHandler() {
    Handler handler = mock(Handler.class);
    PolyNullLocation location = new PolyNullLocation(new Receiver(), ImmutableList.of());
    when(handler.onGetPolyNullLocations(null, null)).thenReturn(ImmutableSet.of(location));
    CompositeHandler composite = new CompositeHandler(ImmutableList.of(handler));
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> composite.onGetPolyNullLocations(null, null));
    assertThat(error).hasMessageThat().contains("receiver locations are not yet supported");
  }

  /** Creates a provider with the given PolyNull models. */
  private static TestLibraryModels polyNullProvider(
      ImmutableSetMultimap<MethodRef, PolyNullLocation> models) {
    return new TestLibraryModels() {
      @Override
      public ImmutableSetMultimap<MethodRef, PolyNullLocation> polyNullLocations() {
        return models;
      }
    };
  }

  /** Creates a configuration that enables built-in and custom PolyNull library models. */
  private static Config polyNullModelsConfig() {
    Config config = mock(Config.class);
    when(config.isPolyNullLibraryModelsEnabled()).thenReturn(true);
    when(config.isJSpecifyJDKModels()).thenReturn(true);
    return config;
  }

  /**
   * Verifies that closing one classloader cannot invalidate a resource stream opened by a second
   * classloader that uses {@link LibraryModelsHandler#openResourceWithoutCaching(URL)}. This models
   * overlapping compilations whose classloaders read a model from the same JAR; see <a
   * href="https://github.com/uber/NullAway/issues/1829">https://github.com/uber/NullAway/issues/1829</a>.
   */
  @Test
  public void uncachedResourceStreamSurvivesClosingAnotherClassLoader() throws Exception {
    byte[] expected = new byte[1_000_000];
    Arrays.fill(expected, (byte) 42);
    File jarFile = createJarContainingResource(expected);
    URL[] urls = {jarFile.toURI().toURL()};

    try (URLClassLoader loaderA = URLClassLoader.newInstance(urls, null);
        URLClassLoader loaderB = URLClassLoader.newInstance(urls, null);
        // Compilation B starts reading its model through an independently owned JAR handle.
        InputStream streamB =
            Objects.requireNonNull(
                LibraryModelsHandler.openResourceWithoutCaching(
                    loaderB.getResource(RESOURCE_NAME)))) {
      // Compilation A opens the same resource through URLClassLoader's shared JAR cache.
      try (InputStream streamA =
          Objects.requireNonNull(loaderA.getResourceAsStream(RESOURCE_NAME))) {
        assertThat(streamA.read()).isNotEqualTo(-1);
      }
      // Finishing compilation A closes its classloader while compilation B is still reading.
      loaderA.close();
      // Check that the read from compilation B completes successfully
      assertThat(ByteStreams.toByteArray(streamB)).isEqualTo(expected);
    }
  }

  /** Creates a temporary JAR containing {@link #RESOURCE_NAME} with the supplied contents. */
  private File createJarContainingResource(byte[] contents) throws IOException {
    File jarFile = temporaryFolder.newFile("models.jar");
    try (JarOutputStream output = new JarOutputStream(new FileOutputStream(jarFile))) {
      output.putNextEntry(new ZipEntry(RESOURCE_NAME));
      output.write(contents);
      output.closeEntry();
    }
    return jarFile;
  }
}
