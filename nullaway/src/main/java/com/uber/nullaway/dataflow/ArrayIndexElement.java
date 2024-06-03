package com.uber.nullaway.dataflow;

import java.util.Objects;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;

public class ArrayIndexElement implements AccessPathElement {
  private final Element javaElement;
  @Nullable final String index;

  public ArrayIndexElement(Element javaElement, @Nullable String index) {
    this.javaElement = javaElement;
    this.index = index;
  }

  @Override
  public Element getJavaElement() {
    return this.javaElement;
  }

  @Override
  public String toString() {
    return "ArrayIndexElement{" + "javaElement=" + javaElement + ", index=" + index + '}';
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof ArrayIndexElement) {
      ArrayIndexElement otherNode = (ArrayIndexElement) obj;
      return this.javaElement.equals(otherNode.javaElement)
          && Objects.equals(index, otherNode.index);
    }
    return false;
  }

  @Override
  public int hashCode() {
    int result = javaElement.hashCode();
    result = 31 * result + (index != null ? index.hashCode() : 0);
    return result;
  }
}
