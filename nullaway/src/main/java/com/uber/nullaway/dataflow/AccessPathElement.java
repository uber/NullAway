package com.uber.nullaway.dataflow;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;

/**
 * Represents a (non-root) element of an AccessPath.
 *
 * <p>This is just a java Element (field, method, etc) in the access-path chain (e.g. f or g() in
 * x.f.g()). Plus, optionally, a list of constant arguments, allowing access path elements for
 * method calls with constant values (e.g. h(3) or k("STR_KEY") in x.h(3).g().k("STR_KEY")).
 */
public final class AccessPathElement {
  private final Element javaElement;
  @Nullable private final ImmutableList<String> constantArguments;

  public AccessPathElement(Element javaElement, List<String> constantArguments) {
    this.javaElement = javaElement;
    this.constantArguments = ImmutableList.copyOf(constantArguments);
  }

  public AccessPathElement(Element javaElement) {
    this.javaElement = javaElement;
    this.constantArguments = null;
  }

  public Element getJavaElement() {
    return this.javaElement;
  }

  public ImmutableList<String> getConstantArguments() {
    return this.constantArguments;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof AccessPathElement) {
      AccessPathElement otherNode = (AccessPathElement) obj;
      return this.javaElement.equals(otherNode.javaElement)
          && (constantArguments == null
              ? otherNode.constantArguments == null
              : constantArguments.equals(otherNode.constantArguments));
    } else {
      return false;
    }
  }

  @Override
  public int hashCode() {
    int result = javaElement.hashCode();
    result = 31 * result + (constantArguments != null ? constantArguments.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return "APElement{"
        + "javaElement="
        + javaElement.toString()
        + ", constantArguments="
        + Arrays.deepToString(constantArguments != null ? constantArguments.toArray() : null)
        + '}';
  }
}
