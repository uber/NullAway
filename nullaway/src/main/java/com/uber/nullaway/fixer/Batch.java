package com.uber.nullaway.fixer;

import java.io.Serializable;
import java.util.Set;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class Batch implements Serializable {

  Fix core;
  Set<Fix> chain;

  public Batch(Fix core) {
    this.core = core;
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
