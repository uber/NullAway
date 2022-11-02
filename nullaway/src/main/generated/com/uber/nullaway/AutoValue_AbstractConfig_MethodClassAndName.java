package com.uber.nullaway;

import javax.annotation.Generated;

@Generated("com.google.auto.value.processor.AutoValueProcessor")
final class AutoValue_AbstractConfig_MethodClassAndName extends AbstractConfig.MethodClassAndName {

  private final String enclosingClass;

  private final String methodName;

  AutoValue_AbstractConfig_MethodClassAndName(String enclosingClass, String methodName) {
    if (enclosingClass == null) {
      throw new NullPointerException("Null enclosingClass");
    }
    this.enclosingClass = enclosingClass;
    if (methodName == null) {
      throw new NullPointerException("Null methodName");
    }
    this.methodName = methodName;
  }

  @Override
  String enclosingClass() {
    return enclosingClass;
  }

  @Override
  String methodName() {
    return methodName;
  }

  @Override
  public String toString() {
    return "MethodClassAndName{"
        + "enclosingClass="
        + enclosingClass
        + ", "
        + "methodName="
        + methodName
        + "}";
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (o instanceof AbstractConfig.MethodClassAndName) {
      AbstractConfig.MethodClassAndName that = (AbstractConfig.MethodClassAndName) o;
      return this.enclosingClass.equals(that.enclosingClass())
          && this.methodName.equals(that.methodName());
    }
    return false;
  }

  @Override
  public int hashCode() {
    int h$ = 1;
    h$ *= 1000003;
    h$ ^= enclosingClass.hashCode();
    h$ *= 1000003;
    h$ ^= methodName.hashCode();
    return h$;
  }
}
