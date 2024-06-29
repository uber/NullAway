package com.uber.nullaway.dataflow;

import javax.lang.model.element.Element;

/**
 * Represents a generic element in an access path used for nullability analysis.
 *
 * <p>This interface abstracts over different kinds of path elements that can be part of an access
 * path, including fields and methods, or array indices. Implementations of this interface should
 * specify the type of the access path element:
 *
 * <ul>
 *   <li>{@code FieldOrMethodCallElement} - Represents access to a field or the invocation of a
 *       method, potentially with constant arguments.
 *   <li>{@code ArrayIndexElement} - Represents access to an array element either by a constant
 *       index or via an index that is calculated dynamically.
 * </ul>
 *
 * <p>The {@code getJavaElement()} method returns the corresponding Java {@link Element} that the
 * access path element refers to.
 */
public interface AccessPathElement {
  /**
   * Returns the Java element associated with this access path element.
   *
   * @return the Java {@link Element} related to this path element, such as a field, method, or the
   *     array itself.
   */
  Element getJavaElement();
}
