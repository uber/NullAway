package com.uber.nullaway.autofixer.results;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

@SuppressWarnings({"unchecked", "EqualsGetClass"})
public class MethodInfo implements Serializable {
  int id;
  String method;
  String clazz;
  String uri;
  String[] nonnullFields = {};
  int parent = -1;

  private static int LAST_ID = 0;
  private static final HashSet<MethodInfo> discovered = new HashSet<>();

  private MethodInfo(String method, String clazz, String uri) {
    this.method = method;
    this.clazz = clazz;
    this.uri = uri;
  }

  public static MethodInfo findOrCreate(String method, String clazz, String uri) {
    MethodInfo methodInfo = new MethodInfo(method, clazz, uri);
    if (discovered.contains(methodInfo)) {
      for (MethodInfo info : discovered) {
        if (info.equals(methodInfo)) {
          methodInfo = info;
          break;
        }
      }
    } else {
      methodInfo.id = LAST_ID++;
      discovered.add(methodInfo);
    }
    return methodInfo;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    MethodInfo that = (MethodInfo) o;
    return method.equals(that.method) && clazz.equals(that.clazz) && uri.equals(that.uri);
  }

  @Override
  public int hashCode() {
    return Objects.hash(method, clazz, uri);
  }

  public JSONObject getJSON() {
    JSONObject info = new JSONObject();
    info.put("method", this.method);
    info.put("class", this.clazz);
    info.put("uri", this.uri);
    info.put("id", this.id);
    info.put("parent", this.parent);
    JSONArray nonNullFields = new JSONArray();
    Collections.addAll(nonNullFields, this.nonnullFields);
    info.put("fields", nonNullFields);
    return info;
  }
}
