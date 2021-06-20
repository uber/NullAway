package com.uber.nullaway.autofixer;

import com.google.common.base.Preconditions;
import com.sun.source.util.Trees;
import com.uber.nullaway.autofixer.qual.AnnotationFactory;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import javax.lang.model.element.Element;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

public class ExplorerConfig {

  public final boolean MAKE_METHOD_TREE_INHERITANCE_ENABLED;
  public final boolean SUGGEST_ENABLED;
  public final boolean PARAM_TEST_ENABLED;
  public final boolean LOG_ERROR_ENABLED;
  public final boolean OPTIMIZED;
  public final long PARAM_INDEX;
  public final AnnotationFactory ANNOTATION_FACTORY;

  public ExplorerConfig() {
    MAKE_METHOD_TREE_INHERITANCE_ENABLED = false;
    SUGGEST_ENABLED = false;
    PARAM_TEST_ENABLED = false;
    LOG_ERROR_ENABLED = false;
    OPTIMIZED = false;
    PARAM_INDEX = 0L;
    ANNOTATION_FACTORY = new AnnotationFactory();
  }

  public ExplorerConfig(String filePath) {
    Preconditions.checkNotNull(filePath);
    JSONObject jsonObject;
    try {
      Object obj =
          new JSONParser()
              .parse(Files.newBufferedReader(Paths.get(filePath), Charset.defaultCharset()));
      ;
      jsonObject = (JSONObject) obj;
    } catch (Exception e) {
      throw new RuntimeException("Error in reading/parsing config at path: " + filePath);
    }
    MAKE_METHOD_TREE_INHERITANCE_ENABLED =
        getValueFromKey(jsonObject, "MAKE_METHOD_INHERITANCE_TREE", Boolean.class).orElse(false);
    SUGGEST_ENABLED = getValueFromKey(jsonObject, "SUGGEST", Boolean.class).orElse(false);
    PARAM_TEST_ENABLED =
        getValueFromKey(jsonObject, "METHOD_PARAM_TEST:ACTIVE", Boolean.class).orElse(false);
    LOG_ERROR_ENABLED = getValueFromKey(jsonObject, "LOG_ERROR", Boolean.class).orElse(false);
    OPTIMIZED = getValueFromKey(jsonObject, "OPTIMIZED", Boolean.class).orElse(false);
    PARAM_INDEX = getValueFromKey(jsonObject, "METHOD_PARAM_TEST:INDEX", Long.class).orElse(0L);
    String nullableAnnot =
        getValueFromKey(jsonObject, "ANNOTATION:NULLABLE", String.class)
            .orElse("javax.annotation.Nullable");
    String nonnullAnnot =
        getValueFromKey(jsonObject, "ANNOTATION:NONNULL", String.class)
            .orElse("javax.annotation.Nonnull");
    this.ANNOTATION_FACTORY = new AnnotationFactory(nullableAnnot, nonnullAnnot);
    if (SUGGEST_ENABLED) {
      makeDirectories();
    }
  }

  private void makeDirectories() {
    try {
      Files.createDirectories(Paths.get("/tmp/NullAwayFix/"));
    } catch (IOException e) {
      throw new RuntimeException("Could not create the directories for fix json file");
    }
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
  }

  public boolean canFixElement(Trees trees, Element symbol) {
    if (!SUGGEST_ENABLED) return false;
    if (trees == null || symbol == null) return false;
    return trees.getPath(symbol) != null;
  }
}
