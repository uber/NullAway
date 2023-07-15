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
import com.uber.nullaway.fixserialization.adapters.SerializationV1Adapter;
import com.uber.nullaway.fixserialization.adapters.SerializationV3Adapter;
import com.uber.nullaway.fixserialization.out.SuggestedNullableFixInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.annotation.Nullable;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/** Config class for Fix Serialization package. */
public class FixSerializationConfig {

  /**
   * If enabled, the corresponding output file will be cleared and for all reported errors, NullAway
   * will serialize information and suggest type changes to resolve them, in case these errors could
   * be fixed by adding a {@code @Nullable} annotation. These type change suggestions are in form of
   * {@link SuggestedNullableFixInfo} instances and will be serialized at output directory. If
   * deactivated, no {@code SuggestedFixInfo} will be created and the output file will remain
   * untouched.
   */
  public final boolean suggestEnabled;

  /**
   * If enabled, serialized information of a fix suggest will also include the enclosing method and
   * class of the element involved in error. Finding enclosing elements is costly and will only be
   * computed at request.
   */
  public final boolean suggestEnclosing;

  /**
   * If enabled, NullAway will serialize information about methods that initialize a field and leave
   * it {@code @NonNull} at exit point.
   */
  public final boolean fieldInitInfoEnabled;

  /** The directory where all files generated/read by Fix Serialization package resides. */
  @Nullable public final String outputDirectory;

  @Nullable private final Serializer serializer;

  /** Default Constructor, all features are disabled with this config. */
  public FixSerializationConfig() {
    suggestEnabled = false;
    suggestEnclosing = false;
    fieldInitInfoEnabled = false;
    outputDirectory = null;
    serializer = null;
  }

  public FixSerializationConfig(
      boolean suggestEnabled,
      boolean suggestEnclosing,
      boolean fieldInitInfoEnabled,
      @Nullable String outputDirectory) {
    this.suggestEnabled = suggestEnabled;
    this.suggestEnclosing = suggestEnclosing;
    this.fieldInitInfoEnabled = fieldInitInfoEnabled;
    this.outputDirectory = outputDirectory;
    serializer = new Serializer(this, initializeAdapter(SerializationAdapter.LATEST_VERSION));
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
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
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
    suggestEnabled =
        XMLUtil.getValueFromAttribute(document, "/serialization/suggest", "active", Boolean.class)
            .orElse(false);
    suggestEnclosing =
        XMLUtil.getValueFromAttribute(
                document, "/serialization/suggest", "enclosing", Boolean.class)
            .orElse(false);
    if (suggestEnclosing && !suggestEnabled) {
      throw new IllegalStateException(
          "Error in the fix serialization configuration, suggest flag must be enabled to activate enclosing method and class serialization.");
    }
    fieldInitInfoEnabled =
        XMLUtil.getValueFromAttribute(
                document, "/serialization/fieldInitInfo", "active", Boolean.class)
            .orElse(false);
    SerializationAdapter serializationAdapter = initializeAdapter(serializationVersion);
    serializer = new Serializer(this, serializationAdapter);
  }

  /**
   * Initializes NullAway serialization adapter according to the requested serialization version.
   */
  private SerializationAdapter initializeAdapter(int version) {
    switch (version) {
      case 1:
        return new SerializationV1Adapter();
      case 2:
        throw new RuntimeException(
            "Serialization version v2 is skipped and was used for an alpha version of the auto-annotator tool. Please use version 3 instead.");
      case 3:
        return new SerializationV3Adapter();
      default:
        throw new RuntimeException(
            "Unrecognized NullAway serialization version: "
                + version
                + ". Supported versions: 1 to "
                + SerializationAdapter.LATEST_VERSION
                + ".");
    }
  }

  @Nullable
  public Serializer getSerializer() {
    return serializer;
  }

  /** Builder class for Serialization Config */
  public static class Builder {

    private boolean suggestEnabled;
    private boolean suggestEnclosing;
    private boolean fieldInitInfo;
    @Nullable private String outputDir;

    public Builder() {
      suggestEnabled = false;
      suggestEnclosing = false;
      fieldInitInfo = false;
    }

    public Builder setSuggest(boolean value, boolean withEnclosing) {
      this.suggestEnabled = value;
      this.suggestEnclosing = withEnclosing && suggestEnabled;
      return this;
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
      return new FixSerializationConfig(suggestEnabled, suggestEnclosing, fieldInitInfo, outputDir);
    }
  }
}
