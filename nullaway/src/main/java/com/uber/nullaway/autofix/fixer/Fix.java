package com.uber.nullaway.autofix.fixer;

import com.uber.nullaway.autofix.qual.AnnotationFactory;
import java.io.Serializable;
import org.json.simple.JSONObject;

@SuppressWarnings({
  "UnusedVariable",
  "unchecked"
}) // TODO: remove this later, this class is still under construction on 'AutoFix' branch
public class Fix implements Serializable {
  public Location location;
  public AnnotationFactory.Annotation annotation;
  public String reason;
  public boolean inject;
  public boolean compulsory;

  public enum Keys {
    PARAM("param"),
    METHOD("method"),
    LOCATION("location"),
    CLASS("class"),
    PKG("pkg"),
    URI("uri"),
    INJECT("inject"),
    ANNOTATION("annotation"),
    REASON("reason"),
    COMPULSORY("compulsory");
    public final String label;

    Keys(String label) {
      this.label = label;
    }

    @Override
    public String toString() {
      return "KEYS{" + "label='" + label + '\'' + '}';
    }
  }

  public JSONObject getJson() {
    JSONObject res = location.getJson();
    res.put(Keys.REASON.label, (reason == null) ? "Undefined" : reason);
    res.put(Keys.INJECT.label, "" + inject);
    res.put(Keys.ANNOTATION.label, annotation.fullName.replace(";", ""));
    res.put(Keys.COMPULSORY.label, "" + compulsory);
    return res;
  }

  @Override
  public String toString() {
    return "Fix{"
        + "location="
        + location
        + ", annotation="
        + annotation
        + ", inject="
        + reason
        + ", reason="
        + compulsory
        + ", compulsory="
        + inject
        + '}';
  }
}
