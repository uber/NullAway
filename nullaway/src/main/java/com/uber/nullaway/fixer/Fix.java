package com.uber.nullaway.fixer;

import java.io.Serializable;
import org.json.simple.JSONObject;

@SuppressWarnings({
  "UnusedVariable",
  "unchecked"
}) // TODO: remove this later, this class is still under construction on 'AutoFix' branch
public class Fix implements Serializable {
  Location location;
  AnnotationFactory.Annotation annotation;
  String reason;
  boolean inject;

  public JSONObject getJson() {
    JSONObject res = location.getJson();
    res.put(KEYS.REASON.label, (reason == null) ? "Undefined" : reason);
    res.put(KEYS.INJECT.label, "" + inject);
    res.put(KEYS.ANNOTATION.label, annotation.fullName.replace(";", ""));
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
        + inject
        + '}';
  }
}
