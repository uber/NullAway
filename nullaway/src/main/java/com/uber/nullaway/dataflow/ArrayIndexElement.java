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

  private ArrayIndexElement(Element javaElement, Object index) {
    this.javaElement = javaElement;
    this.index = index;
  }

  /**
   * Creates an ArrayIndexElement with an integer index.
   *
   * @param javaElement the element of the array
   * @param index the integer index to access the array
   * @return an instance of ArrayIndexElement
   */
  public static ArrayIndexElement withIntegerIndex(Element javaElement, Integer index) {
    return new ArrayIndexElement(javaElement, index);
  }

  /**
   * Creates an ArrayIndexElement with a local variable or field index.
   *
   * @param javaElement the element of the array
   * @param indexElement the local variable or field element representing the index
   * @return an instance of ArrayIndexElement
   */
  public static ArrayIndexElement withVariableIndex(Element javaElement, Element indexElement) {
    return new ArrayIndexElement(javaElement, indexElement);
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
