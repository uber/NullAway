package com.uber.nullaway.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation describing a nullability post-condition for an instance method. Each parameter to
 * the annotation should be a field of the enclosing class. The method must ensure that the method
 * returns true in case the fields are non-null. NullAway verifies that this property holds.
 *
 * <p>Here is an example:
 *
 * <pre>
 * class Foo {
 *     {@literal @}Nullable Object theField;
 *     {@literal @}EnsuresNonNullIf("theField") // @EnsuresNonNullIf("this.theField") is also valid
 *     boolean hasTheField() {
 *         return theField != null;
 *     }
 *     void bar() {
 *         if(!hasTheField()) {
 *             return;
 *         }
 *         // No error, NullAway knows theField is non-null after call to hasTheField()
 *         theField.toString();
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD})
public @interface EnsuresNonNullIf {
  String[] value();
}
