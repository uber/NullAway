package com.uber.nullaway.dataflow;

import java.util.Objects;
import javax.lang.model.element.Element;

/**
 * Represents an array index element of an AccessPath, encapsulating access to array elements either
 * via constant or variable indices.
 *
 * <p>This class holds an element that represents the array itself and an index that specifies the
 * position within the array. The index can be a constant (Integer) if it's statically known, or an
 * Element representing a variable index.
 */
public class ArrayIndexElement implements AccessPathElement {
  private final Element javaElement;
  private final Object index;

  /**
   * Constructs an ArrayIndexElement.
   *
   * @param javaElement The element of the array.
   * @param index The index used to access the array. Must be either an Integer (for constant
   *     indices) or an Element (for variable indices).
   */
  public ArrayIndexElement(Element javaElement, Object index) {
    this.javaElement = javaElement;
    this.index = index;
  }

  @Override
  public Element getJavaElement() {
    return this.javaElement;
  }

  @Override
  public String toString() {
    return "ArrayIndexElement{"
        + "javaElement="
        + javaElement
        + ", index="
        + (index instanceof Element ? ((Element) index).getSimpleName() : index)
        + '}';
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof ArrayIndexElement) {
      ArrayIndexElement other = (ArrayIndexElement) obj;
      return Objects.equals(javaElement, other.javaElement) && Objects.equals(index, other.index);
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
