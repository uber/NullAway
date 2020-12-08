package com.uber.nullaway.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Can annotate a methods with @EnsuresNonnull(param) annotation where param is one of the classes
 * fields. It indicates a post-condition for the method, that at every call site to this method, the
 * class field in the argument is @Nonnull at exit point. If a method is annotated
 * with @EnsuresNonnull(param), NullAway checks weather the @Nonnull assumption of the field at exit
 * point is valid.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface EnsuresNonNull {
  String[] value();
}
