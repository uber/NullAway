package com.uber.nullaway.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation describing a nullability pre-condition for an instance method. Each parameter to
 * the annotation should be a field of the enclosing class. Each call site of the method must ensure
 * that the fields listed in the annotation are non-null before the call. NullAway verifies that
 * this property holds, and uses the property when checking the body of the method. Here is an
 * example:
 *
 * <pre>
 * class Foo {
 *     {@literal @}Nullable Object theField;
 *     {@literal @}RequiresNonNull("theField") // @RequiresNonNull("this.theField") is also valid
 *     void foo() {
 *         // No error, NullAway knows theField is non-null after foo()
 *         theField.toString();
 *     }
 *     void bar() {
 *         // Error, theField may be null before the call to foo()
 *         foo();
 *         this.theField = new Object();
 *         // No error
 *         foo();
 *     }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface RequiresNonNull {
  String[] value();
}
