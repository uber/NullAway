package com.uber.nullaway.tools;

import com.uber.nullaway.autofix.fixer.Fix.Keys;
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
  final String compulsory;
  String uri;

  public Fix(
      String annotation,
      String method,
      String param,
      String location,
      String className,
      String pkg,
      String uri,
      String inject,
      String compulsory) {
    this.annotation = annotation;
    this.method = method;
    this.param = param;
    this.location = location;
    this.className = className;
    this.pkg = pkg;
    this.uri = uri;
    this.inject = inject;
    this.compulsory = compulsory;
  }

  static Fix createFromJson(JSONObject fix) {
    return new Fix(
        fix.get(Keys.ANNOTATION.label).toString(),
        fix.get(Keys.METHOD.label).toString(),
        fix.get(Keys.PARAM.label).toString(),
        fix.get(Keys.LOCATION.label).toString(),
        fix.get(Keys.CLASS.label).toString(),
        fix.get(Keys.PKG.label).toString(),
        fix.get(Keys.URI.label).toString(),
        fix.get(Keys.INJECT.label).toString(),
        fix.get(Keys.COMPULSORY.label).toString());
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
        + ", \n\tcompulsory='"
        + compulsory
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
        && Objects.equals(uri, fix.uri)
        && Objects.equals(compulsory, fix.compulsory);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        annotation, method, param, location, className, pkg, inject, uri, compulsory);
  }
}
