package com.uber.nullaway.dataflow;

import com.sun.source.util.TreePath;
import javax.annotation.Generated;

@Generated("com.google.auto.value.processor.AutoValueProcessor")
final class AutoValue_DataFlow_CfgParams extends DataFlow.CfgParams {

  private final TreePath codePath;

  AutoValue_DataFlow_CfgParams(TreePath codePath) {
    if (codePath == null) {
      throw new NullPointerException("Null codePath");
    }
    this.codePath = codePath;
  }

  @Override
  TreePath codePath() {
    return codePath;
  }

  @Override
  public String toString() {
    return "CfgParams{" + "codePath=" + codePath + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof DataFlow.CfgParams) {
      DataFlow.CfgParams that = (DataFlow.CfgParams) o;
      return this.codePath.equals(that.codePath());
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h$ = 1;
    h$ *= 1000003;
    h$ ^= codePath.hashCode();
    return h$;
  }
}
