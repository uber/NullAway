package com.uber.nullaway.tools;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

public class Batch {
  Fix core;
  Set<Fix> chain;

  public Batch(Fix core, Fix... chain) {
    this.core = core;
    this.chain = new HashSet<>(Arrays.asList(chain));
  }

  public static Batch createFromJson(JSONObject batch) {
    Fix core = Fix.createFromJson((JSONObject) batch.get("core"));
    Fix[] chains = new Fix[0];
    Object chainJsonObject = batch.get("chain");
    if (chainJsonObject != null) {
      JSONArray chainJson = (JSONArray) chainJsonObject;
      chains = new Fix[chainJson.size()];
      for (int i = 0; i < chainJson.size(); i++) {
        chains[i] = Fix.createFromJson((JSONObject) chainJson.get(i));
      }
    }
    return new Batch(core, chains);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Batch)) return false;
    Batch batch = (Batch) o;
    if (!batch.core.equals(this.core)) {
      return false;
    }
    if (this.chain != null) {
      return this.chain.equals(batch.chain);
    }
    return batch.chain == null;
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.chain, this.core);
  }

  void setRootForUri(Path root) {
    core.uri = root.toAbsolutePath().toUri().toASCIIString().concat(core.uri);
    if (chain != null) {
      for (Fix f : chain) {
        f.uri = root.toAbsolutePath().toUri().toASCIIString().concat(f.uri);
      }
    }
  }

  @Override
  public String toString() {
    return "Batch{" + "core=" + core + ", chain=" + chain + '}';
  }
}
