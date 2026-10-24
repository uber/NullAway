package com.uber.lib.generics;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A type parameter whose bound holds a {@code @Nullable} type argument, used to test that such an
 * annotation is not mistaken for an annotation on the bound itself when read from bytecode.
 */
public class NestedNullableInBound<T extends List<@Nullable String>> {}
