package com.uber.nullaway.tools;

import com.uber.nullaway.fixer.KEYS;
import java.util.Objects;
import org.json.simple.JSONObject;

public class Fix {
  final String annotation;
  final String method;
  final String param;
  final String location;
  final String className;
  final String pkg;
  final String inject;
  String uri;

  public Fix(
      String annotation,
      String method,
      String param,
      String location,
      String className,
      String pkg,
      String uri,
      String inject) {
    this.annotation = annotation;
    this.method = method;
    this.param = param;
    this.location = location;
    this.className = className;
    this.pkg = pkg;
    this.uri = uri;
    this.inject = inject;
  }

  static Fix createFromJson(JSONObject fix) {
    return new Fix(
        fix.get(KEYS.ANNOTATION.label).toString(),
        fix.get(KEYS.METHOD.label).toString(),
        fix.get(KEYS.PARAM.label).toString(),
        fix.get(KEYS.LOCATION.label).toString(),
        fix.get(KEYS.CLASS.label).toString(),
        fix.get(KEYS.PKG.label).toString(),
        fix.get(KEYS.URI.label).toString(),
        fix.get(KEYS.INJECT.label).toString());
  }

  @Override
  public String toString() {
    return "\n  {"
        + "\n\tannotation='"
        + annotation
        + '\''
        + ", \n\tmethod='"
        + method
        + '\''
        + ", \n\tparam='"
        + param
        + '\''
        + ", \n\tlocation='"
        + location
        + '\''
        + ", \n\tclassName='"
        + className
        + '\''
        + ", \n\tpkg='"
        + pkg
        + '\''
        + ", \n\tinject='"
        + inject
        + '\''
        + ", \n\turi='"
        + uri
        + '\''
        + "\n  }\n";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Fix)) return false;
    Fix fix = (Fix) o;
    return Objects.equals(annotation, fix.annotation)
        && Objects.equals(method, fix.method)
        && Objects.equals(param, fix.param)
        && Objects.equals(location, fix.location)
        && Objects.equals(className, fix.className)
        && Objects.equals(pkg, fix.pkg)
        && Objects.equals(inject, fix.inject)
        && Objects.equals(uri, fix.uri);
  }

  @Override
  public int hashCode() {
    return Objects.hash(annotation, method, param, location, className, pkg, inject, uri);
  }
}
