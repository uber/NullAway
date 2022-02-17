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
import com.uber.nullaway.fixserialization.qual.AnnotationConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
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
   * {@link com.uber.nullaway.fixserialization.out.SuggestedFixInfo} instances and will be
   * serialized at output directory. If deactivated, no {@code SuggestedFixInfo} will be created and
   * the output file will remain untouched.
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
   * it {@code @Nonnull} at exit point.
   */
  public final boolean fieldInitInfoEnabled;

  /** The directory where all files generated/read by Fix Serialization package resides. */
  public final String outputDirectory;

  public final AnnotationConfig annotationConfig;

  private final Serializer serializer;

  /** Default Constructor, all features are disabled with this config. */
  public FixSerializationConfig() {
    suggestEnabled = false;
    suggestEnclosing = false;
    fieldInitInfoEnabled = false;
    annotationConfig = new AnnotationConfig();
    outputDirectory = null;
    serializer = null;
  }

  public FixSerializationConfig(
      boolean suggestEnabled,
      boolean suggestEnclosing,
      boolean fieldInitInfoEnabled,
      AnnotationConfig annotationConfig,
      String outputDirectory) {
    this.suggestEnabled = suggestEnabled;
    this.suggestEnclosing = suggestEnclosing;
    this.fieldInitInfoEnabled = fieldInitInfoEnabled;
    this.outputDirectory = outputDirectory;
    this.annotationConfig = annotationConfig;
    serializer = new Serializer(this);
  }

  /**
   * Sets all flags based on their values in the configuration file.
   *
   * @param configFilePath Path to the serialization config file written in xml.
   */
  public FixSerializationConfig(String configFilePath) {
    Preconditions.checkNotNull(configFilePath);
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
    String nullableAnnot =
        XMLUtil.getValueFromTag(document, "/serialization/annotation/nullable", String.class)
            .orElse("javax.annotation.Nullable");
    String nonnullAnnot =
        XMLUtil.getValueFromTag(document, "/serialization/annotation/nonnull", String.class)
            .orElse("javax.annotation.Nonnull");
    this.annotationConfig = new AnnotationConfig(nullableAnnot, nonnullAnnot);
    serializer = new Serializer(this);
  }

  public Serializer getSerializer() {
    return serializer;
  }

  /** Builder class for Serialization Config */
  public static class Builder {

    private boolean suggestEnabled;
    private boolean suggestEnclosing;
    private boolean fieldInitInfo;
    private String nullable;
    private String nonnull;
    private String outputDir;

    public Builder() {
      suggestEnabled = false;
      suggestEnclosing = false;
      fieldInitInfo = false;
      nullable = "javax.annotation.Nullable";
      nonnull = "javax.annotation.Nonnull";
    }

    public Builder setSuggest(boolean value, boolean withEnclosing) {
      this.suggestEnabled = value;
      this.suggestEnclosing = withEnclosing && suggestEnabled;
      return this;
    }

    public Builder setAnnotations(String nullable, String nonnull) {
      this.nullable = nullable;
      this.nonnull = nonnull;
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
      return new FixSerializationConfig(
          suggestEnabled,
          suggestEnclosing,
          fieldInitInfo,
          new AnnotationConfig(nullable, nonnull),
          outputDir);
    }
  }
}
