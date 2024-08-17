package com.uber.lib.generics;

import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public class JavaUtilFunctionMethods {

  public static void withFunction(Function<String, @Nullable String> f) {}
}
