package com.uber.nullaway.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation describing a nullability post-condition for an instance method. Each parameter to
 * the annotation should be a field of the enclosing class. The method must ensure that whenever the
 * method exits normally, the fields listed in the annotation are non-null. NullAway verifies that
 * this property holds, and the property is used when checking call sites of the method. Here is an
 * example:
 *
 * <pre>
 * class Foo {
 *     {@literal @}Nullable Object theField;
 *     {@literal @}EnsuresNonNull("theField") // @EnsuresNonNull("this.theField") is also valid
 *     void foo() {
 *         theField = new Object();
 *     }
 *     void bar() {
 *         foo();
 *         // No error, NullAway knows theField is non-null after call to foo()
 *         theField.toString();
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface EnsuresNonNull {
  String[] value();
}
