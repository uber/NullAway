package com.test;

import org.jspecify.annotations.NonNull;
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

    public static void printObjectString(@NonNull Object object) {
        System.out.println(object.toString());
    }

    public static void takesNullArray(Object @Nullable [] objects) {
        System.out.println(objects);
    }

    public static void takesNonNullArray(Object[] objects) {
        String unused = objects.toString();
    }

    public static class Generic<T> {

        public String getString(@Nullable T t) {
            return t != null ? t.toString() : "";
        }

        public void printObjectString(T t) {
            System.out.println(t.toString());
        }
    }
}
