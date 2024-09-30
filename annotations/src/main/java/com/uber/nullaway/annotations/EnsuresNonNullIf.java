package com.uber.nullaway.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation describing a nullability post-condition for an instance method. Each parameter to
 * the annotation should be a field of the enclosing class. The method must ensure that the method
 * returns true in case the fields are non-null. The method can also return false in case the fields
 * are non-null (inverse logic), and in such case, you must set {@code result} to false. NullAway
 * verifies that the property holds.
 *
 * <p>Here is an example:
 *
 * <pre>
 * class Foo {
 *     {@literal @}Nullable Object theField;
 *
 *     {@literal @}EnsuresNonNullIf("theField", result=true) // "this.theField" is also valid
 *     boolean hasTheField() {
 *         return theField != null;
 *     }
 *
 *     void bar() {
 *         if(!hasTheField()) {
 *             return;
 *         }
 *
 *         // No error, NullAway knows theField is non-null after call to hasTheField()
 *         theField.toString();
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD})
public @interface EnsuresNonNullIf {
  /**
   * The list of fields that are non-null after the method returns the given result.
   *
   * @return The list of field names
   */
  String[] value();

  /**
   * The return value of the method under which the postcondition holds. The default is set to true,
   * which means the method should return true in case fields are non-null.
   *
   * @return true or false
   */
  boolean result() default true;
}
