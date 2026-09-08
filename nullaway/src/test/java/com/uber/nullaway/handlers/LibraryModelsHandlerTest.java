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

import com.google.common.io.ByteStreams;
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

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  /**
   * Verifies that closing one classloader cannot invalidate a resource stream opened by a second
   * classloader that uses {@link LibraryModelsHandler#openResourceWithoutCaching(ClassLoader,
   * String)}. This models overlapping compilations whose classloaders read a model from the same
   * JAR; see <a
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
                LibraryModelsHandler.openResourceWithoutCaching(loaderB, RESOURCE_NAME))) {
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
