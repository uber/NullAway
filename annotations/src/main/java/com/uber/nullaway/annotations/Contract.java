package com.uber.nullaway.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies method or constructor behavior depending on the arguments.
 *
 * <p>This annotation is inspired by {@code org.jetbrains.annotations.Contract} and follows the same
 * contract-string semantics. It is provided in the {@code com.uber.nullaway.annotations} package so
 * users can depend directly on NullAway's annotations artifact.
 *
 * <p>The contract string has the following grammar:
 *
 * <pre>{@code
 * contract ::= (clause ';')* clause
 * clause ::= args '->' effect
 * args ::= ((arg ',')* arg)?
 * arg ::= value-constraint
 * value-constraint ::= '_' | 'null' | '!null' | 'false' | 'true'
 * effect ::= value-constraint | 'fail' | 'this' | 'new' | 'param<N>'
 * }</pre>
 *
 * <p>For example, {@code "null -> fail"} describes a method that throws if the argument is {@code
 * null}, and {@code "!null -> !null"} describes a method that returns a non-null value when passed
 * a non-null value.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface Contract {

  /**
   * Contains the contract clauses describing relationships between call arguments and the returned
   * value or exceptional behavior.
   *
   * @return the contract string
   */
  String value();
}
