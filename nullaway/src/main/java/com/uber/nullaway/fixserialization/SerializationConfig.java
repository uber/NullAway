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
import com.sun.source.util.Trees;
import com.uber.nullaway.fixserialization.qual.AnnotationFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import javax.lang.model.element.Element;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

/** Config class for Fix Serialization package. */
public class SerializationConfig {

  /**
   * If activated, for all reported errors, NullAway will serialize information and suggests type
   * changes to resolve them, in case these errors could be fixed by adding a @Nullable annotation.
   * These data will be written at output directory.
   */
  public final boolean suggestEnabled;
  /**
   * If activated, serialized information of a fix suggest will also include the enclosing method
   * and class of the element involved in error. Finding enclosing elements is costly and will only
   * be computed at request.
   */
  public final boolean suggestEnclosing;
  /** The directory where all files generated/read by Fix Serialization package resides. */
  public final String outputDirectory;

  public final AnnotationFactory annotationFactory;

  public final Serializer serializer;

  public SerializationConfig() {
    suggestEnabled = false;
    suggestEnclosing = false;
    annotationFactory = new AnnotationFactory();
    outputDirectory = null;
    serializer = null;
  }

  public SerializationConfig(
      boolean suggestEnabled,
      boolean suggestEnclosing,
      AnnotationFactory annotationFactory,
      String outputDirectory) {
    this.suggestEnabled = suggestEnabled;
    this.suggestEnclosing = suggestEnclosing;
    this.outputDirectory = outputDirectory;
    this.annotationFactory = annotationFactory;
    serializer = new Serializer(this);
  }

  /**
   * Sets all flags based on their values in the configuration file.
   *
   * @param configFilePath Path to the serialization config file written in xml.
   */
  public SerializationConfig(String configFilePath) {
    Preconditions.checkNotNull(configFilePath);
    Document document;
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      DocumentBuilder builder = factory.newDocumentBuilder();
      document = builder.parse(Files.newInputStream(Paths.get(configFilePath)));
      document.normalize();
    } catch (Exception e) {
      throw new RuntimeException(
          "Error in reading/parsing config at path: " + configFilePath + "\n" + e);
    }
    this.outputDirectory =
        XMLUtil.getValueFromTag(document, "serialization:path", String.class).orElse(null);
    Preconditions.checkNotNull(
        this.outputDirectory, "Error in FixSerialization Config: Output path cannot be null");
    suggestEnabled =
        XMLUtil.getValueFromAttribute(document, "serialization:suggest", "active", Boolean.class)
            .orElse(false);
    suggestEnclosing =
        XMLUtil.getValueFromAttribute(document, "serialization:suggest", "enclosing", Boolean.class)
                .orElse(false)
            && suggestEnabled;
    String nullableAnnot =
        XMLUtil.getValueFromTag(document, "serialization:annotation:nullable", String.class)
            .orElse("javax.annotation.Nullable");
    String nonnullAnnot =
        XMLUtil.getValueFromTag(document, "serialization:annotation:nonnull", String.class)
            .orElse("javax.annotation.Nonnull");
    this.annotationFactory = new AnnotationFactory(nullableAnnot, nonnullAnnot);
    serializer = new Serializer(this);
  }

  /**
   * Validates whether {@code suggestEnabled} is active and the element involved in the error is
   * accessible and not received in bytecodes.
   *
   * @param trees Trees object.
   * @param symbol Symbol of the targeted element to perform type change.
   * @return true, if the element is accessible and {@code suggestEnabled} is active.
   */
  public boolean canFixElement(Trees trees, Element symbol) {
    if (!suggestEnabled) return false;
    if (trees == null || symbol == null) return false;
    return trees.getPath(symbol) != null;
  }

  /** Builder class for Serialization Config */
  public static class Builder {

    private boolean suggestEnabled;
    private boolean suggestEnclosing;
    private String nullable;
    private String nonnull;
    private String outputDir;

    public Builder() {
      suggestEnabled = false;
      suggestEnclosing = false;
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

    public Builder setOutputDirectory(String outputDir) {
      this.outputDir = outputDir;
      return this;
    }

    /**
     * Builds and writes the config with the state in builder at the given path as XML.
     *
     * @param path path to write the config file.
     */
    public void writeAsXMLat(String path) {
      SerializationConfig config = this.build();
      XMLUtil.writeInXMLFormat(config, path);
    }

    public SerializationConfig build() {
      return new SerializationConfig(
          suggestEnabled, suggestEnclosing, new AnnotationFactory(nullable, nonnull), outputDir);
    }
  }
}
