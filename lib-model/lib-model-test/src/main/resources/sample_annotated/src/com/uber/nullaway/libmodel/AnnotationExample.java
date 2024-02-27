package com.uber.nullaway.libmodel;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class AnnotationExample {

    @Nullable
    public String makeUpperCase(String inputString) {
        if (inputString == null || inputString.isEmpty()) {
            return null;
        } else {
            return inputString.toUpperCase();
        }
    }
}
