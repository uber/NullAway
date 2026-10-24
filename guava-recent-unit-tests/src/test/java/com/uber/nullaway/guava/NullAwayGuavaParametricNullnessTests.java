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
                            // BUG: Diagnostic contains: passing @Nullable parameter 'result'
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
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null'
                    return Iterables.getFirst(ImmutableList.<String>of(), null);
                }
                public static @Nullable String test2() {
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null'
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
                    // BUG: Diagnostic contains: passing @Nullable parameter 'fis2'
                    return closer.register(fis2);
                }
                public static FileInputStream test3(Closer closer, @Nullable FileInputStream fis3) {
                    // BUG: Diagnostic contains: returning @Nullable expression
                    return closer.register(
                        // BUG: Diagnostic contains: passing @Nullable parameter 'fis3'
                        fis3);
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

  @Test
  public void parametricNullnessReadAsTypeVariable() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.google.common.base.Function;
            import com.google.common.base.Optional;
            import com.google.common.base.Supplier;
            import com.google.common.collect.Iterables;
            import com.google.common.collect.Iterators;
            import com.google.common.collect.MapDifference;
            import com.google.common.collect.Maps;
            import com.google.common.collect.Multiset;
            import com.google.common.collect.Multimap;
            import com.google.common.collect.Ordering;
            import com.google.common.collect.PeekingIterator;
            import com.google.common.collect.Streams;
            import com.google.common.collect.Table;
            import com.google.common.hash.Funnel;
            import com.google.common.io.LineProcessor;
            import com.google.common.util.concurrent.ClosingFuture;
            import com.google.common.util.concurrent.FutureCallback;
            import com.google.common.util.concurrent.Futures;
            import com.google.common.util.concurrent.ListenableFuture;
            import com.google.common.util.concurrent.MoreExecutors;
            import java.util.Iterator;
            import java.util.List;
            import java.util.Map;
            import java.util.stream.Stream;
            import org.jspecify.annotations.Nullable;
            class Test {
                static int iterablesGetOnlyElementReturnsNonNull(List<String> list) {
                    return Iterables.getOnlyElement(list).length();
                }
                static int iterablesGetLastReturnsNonNull(List<String> list) {
                    return Iterables.getLast(list).length();
                }
                static int iteratorsGetNextWithNonNullDefaultReturnsNonNull(Iterator<String> it) {
                    return Iterators.getNext(it, "d").length();
                }
                static int orderingMaxReturnsNonNull(String a, String b) {
                    return Ordering.<String>natural().max(a, b).length();
                }
                static int futuresGetDoneReturnsNonNull(ListenableFuture<String> f) throws Exception {
                    return Futures.getDone(f).length();
                }
                static int supplierGetReturnsNonNull(Supplier<String> s) {
                    return s.get().length();
                }
                static int peekingIteratorReturnsNonNull(PeekingIterator<String> it) {
                    return it.peek().length() + it.next().length();
                }
                static int multisetEntryGetElementReturnsNonNull(Multiset.Entry<String> e) {
                    return e.getElement().length();
                }
                static int tableCellGettersReturnNonNull(Table.Cell<String, String, String> c) {
                    return c.getRowKey().length() + c.getValue().length();
                }
                static int valueDifferenceLeftValueReturnsNonNull(MapDifference.ValueDifference<String> d) {
                    return d.leftValue().length();
                }
                static int lineProcessorGetResultReturnsNonNull(LineProcessor<String> p) {
                    return p.getResult().length();
                }
                static Funnel<String> funnelLambdaParameterIsNonNull() {
                    return (s, into) -> into.putInt(s.length());
                }
                static Stream<Long> mapWithIndexLambdaParameterIsNonNull(Stream<String> stream) {
                    return Streams.mapWithIndex(stream, (s, i) -> s.length() + i);
                }
                static ClosingFuture<Integer> closingFunctionLambdaParameterIsNonNull(ListenableFuture<String> f) {
                    return ClosingFuture.from(f).transform((closer, s) -> s.length(), MoreExecutors.directExecutor());
                }
                static Map<String, Integer> entryTransformerLambdaParametersAreNonNull(Map<String, String> map) {
                    return Maps.transformEntries(map, (k, v) -> k.length() + v.length());
                }
                static FutureCallback<String> futureCallbackOverrideTakesNonNullParameter() {
                    return new FutureCallback<String>() {
                        @Override
                        public void onSuccess(String result) {
                            result.length();
                        }
                        @Override
                        public void onFailure(Throwable t) {}
                    };
                }
                static Integer functionApplyRejectsNull(Function<String, Integer> f) {
                    // BUG: Diagnostic contains: passing @Nullable parameter
                    return f.apply(null);
                }
                static Supplier<String> supplierLambdaCannotReturnNull() {
                    // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                    return () -> null;
                }
                // A false positive: standard mode does not read the @Nullable type argument, so it
                // treats V as non-null although null is a legal value here.
                static void nullValueForNullableTypeArgumentIsAFalsePositive(Multimap<String, @Nullable String> m) {
                    // BUG: Diagnostic contains: passing @Nullable parameter 'null'
                    m.put("k", null);
                }
                static int explicitlyNullableReturnStaysNullable(Optional<String> o) {
                    // BUG: Diagnostic contains: dereferenced expression 'o.orNull()' is @Nullable
                    return o.orNull().length();
                }
            }
            """)
        .doTest();
  }
}
