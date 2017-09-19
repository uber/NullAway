package com.uber.nullaway.testdata;

import java.util.function.ToIntFunction;

public class NullAwayOverrideFunctionalInterfaces {

    public void test() {
        call(str -> 42);
    }

    private int call(ObjToInt<String> f) {
        return f.call("The answer to life the universe and everything");
    }

    @FunctionalInterface
    private static interface ObjToInt<T> extends ObjToIntE<T, RuntimeException>, ToIntFunction<T> {
        @Override
        default int applyAsInt(T t) {
            return call(t);
        }
    }

    @FunctionalInterface
    private static interface ObjToIntE<T, E extends Exception> {
        int call(T t) throws E;
    }
}
