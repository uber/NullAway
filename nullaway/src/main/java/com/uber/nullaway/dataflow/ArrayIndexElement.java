package com.uber.nullaway.dataflow;

import java.util.Objects;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;

public class ArrayIndexElement implements AccessPathElement {
  private final Element javaElement;
  @Nullable private final Integer constantIndex;
  @Nullable private final Element variableElement;

  public ArrayIndexElement(Element javaElement, Integer constantIndex) {
    this.javaElement = javaElement;
    this.constantIndex = constantIndex;
    this.variableElement = null;
  }

  public ArrayIndexElement(Element javaElement, Element variableElement) {
    this.javaElement = javaElement;
    this.variableElement = variableElement;
    this.constantIndex = null;
  }

  @Override
  public Element getJavaElement() {
    return this.javaElement;
  }

  @Nullable
  public Integer getConstantIndex() {
    return this.constantIndex;
  }

  @Nullable
  public Element getVariableElement() {
    return this.variableElement;
  }

  @Override
  public String toString() {
    return "ArrayIndexElement{"
        + "javaElement="
        + javaElement
        + ", constantIndex="
        + constantIndex
        + ", variableElement="
        + (variableElement != null ? variableElement.getSimpleName() : null)
        + '}';
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof ArrayIndexElement) {
      ArrayIndexElement other = (ArrayIndexElement) obj;
      return Objects.equals(javaElement, other.javaElement)
          && Objects.equals(constantIndex, other.constantIndex)
          && Objects.equals(variableElement, other.variableElement);
    }
    return false;
  }

  @Override
  public int hashCode() {
    int result = javaElement.hashCode();
    result =
        31 * result
            + (constantIndex != null ? constantIndex.hashCode() : 0)
            + (variableElement != null ? variableElement.hashCode() : 0);
    return result;
  }
}
