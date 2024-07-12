package com.test;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class ParameterAnnotationExample {

    public static Integer add(Integer a, Integer b) {
        return a + b;
    }

    public static Object getNewObjectIfNull(@Nullable Object object) {
        if (object == null) {
            return new Object();
        } else {
            return object;
        }
    }
}
