package com.uber.nullaway.autofix.out.display;

import java.util.Objects;

public class CallGraphDisplay {
  public final String callerClass;
  public final String calleeMethod;
  public final String calleeClass;

  public CallGraphDisplay(String callerClass, String calleeMethod, String calleeClass) {
    this.callerClass = callerClass;
    this.calleeMethod = calleeMethod;
    this.calleeClass = calleeClass;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof CallGraphDisplay)) return false;
    CallGraphDisplay that = (CallGraphDisplay) o;
    return callerClass.equals(that.callerClass)
        && calleeMethod.equals(that.calleeMethod)
        && calleeClass.equals(that.calleeClass);
  }

  @Override
  public int hashCode() {
    return Objects.hash(callerClass, calleeMethod, calleeClass);
  }

  @Override
  public String toString() {
    return "CallGraphDisplay{"
        + "callerClass='"
        + callerClass
        + '\''
        + ", calleeMethod='"
        + calleeMethod
        + '\''
        + ", calleeClass='"
        + calleeClass
        + '\''
        + '}';
  }
}
