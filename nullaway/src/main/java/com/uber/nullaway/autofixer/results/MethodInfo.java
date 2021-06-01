package com.uber.nullaway.autofixer.results;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullabilityUtil;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.Element;
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

  private MethodInfo(String method, String clazz) {
    this.method = method;
    this.clazz = clazz;
  }

  public static MethodInfo findOrCreate(String method, String clazz) {
    MethodInfo methodInfo = new MethodInfo(method, clazz);
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
    return method.equals(that.method) && clazz.equals(that.clazz);
  }

  @Override
  public int hashCode() {
    return Objects.hash(method, clazz);
  }

  public JSONObject getJSON() {
    JSONObject info = new JSONObject();
    info.put("method", this.method);
    info.put("class", this.clazz);
    info.put("uri", this.uri);
    info.put("parent", this.parent);
    JSONArray nonNullFields = new JSONArray();
    Collections.addAll(nonNullFields, this.nonnullFields);
    info.put("fields", nonNullFields);
    return info;
  }

  public void setNonnullFieldsElements(Set<Element> nonnullFieldsAtExit) {
    List<String> fields = new ArrayList<>();
    for (Element element : nonnullFieldsAtExit) {
      fields.add(element.getSimpleName().toString());
    }
    this.nonnullFields = fields.toArray(new String[0]);
  }

  public void setParent(Symbol.MethodSymbol methodSymbol, VisitorState state) {
    Symbol.MethodSymbol superMethod =
        NullabilityUtil.getClosestOverriddenMethod(methodSymbol, state.getTypes());
    System.out.println("SUPER: " + superMethod);
    if (superMethod == null) {
      return;
    }
    Symbol.ClassSymbol enclosingClass = ASTHelpers.enclosingClass(superMethod);
    MethodInfo superMethodInfo = findOrCreate(superMethod.toString(), enclosingClass.toString());
    this.parent = superMethodInfo.id;
  }

  public void setUri(CompilationUnitTree c) {
    this.uri = c.getSourceFile().toUri().toASCIIString();
  }
}
