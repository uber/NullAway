package com.uber.nullaway.dataflow;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.lang.model.element.Element;
import org.jspecify.annotations.Nullable;

/**
 * Represents a (non-root) field or method call element of an AccessPath.
 *
 * <p>This is just a java Element (field or method call) in the access-path chain (e.g. f or g() in
 * x.f.g()). Plus, optionally, a list of constant arguments, allowing access path elements for
 * method calls with constant values (e.g. h(3) or k("STR_KEY") in x.h(3).g().k("STR_KEY")).
 */
public class FieldOrMethodCallElement implements AccessPathElement {
  private final Element javaElement;
  private final @Nullable ImmutableList<String> constantArguments;

  public FieldOrMethodCallElement(Element javaElement, List<String> constantArguments) {
    this.javaElement = javaElement;
    this.constantArguments = ImmutableList.copyOf(constantArguments);
  }

  public FieldOrMethodCallElement(Element javaElement) {
    this.javaElement = javaElement;
    this.constantArguments = null;
  }

  @Override
  public Element getJavaElement() {
    return this.javaElement;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof FieldOrMethodCallElement) {
      FieldOrMethodCallElement other = (FieldOrMethodCallElement) obj;
      return this.javaElement.equals(other.javaElement)
          && Objects.equals(this.constantArguments, other.constantArguments);
    }
    return false;
  }

  @Override
  public int hashCode() {
    int result = javaElement.hashCode();
    result = 31 * result + (constantArguments != null ? constantArguments.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return "FieldOrMethodCallElement{"
        + "javaElement="
        + javaElement
        + ", constantArguments="
        + (constantArguments != null ? Arrays.toString(constantArguments.toArray()) : "null")
        + '}';
  }
}
