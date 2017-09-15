package com.uber.nullaway.testdata;

public class NullAwaySuperFunctionalInterface {

    public void passLambda() {
        runLambda1(() -> {});
        runLambda2(() -> {});
    }

    private void runLambda1(F1 f) {
        f.call();
    }

    private void runLambda2(F2 f) {
        f.call();
    }

    @FunctionalInterface
    private static interface F2 extends M0, F1 {}

    private static interface M0 {}

    @FunctionalInterface
    private static interface F1 extends F0 {}

    @FunctionalInterface
    private static interface F0 {
        void call();
    }
}