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

    public String @Nullable [] makeUpperCaseArray(String inputString) {
        if (inputString == null || inputString.isEmpty()) {
            return null;
        } else {
            String[] result = new String[1];
            result[0] = inputString.toUpperCase();
            return result;
        }
    }
}
