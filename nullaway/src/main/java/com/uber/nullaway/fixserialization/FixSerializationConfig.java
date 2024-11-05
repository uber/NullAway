/*
 * Copyright (c) 2022 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.fixserialization;

import com.google.common.base.Preconditions;
import com.uber.nullaway.fixserialization.adapters.SerializationAdapter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/** Config class for Fix Serialization package. */
public class FixSerializationConfig {

  /**
   * If enabled, NullAway will serialize information about methods that initialize a field and leave
   * it {@code @NonNull} at exit point.
   */
  public final boolean fieldInitInfoEnabled;

  /** The directory where all files generated/read by Fix Serialization package resides. */
  public final @Nullable String outputDirectory;

  private final @Nullable Serializer serializer;

  /** Default Constructor, all features are disabled with this config. */
  public FixSerializationConfig() {
    fieldInitInfoEnabled = false;
    outputDirectory = null;
    serializer = null;
  }

  public FixSerializationConfig(boolean fieldInitInfoEnabled, @Nullable String outputDirectory) {
    this.fieldInitInfoEnabled = fieldInitInfoEnabled;
    this.outputDirectory = outputDirectory;
    serializer =
        new Serializer(
            this, SerializationAdapter.getAdapterForVersion(SerializationAdapter.LATEST_VERSION));
  }

  /**
   * Sets all flags based on their values in the configuration file.
   *
   * @param configFilePath Path to the serialization config file written in xml.
   * @param serializationVersion Requested serialization version, this value is configurable via
   *     ErrorProne flags ("SerializeFixMetadataVersion"). If not defined by the user {@link
   *     SerializationAdapter#LATEST_VERSION} will be used.
   */
  public FixSerializationConfig(String configFilePath, int serializationVersion) {
    Document document;
    try {
      DocumentBuilderFactory factory = XMLUtil.safeDocumentBuilderFactory();
      DocumentBuilder builder = factory.newDocumentBuilder();
      document = builder.parse(Files.newInputStream(Paths.get(configFilePath)));
      document.normalize();
    } catch (IOException | SAXException | ParserConfigurationException e) {
      throw new RuntimeException("Error in reading/parsing config at path: " + configFilePath, e);
    }
    this.outputDirectory =
        XMLUtil.getValueFromTag(document, "/serialization/path", String.class).orElse(null);
    Preconditions.checkNotNull(
        this.outputDirectory, "Error in FixSerialization Config: Output path cannot be null");
    fieldInitInfoEnabled =
        XMLUtil.getValueFromAttribute(
                document, "/serialization/fieldInitInfo", "active", Boolean.class)
            .orElse(false);
    SerializationAdapter serializationAdapter =
        SerializationAdapter.getAdapterForVersion(serializationVersion);
    serializer = new Serializer(this, serializationAdapter);
  }

  public @Nullable Serializer getSerializer() {
    return serializer;
  }

  /** Builder class for Serialization Config */
  public static class Builder {

    private boolean fieldInitInfo;
    private @Nullable String outputDir;

    public Builder() {
      fieldInitInfo = false;
    }

    public Builder setFieldInitInfo(boolean enabled) {
      this.fieldInitInfo = enabled;
      return this;
    }

    public Builder setOutputDirectory(String outputDir) {
      this.outputDir = outputDir;
      return this;
    }

    /**
     * Builds and writes the config with the state in builder at the given path as XML.
     *
     * @param path path to write the config file.
     */
    public void writeAsXML(String path) {
      FixSerializationConfig config = this.build();
      XMLUtil.writeInXMLFormat(config, path);
    }

    public FixSerializationConfig build() {
      if (outputDir == null) {
        throw new IllegalStateException("did not set mandatory output directory");
      }
      return new FixSerializationConfig(fieldInitInfo, outputDir);
    }
  }
}
