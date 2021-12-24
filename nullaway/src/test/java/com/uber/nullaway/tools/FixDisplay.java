package com.uber.nullaway.tools;

import java.util.Objects;

public class FixDisplay {
  public final String annotation;
  public final String method;
  public final String param;
  public final String location;
  public final String className;
  public String uri;

  public FixDisplay(
      String annotation,
      String method,
      String param,
      String location,
      String className,
      String uri) {
    this.annotation = annotation;
    this.method = method;
    this.param = param;
    this.location = location;
    this.className = className;
    this.uri = uri;
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
        + ", \n\turi='"
        + uri
        + '\''
        + "\n  }\n";
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof FixDisplay)) return false;
    FixDisplay fix = (FixDisplay) o;
    return Objects.equals(annotation, fix.annotation)
        && Objects.equals(method, fix.method)
        && Objects.equals(param, fix.param)
        && Objects.equals(location, fix.location)
        && Objects.equals(className, fix.className)
        && Objects.equals(uri, fix.uri);
  }

  @Override
  public int hashCode() {
    return Objects.hash(annotation, method, param, location, className, uri);
  }

  public static FixDisplay fromCSVLine(String line, String delimiter) {
    String[] infos = line.split(delimiter);
    return new FixDisplay(infos[7], infos[2], infos[3], infos[0], infos[1], infos[5]);
  }
}
