package com.uber.nullaway.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that once the field becomes non-null, it never becomes null again. Inspired by the
 * identically-named annotation from the Checker Framework. A {@code @MonotonicNonNull} field can
 * only be assigned non-null values. The key reason to use this annotation with NullAway is to
 * enable reasoning about field non-nullness in nested lambdas / anonymous classes, e.g.:
 *
 * <pre>
 * class Foo {
 *   {@literal @}MonotonicNonNull Object theField;
 *   void foo() {
 *     theField = new Object();
 *     Runnable r = () -> {
 *       // No error, NullAway knows theField is non-null after assignment
 *       theField.toString();
 *     }
 *   }
 * }
 * </pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.FIELD)
public @interface MonotonicNonNull {}
