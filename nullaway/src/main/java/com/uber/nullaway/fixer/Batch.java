package com.uber.nullaway.fixer;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import java.io.Serializable;
import java.util.Set;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

@SuppressWarnings({
  "UnusedVariable",
  "PackageAccessibility",
  "UnusedMethod",
  "unchecked"
}) // This class is still under construction
public class Batch implements Serializable {

  Fix core;
  Set<Fix> chain;

  public Batch(Fix core) {
    this.core = core;
  }

  public void fillInheritanceChain(VisitorState state) {
    if (!(core.location.kind.equals(Location.Kind.METHOD_PARAM)
        || core.location.kind.equals(Location.Kind.METHOD_RETURN))) {
      return;
    }
    Set<Symbol.MethodSymbol> overriddenMethods =
        LocationUtils.getAllSuperMethods(ASTHelpers.getSymbol(core.location.methodTree), state);
    for (Symbol.MethodSymbol symbol : overriddenMethods) {
      System.out.println(symbol);
    }
  }

  public void setBatch(Set<Fix> fixes) {
    this.chain = fixes;
  }

  public void addToChain(Fix fix) {
    this.chain.add(fix);
  }

  public JSONObject getJson() {
    JSONObject res = new JSONObject();
    res.put("core", core.getJson());
    JSONArray chainJSON = new JSONArray();
    if (chain != null) {
      for (Fix fix : chain) {
        chainJSON.add(fix.getJson());
      }
    }
    res.put("chain", chainJSON);
    return res;
  }

  @Override
  public String toString() {
    return "Batch{" + "core=" + core + ", chain=" + chain + '}';
  }
}
