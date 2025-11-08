package com.uber.nullaway.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a method or constructor preserves the nullness environment when invoking lambda
 * expressions passed as parameters.
 *
 * <p>When a method or constructor is annotated with {@code @NullnessPreserving}, NullAway assumes
 * that any lambda arguments passed to it are invoked <em>synchronously</em> and that the invocation
 * does not modify the visible state or capture the lambda for later (asynchronous) execution. As a
 * result, NullAway treats the body of the lambda as being executed in the same nullness environment
 * as the code at the call site.
 *
 * <p>In other words, this annotation tells NullAway that:
 *
 * <ul>
 *   <li>The annotated method or constructor does not perform side effects on fields or global state
 *       visible at the call site.
 *   <li>Any functional parameters (e.g., lambdas or method references) are invoked synchronously
 *       within the method or constructor body and are not stored or invoked later.
 *   <li>Nullability information from the surrounding context is preserved inside the lambda body.
 * </ul>
 *
 * <p>This allows NullAway to safely reason about nullness within lambdas passed to such methods as
 * if the lambda were executed inline at the call site.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * @NullnessPreserving
 * public static <T> void runWith(T value, Consumer<T> action) {
 *     action.accept(value);
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface NullnessPreserving {}
