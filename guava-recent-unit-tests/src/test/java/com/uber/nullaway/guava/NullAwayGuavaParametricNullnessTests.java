/*
 * Copyright (c) 2022 Uber Technologies, Inc.
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
package com.uber.nullaway.guava;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NullAwayGuavaParametricNullnessTests {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper defaultCompilationHelper;

  private CompilationTestHelper jspecifyCompilationHelper;

  @Before
  public void setup() {
    defaultCompilationHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass())
            .setArgs(
                Arrays.asList(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                    "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.nullaway.[a-zA-Z0-9.]+.unannotated"));
    jspecifyCompilationHelper =
        CompilationTestHelper.newInstance(NullAway.class, getClass())
            .setArgs(
                JSpecifyJavacConfig.withJSpecifyModeArgs(
                    List.of(
                        "-d",
                        temporaryFolder.getRoot().getAbsolutePath(),
                        "-XepOpt:NullAway:OnlyNullMarked=true")));
  }

  @Test
  public void testFutureCallbackParametricNullness() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.google.common.util.concurrent.FutureCallback;
            import javax.annotation.Nullable;
            class Test {
                public static <T> FutureCallback<T> wrapFutureCallback(FutureCallback<T> futureCallback) {
                    return new FutureCallback<T>() {
                        @Override
                        public void onSuccess(@Nullable T result) {
                            futureCallback.onSuccess(result);
                        }
                        @Override
                        public void onFailure(Throwable throwable) {
                            futureCallback.onFailure(throwable);
                        }
                    };
                }
            }
            """)
        .doTest();
  }

  @Test
  public void jspecifyFutureCallback() {
    jspecifyCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            import com.google.common.util.concurrent.FutureCallback;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
                public static <T> FutureCallback<@Nullable T> wrapFutureCallback(FutureCallback<@Nullable T> futureCallback) {
                    return new FutureCallback<@Nullable T>() {
                        @Override
                        public void onSuccess(@Nullable T result) {
                            futureCallback.onSuccess(result);
                        }
                        @Override
                        public void onFailure(Throwable throwable) {
                            futureCallback.onFailure(throwable);
                        }
                    };
                }
            }
            """)
        .doTest();
  }

  @Test
  public void testIterableParametricNullness() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.google.common.collect.ImmutableList;
            import com.google.common.collect.Iterables;
            import javax.annotation.Nullable;
            class Test {
                public static String test1() {
                    // BUG: Diagnostic contains: returning @Nullable expression
                    return Iterables.getFirst(ImmutableList.<String>of(), null);
                }
                public static @Nullable String test2() {
                    return Iterables.getFirst(ImmutableList.<String>of(), null);
                }
            }
            """)
        .doTest();
  }

  @Test
  public void jspecifyIterables() {
    jspecifyCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            import com.google.common.collect.ImmutableList;
            import com.google.common.collect.Iterables;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
                public static String test1() {
                    // BUG: Diagnostic contains: returning @Nullable expression
                    return Iterables.<@Nullable String>getFirst(ImmutableList.<String>of(), null);
                }
                public static @Nullable String test2() {
                    return Iterables.<@Nullable String>getFirst(ImmutableList.<String>of(), null);
                }
                public static String test3() {
                    return Iterables.getOnlyElement(ImmutableList.of("hi"));
                }
                public static String test4() {
                    // BUG: Diagnostic contains: returning @Nullable expression
                    return Iterables.<@Nullable String>getOnlyElement(ImmutableList.of("hi"));
                }
            }
            """)
        .doTest();
  }

  @Test
  public void jspecifyComparators() {
    jspecifyCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            import com.google.common.collect.Comparators;
            import java.util.Comparator;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
                public static String test1(String t1, String t2, Comparator<? super String> cmp) {
                    return Comparators.min(t1, t2, cmp);
                }
                public static String test2(@Nullable String t1, @Nullable String t2, Comparator<? super @Nullable String> cmp) {
                    // BUG: Diagnostic contains: returning @Nullable expression
                    return Comparators.<@Nullable String>min(t1, t2, cmp);
                }
            }
            """)
        .doTest();
  }

  @Test
  public void jspecifyFuturesLambdaReturningNull() {
    jspecifyCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            import com.google.common.util.concurrent.FluentFuture;
            import com.google.common.util.concurrent.Futures;
            import com.google.common.util.concurrent.ListenableFuture;
            import java.util.concurrent.Executor;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              ListenableFuture<@Nullable String> transformIntoNullableResult(ListenableFuture<String> in, Executor e) {
                return Futures.transform(in, x -> {
                  return null;
                }, e);
              }
              ListenableFuture<String> transformIntoNonNullResult(ListenableFuture<String> in, Executor e) {
                // BUG: Diagnostic contains: type variable O constrained to be both @NonNull and @Nullable
                return Futures.transform(in, x -> {
                  // BUG: Diagnostic contains: returning @Nullable expression
                  return null;
                }, e);
              }
              FluentFuture<@Nullable String> fluentTransformIntoNullableResult(FluentFuture<String> in, Executor e) {
                return in.transform(x -> {
                  return null;
                }, e);
              }
              FluentFuture<String> fluentTransformIntoNonNullResult(FluentFuture<String> in, Executor e) {
                // BUG: Diagnostic contains: type variable T constrained to be both @NonNull and @Nullable
                return in.transform(x -> {
                  // BUG: Diagnostic contains: returning @Nullable expression
                  return null;
                }, e);
              }
              ListenableFuture<@Nullable String> catchingIntoNullableResult(ListenableFuture<@Nullable String> in, Executor e) {
                return Futures.catching(in, Exception.class, t -> {
                  return null;
                }, e);
              }
              ListenableFuture<String> catchingIntoNonNullResult(ListenableFuture<String> in, Executor e) {
                // BUG: Diagnostic contains: type variable V constrained to be both @NonNull and @Nullable
                return Futures.catching(in, Exception.class, t -> {
                  // BUG: Diagnostic contains: returning @Nullable expression
                  return null;
                }, e);
              }
              ListenableFuture<@Nullable String> transformAsyncReturningNullFuture(ListenableFuture<String> in, Executor e) {
                return Futures.transformAsync(in, x -> {
                  // BUG: Diagnostic contains: returning @Nullable expression
                  return null;
                }, e);
              }
              ListenableFuture<@Nullable String> catchingAsyncReturningNullFuture(ListenableFuture<@Nullable String> in, Executor e) {
                return Futures.catchingAsync(in, Exception.class, t -> {
                  // BUG: Diagnostic contains: returning @Nullable expression
                  return null;
                }, e);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void jspecifyExtraFuturesClassLambdaReturningNull() {
    CompilationTestHelper.newInstance(NullAway.class, getClass())
        .setArgs(
            JSpecifyJavacConfig.withJSpecifyModeArgs(
                List.of(
                    "-d",
                    temporaryFolder.getRoot().getAbsolutePath(),
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:ExtraFuturesClasses=NullableFutures,NonNullFutures")))
        .addSourceLines(
            "NullableFutures.java",
            """
            import com.google.common.base.Function;
            import com.google.common.util.concurrent.Futures;
            import com.google.common.util.concurrent.ListenableFuture;
            import java.util.concurrent.Executor;
            import org.jspecify.annotations.*;
            @NullMarked
            public class NullableFutures {
              public static <V extends @Nullable Object> ListenableFuture<V> transform(
                  ListenableFuture<V> future, Function<V, V> function, Executor executor) {
                return Futures.transform(future, function, executor);
              }
            }
            """)
        .addSourceLines(
            "NonNullFutures.java",
            """
            import com.google.common.base.Function;
            import com.google.common.util.concurrent.Futures;
            import com.google.common.util.concurrent.ListenableFuture;
            import java.util.concurrent.Executor;
            import org.jspecify.annotations.*;
            @NullMarked
            public class NonNullFutures {
              public static <V> ListenableFuture<V> transform(
                  ListenableFuture<V> future, Function<V, V> function, Executor executor) {
                return Futures.transform(future, function, executor);
              }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            import com.google.common.util.concurrent.ListenableFuture;
            import java.util.concurrent.Executor;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              ListenableFuture<@Nullable String> nullableTypeVariable(ListenableFuture<@Nullable String> in, Executor e) {
                return NullableFutures.transform(in, x -> {
                  return null;
                }, e);
              }
              ListenableFuture<String> nonNullTypeVariable(ListenableFuture<String> in, Executor e) {
                // BUG: Diagnostic contains: its upper bound requires it to be @NonNull
                return NonNullFutures.transform(in, x -> {
                  // BUG: Diagnostic contains: returning @Nullable expression
                  return null;
                }, e);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testCloserParametricNullness() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.google.common.io.Closer;
            import java.io.Closeable;
            import java.io.FileInputStream;
            import javax.annotation.Nullable;
            class Test {
                public static FileInputStream test1(Closer closer, FileInputStream fis1) {
                    // safe: non-null arg to non-null return
                    return closer.register(fis1);
                }
                @Nullable
                public static FileInputStream test2(Closer closer, @Nullable FileInputStream fis2) {
                    // safe: nullable arg to nullable return
                    return closer.register(fis2);
                }
                public static FileInputStream test3(Closer closer, @Nullable FileInputStream fis3) {
                    // BUG: Diagnostic contains: returning @Nullable expression
                    return closer.register(fis3);
                }
            }
            """)
        .doTest();
  }

  @Test
  public void testFunctionMethodOverride() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.google.common.base.Function;
            import javax.annotation.Nullable;
            class Test {
                public static void testFunctionOverride() {
                    Function<Object, Object> f = new Function<Object, Object>() {
                        @Override
                        public Object apply(Object input) {
                            return input;
                         }
                    };
                }
                public static void testFunctionOverrideNullableReturn() {
                    Function<Object, Object> f = new Function<Object, Object>() {
                        @Override
                        @Nullable
                        // BUG: Diagnostic contains: method returns @Nullable, but superclass method
                        public Object apply(Object input) {
                            return null;
                         }
                    };
                }
            }
            """)
        .doTest();
  }

  @Test
  public void newHashSetPassingNullable() {
    jspecifyCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            import com.google.common.collect.Sets;
            import java.util.Set;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              public static void test(@Nullable String s) {
                Set<@Nullable String> params = Sets.newHashSet(s);
              }
            }
            """)
        .doTest();
  }

  /**
   * A library model makes {@code AsyncFunction.apply} take a non-null argument, as for Guava's
   * {@code Function}. Guava marks the parameter {@code @ParametricNullness}, which NullAway reads
   * as {@code @Nullable} outside JSpecify mode.
   */
  @Test
  public void guavaAsyncFunctionTakesNonNullArgument() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.google.common.util.concurrent.AsyncFunction;
            import com.google.common.util.concurrent.Futures;
            class Test {
              static AsyncFunction<String, Integer> dereferencesParameter() {
                return s -> Futures.immediateFuture(s.length());
              }
              static void passesNull(AsyncFunction<String, Integer> f) throws Exception {
                // BUG: Diagnostic contains: passing @Nullable parameter
                f.apply(null);
              }
            }
            """)
        .doTest();
  }

  /**
   * A library model makes {@code Maps.EntryTransformer.transformEntry} take and return non-null, as
   * for Guava's {@code Function}. Guava 32 and later mark both parameters and the return
   * {@code @ParametricNullness}, which NullAway reads as {@code @Nullable} outside JSpecify mode.
   */
  @Test
  public void guavaEntryTransformerTakesAndReturnsNonNull() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.google.common.collect.Maps;
            class Test {
              static Maps.EntryTransformer<String, String, Integer> dereferencesParameters() {
                return (k, v) -> k.length() + v.length();
              }
              static int dereferencesResult(Maps.EntryTransformer<String, String, Integer> t) {
                return t.transformEntry("a", "b").intValue();
              }
              static void passesNullKey(Maps.EntryTransformer<String, String, Integer> t) {
                // BUG: Diagnostic contains: passing @Nullable parameter
                t.transformEntry(null, "b");
              }
              static void passesNullValue(Maps.EntryTransformer<String, String, Integer> t) {
                // BUG: Diagnostic contains: passing @Nullable parameter
                t.transformEntry("a", null);
              }
              static Maps.EntryTransformer<String, String, Integer> returnsNull() {
                // BUG: Diagnostic contains: returning @Nullable expression
                return (k, v) -> { return null; };
              }
            }
            """)
        .doTest();
  }

  /**
   * In JSpecify mode, the type arguments of a Guava functional interface decide the nullness of its
   * parameters and return, and the non-null models of the standard mode do not apply.
   */
  @Test
  public void jspecifyGuavaFunctionalInterfacesFollowTypeArguments() {
    jspecifyCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.google.common.base.Function;
            import com.google.common.base.Predicate;
            import com.google.common.collect.Maps;
            import com.google.common.util.concurrent.AsyncFunction;
            import org.jspecify.annotations.*;
            @NullMarked
            class Test {
              static void functionPassesNullToNullableArgument(Function<@Nullable String, String> f) {
                f.apply(null);
              }
              static void functionPassesNullToNonNullArgument(Function<String, String> f) {
                // BUG: Diagnostic contains: passing @Nullable parameter
                f.apply(null);
              }
              static Function<@Nullable String, Integer> functionDereferencesNullableParameter() {
                // BUG: Diagnostic contains: dereferenced expression 's' is @Nullable
                return s -> s.length();
              }
              static Function<String, Integer> functionDereferencesNonNullParameter() {
                return s -> s.length();
              }
              static int functionDereferencesNullableResult(Function<String, @Nullable Integer> f) {
                // BUG: Diagnostic contains: dereferenced expression 'f.apply("a")' is @Nullable
                return f.apply("a").intValue();
              }
              static boolean predicatePassesNullToNullableArgument(Predicate<@Nullable String> p) {
                return p.apply(null);
              }
              static boolean predicatePassesNullToNonNullArgument(Predicate<String> p) {
                // BUG: Diagnostic contains: passing @Nullable parameter
                return p.apply(null);
              }
              static void asyncFunctionPassesNullToNullableArgument(
                  AsyncFunction<@Nullable String, String> f) throws Exception {
                f.apply(null);
              }
              static void asyncFunctionPassesNullToNonNullArgument(AsyncFunction<String, String> f)
                  throws Exception {
                // BUG: Diagnostic contains: passing @Nullable parameter
                f.apply(null);
              }
              static void entryTransformerPassesNullToNullableArguments(
                  Maps.EntryTransformer<@Nullable String, @Nullable String, String> t) {
                t.transformEntry(null, null);
              }
              static void entryTransformerPassesNullToNonNullArgument(
                  Maps.EntryTransformer<String, String, String> t) {
                // BUG: Diagnostic contains: passing @Nullable parameter
                t.transformEntry(null, "b");
              }
              static int entryTransformerDereferencesNullableResult(
                  Maps.EntryTransformer<String, String, @Nullable Integer> t) {
                // BUG: Diagnostic contains: dereferenced expression 't.transformEntry("a", "b")'
                return t.transformEntry("a", "b").intValue();
              }
            }
            """)
        .doTest();
  }
}
