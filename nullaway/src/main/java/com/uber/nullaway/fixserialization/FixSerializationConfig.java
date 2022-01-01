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
public class FixSerializationConfig {

  /**
   * If activated, for all reporting errors, NullAway will serialize information and suggests type
   * changes to resolve them, in case these errors could be fixed by adding a @Nullable annotation.
   * These data will be written at output directory.
   */
  public final boolean suggestEnabled;
  /**
   * If activated, serialized information of a fix suggest will also include the enclosing method
   * and class of the element involved in error.
   */
  public final boolean suggestEnclosing;
  /** If activated, NullAway will write reporting errors in output directory. */
  public final boolean logErrorEnabled;
  /** If activated, errors information will also include the enclosing method and class. */
  public final boolean logErrorEnclosing;
  /** The directory where all files generated/read by Fix Serialization package resides. */
  public final String outputDirectory;

  public final AnnotationFactory annotationFactory;

  public final Writer writer;

  public FixSerializationConfig() {
    suggestEnabled = false;
    suggestEnclosing = false;
    logErrorEnabled = false;
    logErrorEnclosing = false;
    annotationFactory = new AnnotationFactory();
    outputDirectory = null;
    writer = null;
  }

  public FixSerializationConfig(
      boolean suggestEnabled,
      boolean suggestEnclosing,
      boolean logErrorEnabled,
      boolean logErrorEnclosing,
      AnnotationFactory annotationFactory,
      String outputDirectory) {
    this.suggestEnabled = suggestEnabled;
    this.suggestEnclosing = suggestEnclosing;
    this.logErrorEnabled = logErrorEnabled;
    this.logErrorEnclosing = logErrorEnclosing;
    this.outputDirectory = outputDirectory;
    this.annotationFactory = annotationFactory;
    writer = new Writer(this);
  }

  /**
   * * Sets all flags based on their values in {@code outputdierectory/explorer.config} json file.
   *
   * @param autofixEnabled Flag value in main NullAway config. If false, all flags here will be set
   *     to false.
   * @param outputDirectory Directory where all files generated/read by Fix Serialization package
   *     resides.
   */
  public FixSerializationConfig(boolean autofixEnabled, String outputDirectory) {
    // if autofixEnabled is false, all flags will be false regardless of their given value in json
    // config file.
    Preconditions.checkNotNull(outputDirectory);

    Document document = null;
    if (autofixEnabled) {
      try {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        document =
            builder.parse(
                Files.newInputStream(Paths.get(outputDirectory).resolve("explorer.config")));
        document.normalize();
      } catch (Exception e) {
        throw new RuntimeException(
            "Error in reading/parsing config at path: " + outputDirectory + "\n" + e);
      }
    }
    suggestEnabled =
        XMLUtil.getValueFromAttribute(document, "serialization:suggest", "active", Boolean.class)
                .orElse(false)
            && autofixEnabled;
    suggestEnclosing =
        XMLUtil.getValueFromAttribute(document, "serialization:suggest", "enclosing", Boolean.class)
                .orElse(false)
            && suggestEnabled;
    logErrorEnabled =
        XMLUtil.getValueFromAttribute(document, "serialization:error", "active", Boolean.class)
                .orElse(false)
            && autofixEnabled;
    logErrorEnclosing =
        XMLUtil.getValueFromAttribute(document, "serialization:error", "enclosing", Boolean.class)
                .orElse(false)
            && logErrorEnabled;
    String nullableAnnot =
        XMLUtil.getValueFromTag(document, "serialization:annotation:nullable", String.class)
            .orElse("javax.annotation.Nullable");
    String nonnullAnnot =
        XMLUtil.getValueFromTag(document, "serialization:annotation:nonnull", String.class)
            .orElse("javax.annotation.Nonnull");
    this.annotationFactory = new AnnotationFactory(nullableAnnot, nonnullAnnot);
    this.outputDirectory = outputDirectory;
    writer = new Writer(this);
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

  /** Builder class for Config */
  public static class FixSerializationConfigBuilder {

    private boolean suggestEnabled;
    private boolean suggestEnclosing;
    private boolean logErrorEnabled;
    private boolean logErrorEnclosing;
    private String nullable;
    private String nonnull;
    private String outputDir;

    public FixSerializationConfigBuilder() {
      suggestEnabled = false;
      suggestEnclosing = false;
      logErrorEnabled = false;
      logErrorEnclosing = false;
      nullable = "javax.annotation.Nullable";
      nonnull = "javax.annotation.Nonnull";
    }

    public FixSerializationConfigBuilder setSuggest(boolean value, boolean withEnclosing) {
      this.suggestEnabled = value;
      this.suggestEnclosing = withEnclosing && suggestEnabled;
      return this;
    }

    public FixSerializationConfigBuilder setAnnotations(String nullable, String nonnull) {
      this.nullable = nullable;
      this.nonnull = nonnull;
      return this;
    }

    public FixSerializationConfigBuilder setLogError(boolean value, boolean withEnclosing) {
      this.logErrorEnabled = value;
      if (!value && withEnclosing) {
        throw new IllegalArgumentException(
            "Log error must be enabled to activate enclosing log error");
      }
      this.logErrorEnclosing = withEnclosing;
      return this;
    }

    public FixSerializationConfigBuilder setOutputDirectory(String outputDir) {
      this.outputDir = outputDir;
      return this;
    }

    public void write(String path) {
      FixSerializationConfig config = this.build();
      XMLUtil.writeInXMLFormat(config, path);
    }

    public FixSerializationConfig build() {
      return new FixSerializationConfig(
          suggestEnabled,
          suggestEnclosing,
          logErrorEnabled,
          logErrorEnclosing,
          new AnnotationFactory(nullable, nonnull),
          outputDir);
    }
  }
}
