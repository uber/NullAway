package com.uber.nullaway.autofix;

import com.google.common.base.Preconditions;
import com.sun.source.util.Trees;
import com.uber.nullaway.autofix.qual.AnnotationFactory;
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

public class AutoFixConfig {

  public final boolean suggestEnabled;
  public final boolean suggestDeep;
  public final boolean logErrorEnabled;
  public final boolean logErrorDeep;
  public final String outputDirectory;
  public final AnnotationFactory annotationFactory;
  public final Set<String> workList;
  public final Writer writer;

  public AutoFixConfig() {
    suggestEnabled = false;
    suggestDeep = false;
    logErrorEnabled = false;
    logErrorDeep = false;
    annotationFactory = new AnnotationFactory();
    workList = Collections.singleton("*");
    outputDirectory = "/tmp/NullAwayFix";
    writer = new Writer(this);
  }

  public AutoFixConfig(boolean autofixEnabled, String outputDirectory) {
    Preconditions.checkNotNull(outputDirectory);
    JSONObject jsonObject = null;
    if (autofixEnabled) {
      try {
        Object obj =
            new JSONParser()
                .parse(
                    Files.newBufferedReader(
                        Paths.get(outputDirectory, "explorer.config"), Charset.defaultCharset()));
        jsonObject = (JSONObject) obj;
      } catch (Exception e) {
        throw new RuntimeException(
            "Error in reading/parsing config at path: " + outputDirectory + "\n" + e);
      }
    }
    suggestEnabled =
        getValueFromKey(jsonObject, "SUGGEST:ACTIVE", Boolean.class).orElse(false)
            && autofixEnabled;
    suggestDeep =
        getValueFromKey(jsonObject, "SUGGEST:DEEP", Boolean.class).orElse(false) && suggestEnabled;
    logErrorEnabled =
        getValueFromKey(jsonObject, "LOG_ERROR:ACTIVE", Boolean.class).orElse(false)
            && autofixEnabled;
    logErrorDeep =
        getValueFromKey(jsonObject, "LOG_ERROR:DEEP", Boolean.class).orElse(false)
            && autofixEnabled;
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

  public boolean canFixElement(Trees trees, Element symbol) {
    if (!suggestEnabled) return false;
    if (trees == null || symbol == null) return false;
    return trees.getPath(symbol) != null;
  }

  public boolean isOutOfScope(String clazz) {
    if (workList.size() == 1 && workList.contains("*")) {
      return false;
    }
    return !workList.contains(clazz);
  }

  public static class AutoFixConfigBuilder {

    private boolean suggestEnabled;
    private boolean suggestDeep;
    private boolean logErrorEnabled;
    private boolean logErrorDeep;
    private String nullable;
    private String nonnull;
    private Set<String> workList;

    public AutoFixConfigBuilder() {
      suggestEnabled = false;
      suggestDeep = false;
      logErrorEnabled = false;
      logErrorDeep = false;
      nullable = "javax.annotation.Nullable";
      nonnull = "javax.annotation.Nonnull";
      workList = Collections.singleton("*");
    }

    private String workListDisplay() {
      String display = workList.toString();
      return display.substring(1, display.length() - 1);
    }

    @SuppressWarnings("unchecked")
    public void writeInJson(String path) {
      JSONObject res = new JSONObject();
      JSONObject suggest = new JSONObject();
      suggest.put("ACTIVE", suggestEnabled);
      suggest.put("DEEP", suggestDeep);
      res.put("SUGGEST", suggest);
      JSONObject annotation = new JSONObject();
      annotation.put("NULLABLE", nullable);
      annotation.put("NONNULL", nonnull);
      res.put("ANNOTATION", annotation);
      JSONObject logError = new JSONObject();
      logError.put("ACTIVE", logErrorEnabled);
      logError.put("DEEP", logErrorDeep);
      res.put("LOG_ERROR", logError);
      JSONObject paramTest = new JSONObject();
      res.put("METHOD_PARAM_TEST", paramTest);
      JSONObject virtualAnnot = new JSONObject();
      res.put("VIRTUAL", virtualAnnot);
      res.put("WORK_LIST", workListDisplay());
      try {
        BufferedWriter file = Files.newBufferedWriter(Paths.get(path), Charset.defaultCharset());
        file.write(res.toJSONString());
        file.flush();
      } catch (IOException e) {
        System.err.println("Error happened in writing config.");
      }
    }

    public AutoFixConfigBuilder setSuggest(boolean value, boolean isDeep) {
      suggestEnabled = value;
      if (suggestEnabled) {
        suggestDeep = isDeep;
      }
      return this;
    }

    public AutoFixConfigBuilder setSuggest(boolean suggest, String nullable, String nonnull) {
      suggestEnabled = suggest;
      if (!suggest) {
        throw new RuntimeException("SUGGEST must be activated");
      }
      this.nullable = nullable;
      this.nonnull = nonnull;
      return this;
    }

    public AutoFixConfigBuilder setLogError(boolean value, boolean isDeep) {
      logErrorEnabled = value;
      if (!value && isDeep) {
        throw new RuntimeException("Log error must be enabled to activate deep log error");
      }
      logErrorDeep = isDeep;
      return this;
    }

    public AutoFixConfigBuilder setWorkList(Set<String> workList) {
      this.workList = workList;
      return this;
    }

    public void write(String path) {
      writeInJson(path);
    }
  }
}
