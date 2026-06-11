/*
 * Copyright (c) 2026 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.uber.nullaway.testdata;

import javax.annotation.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class NullAwayReactorSupportPositiveCases {

    static class NullableContainer<T> {
        @Nullable private T ref;

        public NullableContainer() {
            ref = null;
        }

        @Nullable
        public T get() {
            return ref;
        }

        public void set(T o) {
            ref = o;
        }
    }

    private static boolean perhaps() {
        return Math.random() > 0.5;
    }

    // no filter before map - should warn
    private Flux<Integer> fluxMapWithoutFilter(Flux<NullableContainer<String>> flux) {
        // BUG: Diagnostic contains: dereferenced expression
        return flux.map(c -> c.get().length());
    }

    // filter condition doesn't guarantee non-null - should warn
    private Flux<Integer> fluxFilterWithOrThenMap(Flux<NullableContainer<String>> flux) {
        return flux
                .filter(c -> c.get() != null || perhaps())
                .map(
                        c -> {
                            // BUG: Diagnostic contains: dereferenced expression
                            return c.get().length();
                        });
    }

    // Flux: doOnNext without filter - should warn
    private Flux<Integer> fluxDoOnNextWithoutFilter(Flux<NullableContainer<String>> flux) {
        return flux
                .doOnNext(
                        c -> {
                            // BUG: Diagnostic contains: dereferenced expression
                            System.out.println(c.get().length());
                        })
                .map(
                        c -> {
                            // BUG: Diagnostic contains: dereferenced expression
                            return c.get().length();
                        });
    }

    // Mono: no filter before map - should warn
    private Mono<Integer> monoMapWithoutFilter(Mono<NullableContainer<String>> mono) {
        // BUG: Diagnostic contains: dereferenced expression
        return mono.map(c -> c.get().length());
    }

    // Mono: doOnNext without filter - should warn
    private Mono<Integer> monoDoOnNextWithoutFilter(Mono<NullableContainer<String>> mono) {
        return mono
                .doOnNext(
                        c -> {
                            // BUG: Diagnostic contains: dereferenced expression
                            System.out.println(c.get().length());
                        })
                .map(
                        c -> {
                            // BUG: Diagnostic contains: dereferenced expression
                            return c.get().length();
                        });
    }
}
