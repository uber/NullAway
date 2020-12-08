package com.uber.nullaway.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Can annotate a methods with @RequiresNonnull(param) annotation where param is one of the classes
 * fields. It indicates a pre-condition for the method, that at every call site to this method, the
 * class field in the argument must be @Nonnull. If a method is annotated
 * with @RequiresNonnull(param), NullAway dataflow analysis is going to assume that the filed with
 * name param, is @Nonnull at the start point.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface RequiresNonNull {
  String[] value();
}
