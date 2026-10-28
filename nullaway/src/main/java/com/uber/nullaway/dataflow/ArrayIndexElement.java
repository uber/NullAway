package com.uber.nullaway.dataflow;

import java.util.Objects;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import org.jspecify.annotations.Nullable;

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
   * Creates an array index only when the variable's symbol faithfully represents the index.
   *
   * <p>Instance fields require a receiver, which this representation cannot retain. Reject them
   * rather than letting guards, reads, or writes conflate different receivers. Static fields and
   * local variables have no receiver dependency and are invalidated when their symbols change.
   *
   * @param javaElement the element of the array
   * @param indexElement the local variable or field element representing the index
   * @return an instance of ArrayIndexElement, or {@code null} for a receiver-dependent index
   */
  public static @Nullable ArrayIndexElement withVariableIndex(
      Element javaElement, Element indexElement) {
    if (indexElement.getKind().isField()
        && !indexElement.getModifiers().contains(Modifier.STATIC)) {
      return null;
    }
    return new ArrayIndexElement(javaElement, indexElement);
  }

  /** Returns the represented index: an {@link Integer} constant or a variable {@link Element}. */
  public Object getIndex() {
    return index;
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
        + (index instanceof Element element ? element.getSimpleName() : index)
        + '}';
  }

  @Override
  public boolean equals(@Nullable Object obj) {
    if (obj instanceof ArrayIndexElement other) {
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
