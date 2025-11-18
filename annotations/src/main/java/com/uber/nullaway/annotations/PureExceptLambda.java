package com.uber.nullaway.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a method or constructor preserves the nullness environment when invoking lambda
 * expressions passed as parameters.
 *
 * <p>You can only use this on methods which have a single lambda callback. Otherwise, the purity
 * cannot be guaranteed.
 *
 * <p>When a method or constructor is annotated with {@code @PureExceptLambda}, NullAway assumes
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
 *   <li>The single functional parameter (e.g., lambda or method reference) is invoked synchronously
 *       within the method or constructor body and are not stored or invoked later.
 *   <li>Nullability information from the surrounding context is preserved inside the lambda body.
 * </ul>
 *
 * <p>This allows NullAway to safely reason about nullness within lambdas passed to such methods as
 * if the lambda were executed inline at the call site.
 *
 * <p><b>Important:</b> NullAway does <em>not</em> verify that annotated methods actually satisfy
 * these requirements. The annotation represents a contract that developers must uphold manually.
 * Misuse of this annotation (e.g., annotating methods that perform side effects or invoke lambdas
 * asynchronously) can lead to unsound nullability analysis. Note that this annotation requires only
 * side-effect freedom; determinism (always producing the same result for the same inputs) is not
 * required.
 *
 * <p><b>Example:</b>
 *
 * <pre>{@code
 * @PureExceptLambda
 * public static <T> void runWith(T value, Consumer<T> action) {
 *     action.accept(value);
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface PureExceptLambda {}
