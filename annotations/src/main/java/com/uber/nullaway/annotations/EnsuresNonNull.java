package com.uber.nullaway.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation describing a nullability post-condition for an instance method. Each parameter to
 * the annotation should be a field of the enclosing class. The method must ensure that whenever the
 * method exits normally, the fields listed in the annotation are non-null. NullAway verifies that
 * this property holds. Here is an example:
 *
 * <pre>
 * class Foo {
 *     @Nullable Object theField;
 *     @EnsuresNonNull("theField") // @EnsuresNonNull("this.theField") is also valid
 *     void foo() {
 *         theField = new Object();
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface EnsuresNonNull {
  String[] value();
}
