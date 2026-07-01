package com.uber.lib.unannotated;

import java.util.function.Function;

/* @NullMarked */
public class LambdaModel {

  public static <U> LambdaBox<U> map(Function<String, ? extends /* @Nullable */ U> mapper) {
    return new LambdaBox<>();
  }

  public static String apply(Function<String, /* @Nullable */ String> mapper) {
    return mapper.apply("");
  }

  public static void consume(LambdaConsumer<? super /* @Nullable */ String> consumer) {}
}
