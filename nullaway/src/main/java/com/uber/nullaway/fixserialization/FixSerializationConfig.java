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
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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
   * it {@code @NonNull} at exit point.
   */
  public final boolean fieldInitInfoEnabled;

  /**
   * If enabled, the formal parameter at index {@link FixSerializationConfig#paramTestIndex} in all
   * methods will be treated as {@code @Nullable}
   */
  public final boolean methodParamProtectionTestEnabled;

  /**
   * If enabled, NullAway will assume all methods stored {@link
   * FixSerializationConfig#upstreamDependencyAPIInfoFile} file returns {@code @Nullable}. This
   * feature is used to compute effects of making methods in upstream dependencies {@code @Nullable}
   * on downstream dependencies (current module).
   */
  public final boolean apiAnalysisFromUpstreamDependencyEnabled;

  /**
   * Path to {@code .tsv} file where contains set of methods available through upstream
   * dependencies.
   */
  @Nullable public final String upstreamDependencyAPIInfoFile;

  /** Container class to store information for methods available via upstream dependencies. */
  private static class UpstreamMethod {
    /** Containing class's symbol in String. */
    final String clazz;
    /** Method's symbol in String. */
    final String method;

    public UpstreamMethod(String clazz, String method) {
      this.clazz = clazz;
      this.method = method;
    }
  }

  /** Set of all methods available via upstream dependencies. */
  private final Set<UpstreamMethod> upstreamMethods;

  /**
   * Index of the formal parameter of all methods which will be considered {@code @Nullable}, if
   * {@link FixSerializationConfig#methodParamProtectionTestEnabled} is enabled.
   */
  public final int paramTestIndex;

  /** The directory where all files generated/read by Fix Serialization package resides. */
  @Nullable public final String outputDirectory;

  public final AnnotationConfig annotationConfig;

  @Nullable private final Serializer serializer;

  /** Default Constructor, all features are disabled with this config. */
  public FixSerializationConfig() {
    suggestEnabled = false;
    suggestEnclosing = false;
    fieldInitInfoEnabled = false;
    methodParamProtectionTestEnabled = false;
    paramTestIndex = Integer.MAX_VALUE;
    annotationConfig = new AnnotationConfig();
    apiAnalysisFromUpstreamDependencyEnabled = false;
    upstreamDependencyAPIInfoFile = null;
    upstreamMethods = Collections.emptySet();
    outputDirectory = null;
    serializer = null;
  }

  public FixSerializationConfig(
      boolean suggestEnabled,
      boolean suggestEnclosing,
      boolean fieldInitInfoEnabled,
      boolean methodParamProtectionTestEnabled,
      int paramTestIndex,
      boolean apiAnalysisFromUpstreamDependencyEnabled,
      @Nullable String upstreamDependencyAPIInfoFile,
      AnnotationConfig annotationConfig,
      String outputDirectory) {
    this.suggestEnabled = suggestEnabled;
    this.suggestEnclosing = suggestEnclosing;
    this.fieldInitInfoEnabled = fieldInitInfoEnabled;
    this.methodParamProtectionTestEnabled = methodParamProtectionTestEnabled;
    this.paramTestIndex = paramTestIndex;
    this.apiAnalysisFromUpstreamDependencyEnabled = apiAnalysisFromUpstreamDependencyEnabled;
    this.upstreamDependencyAPIInfoFile = upstreamDependencyAPIInfoFile;
    this.upstreamMethods =
        readUpstreamMethods(
            apiAnalysisFromUpstreamDependencyEnabled, upstreamDependencyAPIInfoFile);
    this.outputDirectory = outputDirectory;
    this.annotationConfig = annotationConfig;
    this.serializer = new Serializer(this);
  }

  /**
   * Sets all flags based on their values in the configuration file.
   *
   * @param configFilePath Path to the serialization config file written in xml.
   */
  public FixSerializationConfig(String configFilePath) {
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
    this.suggestEnabled =
        XMLUtil.getValueFromAttribute(document, "/serialization/suggest", "active", Boolean.class)
            .orElse(false);
    this.suggestEnclosing =
        XMLUtil.getValueFromAttribute(
                document, "/serialization/suggest", "enclosing", Boolean.class)
            .orElse(false);
    if (suggestEnclosing && !suggestEnabled) {
      throw new IllegalStateException(
          "Error in the fix serialization configuration, suggest flag must be enabled to activate enclosing method and class serialization.");
    }
    this.fieldInitInfoEnabled =
        XMLUtil.getValueFromAttribute(
                document, "/serialization/fieldInitInfo", "active", Boolean.class)
            .orElse(false);
    methodParamProtectionTestEnabled =
        XMLUtil.getValueFromAttribute(document, "/serialization/paramTest", "active", Boolean.class)
            .orElse(false);
    paramTestIndex =
        XMLUtil.getValueFromAttribute(document, "/serialization/paramTest", "index", Integer.class)
            .orElse(Integer.MAX_VALUE);
    this.apiAnalysisFromUpstreamDependencyEnabled =
        XMLUtil.getValueFromAttribute(
                document,
                "/serialization/apiAnalysisFromUpstreamDependency",
                "active",
                Boolean.class)
            .orElse(false);
    this.upstreamDependencyAPIInfoFile =
        XMLUtil.getValueFromAttribute(
                document, "/serialization/apiAnalysisFromUpstreamDependency", "path", String.class)
            .orElse(null);
    this.upstreamMethods =
        readUpstreamMethods(
            apiAnalysisFromUpstreamDependencyEnabled, upstreamDependencyAPIInfoFile);
    String nullableAnnot =
        XMLUtil.getValueFromTag(document, "/serialization/annotation/nullable", String.class)
            .orElse("javax.annotation.Nullable");
    String nonnullAnnot =
        XMLUtil.getValueFromTag(document, "/serialization/annotation/nonnull", String.class)
            .orElse("javax.annotation.Nonnull");
    this.annotationConfig = new AnnotationConfig(nullableAnnot, nonnullAnnot);
    this.serializer = new Serializer(this);
  }

  /**
   * Reads all upstream methods stored as string in a file.
   *
   * @param apiAnalysisFromUpstreamDependencyEnabled if false, returns empty list, otherwise, it
   *     will read the file.
   * @param upstreamDependencyAPIInfoFile path to {@code .tsv} file containing methods information.
   *     Header of this file is: CLASS\tMETHOD.
   * @return Set of methods stored in {@link FixSerializationConfig#upstreamDependencyAPIInfoFile}.
   */
  private Set<UpstreamMethod> readUpstreamMethods(
      boolean apiAnalysisFromUpstreamDependencyEnabled, String upstreamDependencyAPIInfoFile) {
    if (!apiAnalysisFromUpstreamDependencyEnabled) {
      return Collections.emptySet();
    }
    if (upstreamDependencyAPIInfoFile == null) {
      throw new IllegalStateException(
          "path to file containing info regarding api of upstream dependency should be set to activate apiAnalysisFromUpstreamDependency");
    }
    Set<UpstreamMethod> methods = new HashSet<>();
    try (BufferedReader reader =
        new BufferedReader(
            new FileReader(upstreamDependencyAPIInfoFile, Charset.defaultCharset()))) {
      // to skip header
      reader.readLine();
      String line = reader.readLine();
      while (line != null) {
        String clazz = line.split("\\t")[0];
        String method = line.split("\\t")[1];
        methods.add(new UpstreamMethod(clazz, method));
        line = reader.readLine();
      }
    } catch (IOException e) {
      throw new RuntimeException(
          "Error happened in reading file: " + upstreamDependencyAPIInfoFile, e);
    }
    return methods;
  }

  @Nullable
  public Serializer getSerializer() {
    return serializer;
  }

  /**
   * Checks if method passed as arguments is coming from upstream dependencies.
   *
   * @param clazz containing class of method.
   * @param method method.
   * @return true, the method is available through upstream dependencies.
   */
  public boolean isUpstreamMethod(String clazz, String method) {
    return apiAnalysisFromUpstreamDependencyEnabled
        && upstreamMethods.stream()
            .anyMatch(
                upstreamMethod ->
                    upstreamMethod.clazz.equals(clazz) && upstreamMethod.method.equals(method));
  }

  /** Builder class for Serialization Config */
  public static class Builder {

    private boolean suggestEnabled;
    private boolean suggestEnclosing;
    private boolean fieldInitInfo;
    private boolean methodParamProtectionTestEnabled;
    private int paramIndex;
    private boolean apiAnalysisFromUpstreamDependencyEnabled;
    @Nullable private String upstreamDependencyAPIInfoFile;
    private String nullable;
    private String nonnull;
    @Nullable private String outputDir;

    public Builder() {
      suggestEnabled = false;
      suggestEnclosing = false;
      fieldInitInfo = false;
      apiAnalysisFromUpstreamDependencyEnabled = false;
      upstreamDependencyAPIInfoFile = null;
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

    public Builder setParamProtectionTest(boolean value, int index) {
      this.methodParamProtectionTestEnabled = value;
      this.paramIndex = index;
      return this;
    }

    public Builder setAPIAnalysisFromUpstreamDependency(boolean enabled, String path) {
      this.apiAnalysisFromUpstreamDependencyEnabled = enabled;
      this.upstreamDependencyAPIInfoFile = path;
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
      return new FixSerializationConfig(
          suggestEnabled,
          suggestEnclosing,
          fieldInitInfo,
          methodParamProtectionTestEnabled,
          paramIndex,
          apiAnalysisFromUpstreamDependencyEnabled,
          upstreamDependencyAPIInfoFile,
          new AnnotationConfig(nullable, nonnull),
          outputDir);
    }
  }
}
