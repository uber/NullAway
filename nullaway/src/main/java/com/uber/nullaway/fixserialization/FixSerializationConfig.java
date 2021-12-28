/*
 * Copyright (c) 2019 Uber Technologies, Inc.
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
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.Element;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

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
  /**
   * Contains a set of classes fully qualified name. If not empty, NullAway will only report errors
   * in these classes.
   */
  public final Set<String> workList;

  public final Writer writer;

  public FixSerializationConfig() {
    suggestEnabled = false;
    suggestEnclosing = false;
    logErrorEnabled = false;
    logErrorEnclosing = false;
    annotationFactory = new AnnotationFactory();
    // No class is excluded.
    workList = Collections.singleton("*");
    outputDirectory = null;
    writer = null;
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
    JSONObject jsonObject = null;
    if (autofixEnabled) {
      try {
        Object obj =
            new JSONParser()
                .parse(
                    Files.newBufferedReader(
                        Paths.get(outputDirectory).resolve("explorer.config"),
                        Charset.defaultCharset()));
        jsonObject = (JSONObject) obj;
      } catch (Exception e) {
        throw new RuntimeException(
            "Error in reading/parsing config at path: " + outputDirectory + "\n" + e);
      }
    }
    suggestEnabled =
        getValueFromKey(jsonObject, "SUGGEST:ACTIVE", Boolean.class).orElse(false)
            && autofixEnabled;
    suggestEnclosing =
        getValueFromKey(jsonObject, "SUGGEST:ENCLOSING", Boolean.class).orElse(false)
            && suggestEnabled;
    logErrorEnabled =
        getValueFromKey(jsonObject, "LOG_ERROR:ACTIVE", Boolean.class).orElse(false)
            && autofixEnabled;
    logErrorEnclosing =
        getValueFromKey(jsonObject, "LOG_ERROR:ENCLOSING", Boolean.class).orElse(false)
            && logErrorEnabled;
    String nullableAnnot =
        getValueFromKey(jsonObject, "ANNOTATION:NULLABLE", String.class)
            .orElse("javax.annotation.Nullable");
    String nonnullAnnot =
        getValueFromKey(jsonObject, "ANNOTATION:NONNULL", String.class)
            .orElse("javax.annotation.Nonnull");
    this.annotationFactory = new AnnotationFactory(nullableAnnot, nonnullAnnot);
    String WORK_LIST_VALUE = getValueFromKey(jsonObject, "WORK_LIST", String.class).orElse("*");
    if (!WORK_LIST_VALUE.equals("*")) {
      this.workList = new HashSet<>(Arrays.asList(WORK_LIST_VALUE.split(",")));
    } else {
      this.workList = Collections.singleton("*");
    }
    this.outputDirectory = outputDirectory;
    writer = new Writer(this);
  }

  /**
   * Helper method for reading values from json.
   *
   * @param json Json object to read values from.
   * @param key Key to locate the value, can be nested (e.g. key1:key2).
   * @param klass Class type of the value in json.
   * @return The value in the specified key cast to the class type given in parameter.
   */
  private <T> OrElse<T> getValueFromKey(JSONObject json, String key, Class<T> klass) {
    if (json == null) {
      return new OrElse<>(null, klass);
    }
    try {
      ArrayList<String> keys = new ArrayList<>(Arrays.asList(key.split(":")));
      while (keys.size() != 1) {
        if (json.containsKey(keys.get(0))) {
          json = (JSONObject) json.get(keys.get(0));
          keys.remove(0);
        } else {
          return new OrElse<>(null, klass);
        }
      }
      return json.containsKey(keys.get(0))
          ? new OrElse<>(json.get(keys.get(0)), klass)
          : new OrElse<>(null, klass);
    } catch (Exception e) {
      return new OrElse<>(null, klass);
    }
  }

  /** Helper class for setting default values when the key is not found. */
  static class OrElse<T> {
    final Object value;
    final Class<T> klass;

    OrElse(Object value, Class<T> klass) {
      this.value = value;
      this.klass = klass;
    }

    T orElse(T other) {
      return value == null ? other : klass.cast(this.value);
    }
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

  /**
   * Determines if a class is out of scope of analysis. If {@code workList} is either empty or only
   * contains {@code *}, no class is excluded.
   *
   * @param clazz Class name to process.
   * @return true, if class is excluded.
   */
  public boolean isOutOfScope(String clazz) {
    if (workList.size() == 0 || (workList.size() == 1 && workList.contains("*"))) {
      return false;
    }
    return !workList.contains(clazz);
  }

  /** Builder class for Config */
  public static class FixSerializationConfigBuilder {

    private boolean suggestEnabled;
    private boolean suggestEnclosing;
    private boolean logErrorEnabled;
    private boolean logErrorEnclosing;
    private String nullable;
    private String nonnull;
    private Set<String> workList;

    public FixSerializationConfigBuilder() {
      suggestEnabled = false;
      suggestEnclosing = false;
      logErrorEnabled = false;
      logErrorEnclosing = false;
      nullable = "javax.annotation.Nullable";
      nonnull = "javax.annotation.Nonnull";
      workList = Collections.singleton("*");
    }

    private String workListDisplay() {
      String display = workList.toString();
      return display.substring(1, display.length() - 1);
    }

    /**
     * Write the json representation of config at the given path.
     *
     * @param path Path to write the config file.
     */
    @SuppressWarnings("unchecked")
    public void writeInJson(String path) {
      JSONObject res = new JSONObject();
      JSONObject suggest = new JSONObject();
      suggest.put("ACTIVE", suggestEnabled);
      suggest.put("ENCLOSING", suggestEnclosing);
      res.put("SUGGEST", suggest);
      JSONObject annotation = new JSONObject();
      annotation.put("NULLABLE", nullable);
      annotation.put("NONNULL", nonnull);
      res.put("ANNOTATION", annotation);
      JSONObject logError = new JSONObject();
      logError.put("ACTIVE", logErrorEnabled);
      logError.put("ENCLOSING", logErrorEnclosing);
      res.put("LOG_ERROR", logError);
      res.put("WORK_LIST", workListDisplay());
      try {
        BufferedWriter file = Files.newBufferedWriter(Paths.get(path), Charset.defaultCharset());
        file.write(res.toJSONString());
        file.flush();
      } catch (IOException e) {
        System.err.println("Error happened in writing config." + e);
      }
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

    public FixSerializationConfigBuilder setWorkList(Set<String> workList) {
      this.workList = workList;
      return this;
    }

    public void write(String path) {
      writeInJson(path);
    }
  }
}
