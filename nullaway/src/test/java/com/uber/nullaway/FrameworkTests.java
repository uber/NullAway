package com.uber.nullaway;

import com.uber.nullaway.tools.DualModeCompilationTestHelper;
import java.util.Arrays;
import org.junit.Test;

public class FrameworkTests extends NullAwayTestsBase {

  /** The {@code java.lang.annotation} imports needed by the annotation stubs in this class. */
  private static final String ANNOTATION_IMPORTS =
      """
      import java.lang.annotation.ElementType;
      import java.lang.annotation.Retention;
      import java.lang.annotation.RetentionPolicy;
      import java.lang.annotation.Target;
      """;

  /** {@code @Target} for annotations applicable to types and fields. */
  private static final String TARGET_TYPE_FIELD = "@Target({ElementType.TYPE, ElementType.FIELD})";

  /** {@code @Target} for annotations applicable to fields and parameters. */
  private static final String TARGET_FIELD_PARAMETER =
      "@Target({ElementType.FIELD, ElementType.PARAMETER})";

  /** {@code @Target} for annotations applicable to fields only. */
  private static final String TARGET_FIELD = "@Target(ElementType.FIELD)";

  /** {@code @Retention} shared by all the annotation stubs in this class. */
  private static final String RETENTION_RUNTIME = "@Retention(RetentionPolicy.RUNTIME)";

  @Test
  public void lombokSupportTesting() {
    defaultCompilationHelper
        .addSourceLines(
            "LombokBuilderInit.java",
            """
            package com.uber.nullaway.testdata.lombok;

            import javax.annotation.Nullable;
            import lombok.Builder;

            @Builder
            public class LombokBuilderInit {
              private String field;
              @Builder.Default private String fieldWithDefault = "Default";
              @Nullable private String nullableField;
            }
            """)
        .doTest();
  }

  @Test
  public void coreNullabilityNativeModels() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayNativeModels.java",
            """
            package com.uber.nullaway.testdata;

            import android.webkit.WebView;
            import com.google.common.collect.ImmutableList;
            import com.google.common.collect.ImmutableMap;
            import com.google.common.collect.ImmutableSet;
            import com.google.common.collect.ImmutableSortedSet;
            import com.google.common.collect.Iterables;
            import java.io.File;
            import java.lang.ref.WeakReference;
            import java.net.URLClassLoader;
            import java.util.ArrayDeque;
            import java.util.Collection;
            import java.util.Deque;
            import java.util.HashMap;
            import java.util.LinkedHashMap;
            import java.util.Map;
            import java.util.Optional;
            import java.util.concurrent.atomic.AtomicReference;
            import javax.annotation.Nullable;
            import javax.lang.model.element.Element;
            import javax.lang.model.util.Elements;

            public class NullAwayNativeModels {

              public static void referenceStuff() {
                AtomicReference<Object> ref = new AtomicReference<>(null);
                Object x = ref.get();
                // BUG: Diagnostic contains: dereferenced expression
                x.toString();
                // BUG: Diagnostic contains: dereferenced expression
                ref.get().toString();
                WeakReference<Object> w = new WeakReference<Object>(x);
                // BUG: Diagnostic contains: dereferenced expression
                w.get().hashCode();
                Exception e = new RuntimeException();
                // BUG: Diagnostic contains: dereferenced expression
                e.getMessage().hashCode();
                // BUG: Diagnostic contains: dereferenced expression
                e.getLocalizedMessage().hashCode();
                // BUG: Diagnostic contains: dereferenced expression
                e.getCause().toString();
              }

              // we will add bug annotations when we have full support for maps
              public static void mapStuff(Map<Object, Object> m) {
                // BUG: Diagnostic contains: dereferenced expression
                m.get(new Object()).toString();
                Object value = m.get(new Object());
                // BUG: Diagnostic contains: dereferenced expression
                value.toString();
                HashMap<Object, Object> h = new HashMap<>();
                Object value2 = h.get(new Object());
                // BUG: Diagnostic contains: dereferenced expression
                value2.toString();
              }

              static void mapGetNullCheck() {
                Object x = new Object();
                Map<Object, Object> m = new HashMap<>();
                if (m.get(x) != null) {
                  m.get(x).toString();
                }
                HashMap<Object, Object> m2 = (HashMap) m;
                if (m2.get(x) != null) {
                  m2.get(x).hashCode();
                }
              }

              static void mapContainsKeyCheck() {
                Object x = new Object();
                Map<Object, Object> m = new HashMap<>();
                if (m.containsKey(x)) {
                  m.get(x).toString();
                }
                if (m.containsKey(x)) {
                  Object y = m.get(x);
                  y.toString();
                }
                HashMap<Object, Object> m2 = (HashMap) m;
                if (m2.containsKey(x)) {
                  m2.get(x).hashCode();
                }
                if (m2.containsKey(x)) {
                  Object y = m2.get(x);
                  y.hashCode();
                }
                Object z = new Object();
                if (m2.containsKey(z)) {
                  // BUG: Diagnostic contains: dereferenced expression
                  m2.get(x).hashCode();
                }
                if (m2.containsKey(z)) {
                  Object y = m2.get(x);
                  // BUG: Diagnostic contains: dereferenced expression
                  y.hashCode();
                }
                // test negation
                if (!m2.containsKey(x)) {
                  return;
                }
                Object y = m2.get(x);
                y.hashCode();
              }

              static class Wrapper {

                Object wrapped = new Object();

                public Object getWrapped() {
                  return wrapped;
                }
              }

              static final String KEY = "key";

              static void harderMapContainsKeyCheck() {
                Map m = new HashMap();
                Wrapper w = new Wrapper();
                if (m.containsKey(w.getWrapped())) {
                  m.get(w.getWrapped()).toString();
                }
                if (m.containsKey(w.getWrapped())) {
                  Object o = m.get(w.getWrapped());
                  o.toString();
                }
                if (m.get(w.getWrapped()) != null) {
                  m.get(w.getWrapped()).toString();
                }
                if (m.get(w.getWrapped()) != null) {
                  Object o = m.get(w.getWrapped());
                  o.toString();
                }
                if (m.containsKey(KEY)) {
                  m.get(KEY).toString();
                }
                if (m.containsKey(KEY)) {
                  Object o = m.get(KEY);
                  o.toString();
                }
              }

              static void testLinkedHashMap() {
                LinkedHashMap m = new LinkedHashMap();
                Object o = new Object();
                if (m.containsKey(o)) {
                  m.get(o).toString();
                }
              }

              static void mapContainsKeyPut() {
                Object x = new Object();
                Map<Object, Object> m = new HashMap<>();
                if (!m.containsKey(x)) {
                  m.put(x, new Object());
                }
                m.get(x).toString();
                HashMap<Object, Object> m2 = new HashMap<>();
                if (!m2.containsKey(x)) {
                  m2.put(x, x);
                }
                m2.get(x).toString();
                Object y = new Object(), z = new Object();
                if (!m2.containsKey(z)) {
                  m2.put(y, new Object());
                }
                // BUG: Diagnostic contains: dereferenced expression
                m2.get(z).toString();
                LinkedHashMap m3 = new LinkedHashMap();
                if (!m3.containsKey(y)) {
                  m3.put(y, new Object());
                }
                m3.get(y).hashCode();
              }

              static void immutableMapStuff() {
                ImmutableMap m = ImmutableMap.of();
                Object res = m.get(new Object());
                // BUG: Diagnostic contains: dereferenced expression
                res.toString();
                Object x = new Object();
                if (m.containsKey(x)) {
                  m.get(x).toString();
                }
              }

              static void mapCheckWithPrimitiveUnboxing(int key) {
                Map<Integer, Object> m = new HashMap<>();
                if (m.containsKey(key)) {
                  m.get(key).hashCode();
                }
              }

              static void mapCheckWithPrimitiveUnboxingLong(long key) {
                Map<Integer, Object> m = new HashMap<>();
                if (m.containsKey(key)) {
                  m.get(key).hashCode();
                }
              }

              static void mapCheckWithStringConstantKey() {
                Map<String, Object> m = new HashMap<>();
                if (m.containsKey("key")) {
                  m.get("key").hashCode();
                }
              }

              static void mapCheckWithIntConstantKey() {
                Map<String, Object> m = new HashMap<>();
                if (m.containsKey(42)) {
                  m.get(42).hashCode();
                }
              }

              static void mapCheckWithWideningNode() {
                Map<Long, String> m = new HashMap<>();
                m.put(Long.valueOf(42), "");
              }

              static void failIfNull(
                  @Nullable Object o1,
                  @Nullable Object o2,
                  @Nullable Object o3,
                  @Nullable Object o4,
                  @Nullable Object o5) {
                org.junit.Assert.assertNotNull(o1);
                o1.toString();
                org.junit.Assert.assertNotNull("Null!", o2);
                o2.toString();
                org.junit.jupiter.api.Assertions.assertNotNull(o3);
                o3.toString();
                org.junit.jupiter.api.Assertions.assertNotNull(o4, "Null!");
                o4.toString();
                org.junit.jupiter.api.Assertions.assertNotNull(o5, () -> "Null!");
                o5.toString();
              }

              static void nonNullParameters() {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                NullAwayNativeModels.class.getResource(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                NullAwayNativeModels.class.isAssignableFrom(null);
                String s = null;
                // BUG: Diagnostic contains: passing @Nullable parameter 's' where @NonNull is required
                File f = new File(s);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                URLClassLoader.newInstance(null, NullAwayNativeModels.class.getClassLoader());
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                Optional<Object> op = Optional.of(null);
              }

              static void elementStuff(Element e, Elements elems) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                e.getAnnotation(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                elems.getPackageElement(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                elems.getTypeElement(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                elems.getDocComment(null);
              }

              static void arrayDequeStuff() {
                ArrayDeque<Object> d = new ArrayDeque<>();
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.add(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.addFirst(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.addLast(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.offerFirst(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.offerLast(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.offer(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.push(null);
                Object[] o = null;
                // BUG: Diagnostic contains: passing @Nullable parameter 'o' where @NonNull is required
                d.toArray(o);
                // this should be fine
                d.toArray();
              }

              static void dequeStuff() {
                Deque<Object> d = new ArrayDeque<>();
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.add(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.addFirst(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.addLast(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.offerFirst(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.offerLast(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.offer(null);
                // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                d.push(null);
                Object[] o = null;
                // BUG: Diagnostic contains: passing @Nullable parameter 'o' where @NonNull is required
                d.toArray(o);
              }

              static void guavaStuff() {
                Collection<String> c = null;
                Object o = null;
                // BUG: Diagnostic contains: passing @Nullable parameter 'c' where @NonNull is required
                ImmutableList.builder().addAll(c).build();
                // BUG: Diagnostic contains: passing @Nullable parameter 'o' where @NonNull is required
                ImmutableList.builder().add(o).build();
                // BUG: Diagnostic contains: passing @Nullable parameter 'c' where @NonNull is required
                ImmutableSet.builder().addAll(c).build();
                // BUG: Diagnostic contains: passing @Nullable parameter 'o' where @NonNull is required
                ImmutableSet.builder().add(o).build();
                // BUG: Diagnostic contains: passing @Nullable parameter 'c' where @NonNull is required
                ImmutableSortedSet.builder().addAll(c).build();
                // BUG: Diagnostic contains: passing @Nullable parameter 'o' where @NonNull is required
                ImmutableSortedSet.builder().add(o).build();
                // BUG: Diagnostic contains: passing @Nullable parameter 'c' where @NonNull is required
                Iterables.getFirst(c, "hi");
              }

              static void androidStuff() {
                android.webkit.WebView webView = new WebView();
                // BUG: Diagnostic contains: dereferenced expression
                webView.getUrl().toString();
                String s = null;
                if (!android.text.TextUtils.isEmpty(s)) {
                  // no warning due to isEmpty check
                  s.hashCode();
                }
              }

              static void apacheCommonsStuff() {
                String s = null;
                if (!org.apache.commons.lang.StringUtils.isEmpty(s)) {
                  // no warning due to isEmpty check
                  s.hashCode();
                }
                String t = null;
                if (!org.apache.commons.lang3.StringUtils.isEmpty(t)) {
                  // no warning due to isEmpty check
                  t.hashCode();
                }
              }
            }
            """)
        .addSourceLines(
            "WebView.java",
            """
            package android.webkit;

            public class WebView {

              public String getUrl() {
                return null;
              }
            }
            """)
        .addSourceLines(
            "TextUtils.java",
            """
            package android.text;

            public class TextUtils {

              public static boolean isEmpty(CharSequence c) {
                return false;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void rxSupportPositiveCases() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayRxSupportPositiveCases.java",
            """
            package com.uber.nullaway.testdata;

            import io.reactivex.Observable;
            import io.reactivex.functions.Function;
            import io.reactivex.functions.Predicate;
            import javax.annotation.Nullable;

            public class NullAwayRxSupportPositiveCases {

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

              private Observable<Integer> filterWithIfThenMapNullableContainerNullableOnSomeBranch(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) throws Exception {
                            if (container.get() != null) {
                              return true;
                            } else {
                              return perhaps();
                            }
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> c) throws Exception {
                            // BUG: Diagnostic contains: dereferenced expression
                            return c.get().length();
                          }
                        });
              }

              private Observable<Integer> filterWithIfThenMapNullableContainerNullableOnSomeBranchAnyOrder(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) throws Exception {
                            if (container.get() == null) {
                              return perhaps();
                            } else {
                              return true;
                            }
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> c1) throws Exception {
                            // BUG: Diagnostic contains: dereferenced expression
                            return c1.get().length();
                          }
                        });
              }

              private Observable<Integer> filterWithOrExpressionThenMapNullableContainer(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) throws Exception {
                            return container.get() != null || perhaps();
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> container) throws Exception {
                            // BUG: Diagnostic contains: dereferenced expression
                            return container.get().length();
                          }
                        });
              }

              private Observable<Integer> filterWithLambdaNullExpressionBody(Observable<String> observable) {
                // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return
                // type
                return observable.map(o -> perhaps() ? o : null).map(o -> o.length());
              }

              private Observable<Integer> filterThenMapNullableContainerLambdas(
                  Observable<NullableContainer<String>> observable) {
                // BUG: Diagnostic contains: dereferenced expression
                return observable.filter(c -> c.get() != null || perhaps()).map(c -> c.get().length());
              }

              private Observable<Integer> filterThenMapMethodRefs1(
                  Observable<NullableContainer<String>> observable) {
                // this is to make sure the analysis doesn't get confused by two instances of the same method
                // ref
                Object o =
                    observable
                        .filter(c -> c.get() != null && perhaps())
                        .map(NullableContainer::get)
                        .map(String::length);
                return observable
                    .filter(c -> c.get() != null || perhaps())
                    // BUG: Diagnostic contains: referenced method returns @Nullable
                    .map(NullableContainer::get)
                    .map(String::length);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void rxSupportNegativeCases() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayRxSupportNegativeCases.java",
            """
            package com.uber.nullaway.testdata;

            import com.google.common.collect.ImmutableList;
            import io.reactivex.Maybe;
            import io.reactivex.Observable;
            import io.reactivex.ObservableSource;
            import io.reactivex.Single;
            import io.reactivex.functions.BiPredicate;
            import io.reactivex.functions.Function;
            import io.reactivex.functions.Predicate;
            import javax.annotation.Nullable;

            public class NullAwayRxSupportNegativeCases {

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

              private Observable<Integer> filterThenMap(Observable<String> observable) {
                return observable
                    .filter(
                        new Predicate<String>() {
                          @Override
                          public boolean test(String s) throws Exception {
                            return s != null;
                          }
                        })
                    .map(
                        new Function<String, Integer>() {
                          @Override
                          public Integer apply(String s) throws Exception {
                            return s.length();
                          }
                        });
              }

              private Observable<Integer> filterWithIfThenMapNullableContainer(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) throws Exception {
                            if (container.get() != null) {
                              return true;
                            } else {
                              return false;
                            }
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> c) throws Exception {
                            return c.get().length();
                          }
                        });
              }

              private Observable<Integer> filterWithNEExpressionThenMapNullableContainer(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) throws Exception {
                            return container.get() != null;
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> container) throws Exception {
                            return container.get().length();
                          }
                        });
              }

              private Observable<Integer> filterWithAndExpressionThenMapNullableContainer(
                  Observable<NullableContainer<NullableContainer<String>>> observable) {
                return observable
                    .filter(
                        new Predicate<NullableContainer<NullableContainer<String>>>() {
                          @Override
                          public boolean test(NullableContainer<NullableContainer<String>> container)
                              throws Exception {
                            return container.get() != null && container.get().get() != null;
                          }
                        })
                    .map(
                        new Function<NullableContainer<NullableContainer<String>>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<NullableContainer<String>> container)
                              throws Exception {
                            return container.get().get().length();
                          }
                        });
              }

              private Observable<Integer> filterThenMapNullableContainerMergesReturns(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) throws Exception {
                            if (perhaps() && container.get() != null) {
                              return true;
                            } else {
                              return (container.get() != null);
                            }
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> c) throws Exception {
                            return c.get().length();
                          }
                        });
              }

              private Observable<Integer> filterThenMapNullableContainerWPassthroughMethods(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) throws Exception {
                            return container.get() != null;
                          }
                        })
                    .distinctUntilChanged()
                    .distinct()
                    .flatMap(
                        new Function<NullableContainer<String>, ObservableSource<Integer>>() {
                          @Override
                          public ObservableSource<Integer> apply(NullableContainer<String> container)
                              throws Exception {
                            return io.reactivex.Observable.fromIterable(
                                ImmutableList.of(container.get().length(), container.get().length()));
                          }
                        });
              }

              private Observable<NullableContainer<String>> filterThenDistinctUntilChanged(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) throws Exception {
                            return container.get() != null;
                          }
                        })
                    .distinctUntilChanged(
                        new BiPredicate<NullableContainer<String>, NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> nc1, NullableContainer<String> nc2) {
                            return nc1.get().length() == nc2.get().length()
                                && nc1.get().contains(nc2.get())
                                && nc2.get().contains(nc1.get());
                          }
                        });
              }

              private static class NoOpFilterClass<T> implements Predicate<T> {
                public NoOpFilterClass() {}

                public boolean test(T o) throws Exception {
                  return true;
                }
              }

              private Observable<Integer> filterThenMapDoesntBreakWithNonAnnonClass(
                  Observable<String> observable) {
                return observable
                    .filter(new NoOpFilterClass<String>())
                    .map(
                        new Function<String, Integer>() {
                          @Override
                          public Integer apply(String s) throws Exception {
                            // No new nullability facts, this test is only to ensure our handler doesn't
                            // break the checker when using Observables with non-annonymous functions.
                            return s.length();
                          }
                        });
              }

              private Maybe<Integer> testMaybe(Maybe<NullableContainer<String>> maybe) {
                return maybe
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) throws Exception {
                            return container.get() != null;
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> c) throws Exception {
                            return c.get().length();
                          }
                        });
              }

              private Maybe<Integer> testSingle(Single<NullableContainer<String>> single) {
                return single
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) throws Exception {
                            return container.get() != null;
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> c) throws Exception {
                            return c.get().length();
                          }
                        });
              }

              private Observable<Integer> filterThenMapLambdas(Observable<String> observable) {
                return observable.filter(s -> s != null).map(s -> s.length());
              }

              private Observable<Integer> filterThenMapNullableContainerLambdas(
                  Observable<NullableContainer<String>> observable) {
                return observable.filter(c -> c.get() != null).map(c -> c.get().length());
              }

              private Observable<Integer> filterThenMapNullableContainerLambdas2(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(
                        c -> {
                          if (c.get() == null) {
                            return false;
                          } else {
                            return true;
                          }
                        })
                    .map(c -> c.get().length());
              }

              private Observable<Integer> filterThenMapNullableContainerLambdas3(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(c -> c.get() != null)
                    .map(
                        c -> {
                          String s = c.get();
                          return s.length();
                        });
              }

              private Observable<Integer> filterThenMapLambdas4(Observable<String> observable) {
                return observable.filter(s -> s != null && perhaps()).map(s -> s.length());
              }

              private Observable<Integer> filterThenDoOnNextThenMapLambdas(Observable<String> observable) {
                return observable
                    .filter(s -> s != null && perhaps())
                    .doOnNext(
                        s -> {
                          if (s.length() == 0) {
                            throw new Error();
                          } else {
                            return;
                          }
                        })
                    .map(s -> s.length());
              }

              private Observable<Integer> filterThenDoOnNextThenMapLambdas2(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(c -> c.get() != null && perhaps())
                    .doOnNext(
                        c -> {
                          String s = c.get();
                          if (s.length() == 0) {
                            throw new Error();
                          } else {
                            return;
                          }
                        })
                    .map(
                        c -> {
                          String s = c.get();
                          return s.length();
                        });
              }

              private static <T> boolean predtest(Predicate<T> f, T val) {
                try {
                  return f.test(val);
                } catch (Exception e) {
                  return false;
                }
              }

              private static <T, R> R funcapply(Function<T, R> f, T val) throws Exception {
                return f.apply(val);
              }

              private Observable<Integer> filterThenMapLambdas5(Observable<String> observable) {
                return observable
                    .filter(s -> predtest(r -> r != null, s))
                    .map(s -> funcapply(r -> r.length(), s));
              }

              private Observable<Integer> filterThenMapMethodRefs1(
                  Observable<NullableContainer<String>> observable) {
                return observable
                    .filter(c -> c.get() != null && perhaps())
                    .map(NullableContainer::get)
                    .map(String::length);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void streamSupportNegativeCases() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayStreamSupportNegativeCases.java",
            """
            package com.uber.nullaway.testdata;

            import com.google.common.base.Preconditions;
            import com.google.common.collect.ImmutableList;
            import com.uber.nullaway.testdata.unannotated.CustomStream;
            import java.util.function.Function;
            import java.util.function.Predicate;
            import java.util.stream.DoubleStream;
            import java.util.stream.IntStream;
            import java.util.stream.LongStream;
            import java.util.stream.Stream;
            import javax.annotation.Nullable;

            public class NullAwayStreamSupportNegativeCases {

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

              private Stream<Integer> filterThenMap(Stream<String> stream) {
                return stream
                    .filter(
                        new Predicate<String>() {
                          @Override
                          public boolean test(String s) {
                            return s != null;
                          }
                        })
                    .map(
                        new Function<String, Integer>() {
                          @Override
                          public Integer apply(String s) {
                            return s.length();
                          }
                        });
              }

              private Stream<Integer> filterWithIfThenMapNullableContainer(
                  Stream<NullableContainer<String>> stream) {
                return stream
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) {
                            if (container.get() != null) {
                              return true;
                            } else {
                              return false;
                            }
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> c) {
                            return c.get().length();
                          }
                        });
              }

              private Stream<Integer> filterWithNEExpressionThenMapNullableContainer(
                  Stream<NullableContainer<String>> stream) {
                return stream
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) {
                            return container.get() != null;
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> container) {
                            return container.get().length();
                          }
                        });
              }

              private Stream<Integer> filterWithAndExpressionThenMapNullableContainer(
                  Stream<NullableContainer<NullableContainer<String>>> stream) {
                return stream
                    .filter(
                        new Predicate<NullableContainer<NullableContainer<String>>>() {
                          @Override
                          public boolean test(NullableContainer<NullableContainer<String>> container) {
                            return container.get() != null && container.get().get() != null;
                          }
                        })
                    .map(
                        new Function<NullableContainer<NullableContainer<String>>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<NullableContainer<String>> container) {
                            return container.get().get().length();
                          }
                        });
              }

              private Stream<Integer> filterThenMapNullableContainerMergesReturns(
                  Stream<NullableContainer<String>> stream) {
                return stream
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) {
                            if (perhaps() && container.get() != null) {
                              return true;
                            } else {
                              return (container.get() != null);
                            }
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> c) {
                            return c.get().length();
                          }
                        });
              }

              private Stream<Integer> filterThenMapNullableContainerWPassthroughMethods(
                  Stream<NullableContainer<String>> stream) {
                return stream
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) {
                            return container.get() != null;
                          }
                        })
                    .distinct()
                    .flatMap(
                        new Function<NullableContainer<String>, Stream<Integer>>() {
                          @Override
                          public Stream<Integer> apply(NullableContainer<String> container) {
                            return ImmutableList.of(container.get().length(), container.get().length())
                                .stream();
                          }
                        });
              }

              private Stream<String> filterThenMapStreamOfMapsWithGet(
                  Stream<java.util.Map<String, Integer>> stream) {
                return stream.filter(m -> m.get("hello") != null).map(n -> n.get("hello").toString());
              }

              private static class NoOpFilterClass<T> implements Predicate<T> {
                public NoOpFilterClass() {}

                public boolean test(T o) {
                  return true;
                }
              }

              private Stream<Integer> filterThenMapDoesntBreakWithNonAnnonClass(Stream<String> observable) {
                return observable
                    .filter(new NoOpFilterClass<String>())
                    .map(
                        new Function<String, Integer>() {
                          @Override
                          public Integer apply(String s) {
                            // No new nullability facts, this test is only to ensure our handler doesn't
                            // break the checker when using Streams with non-annonymous functions.
                            return s.length();
                          }
                        });
              }

              private Stream<Integer> filterThenMapLambdas(Stream<String> observable) {
                return observable.filter(s -> s != null).map(s -> s.length());
              }

              private Stream<Integer> filterThenMapNullableContainerLambdas(
                  Stream<NullableContainer<String>> observable) {
                return observable.filter(c -> c.get() != null).map(c -> c.get().length());
              }

              private Stream<Integer> filterThenMapNullableContainerLambdas2(
                  Stream<NullableContainer<String>> observable) {
                return observable
                    .filter(
                        c -> {
                          if (c.get() == null) {
                            return false;
                          } else {
                            return true;
                          }
                        })
                    .map(c -> c.get().length());
              }

              private Stream<Integer> filterThenMapNullableContainerLambdas3(
                  Stream<NullableContainer<String>> observable) {
                return observable
                    .filter(c -> c.get() != null)
                    .map(
                        c -> {
                          String s = c.get();
                          return s.length();
                        });
              }

              private Stream<Integer> filterThenMapLambdas4(Stream<String> observable) {
                return observable.filter(s -> s != null && perhaps()).map(s -> s.length());
              }

              private static <T> boolean predtest(Predicate<T> f, T val) {
                try {
                  return f.test(val);
                } catch (Exception e) {
                  return false;
                }
              }

              private static <T, R> R funcapply(Function<T, R> f, T val) {
                return f.apply(val);
              }

              private Stream<Integer> filterThenMapLambdas5(Stream<String> observable) {
                return observable
                    .filter(s -> predtest(r -> r != null, s))
                    .map(s -> funcapply(r -> r.length(), s));
              }

              private Stream<Integer> filterThenMapMethodRefs1(Stream<NullableContainer<String>> observable) {
                return observable
                    .filter(c -> c.get() != null && perhaps())
                    .map(NullableContainer::get)
                    .map(String::length);
              }

              private IntStream filterThenMapToInt(Stream<NullableContainer<String>> stream) {
                return stream.filter(c -> c.get() != null).mapToInt(c -> c.get().length());
              }

              private LongStream filterThenMapToLong(Stream<NullableContainer<String>> stream) {
                return stream.filter(c -> c.get() != null).mapToLong(c -> c.get().length());
              }

              private DoubleStream filterThenMapToDouble(Stream<NullableContainer<String>> stream) {
                return stream.filter(c -> c.get() != null).mapToDouble(c -> c.get().length());
              }

              private void filterThenForEach(Stream<NullableContainer<String>> stream) {
                stream.filter(s -> s.get() != null).forEach(s -> System.out.println(s.get().length()));
              }

              private void filterThenForEachOrdered(Stream<NullableContainer<String>> stream) {
                stream.filter(s -> s.get() != null).forEachOrdered(s -> System.out.println(s.get().length()));
              }

              // CustomStream is modeled in TestLibraryModels
              private CustomStream<Integer> filterThenMapLambdasCustomStream(CustomStream<String> stream) {
                return stream.filter(s -> s != null).map(s -> s.length());
              }

              private CustomStream<Integer> filterThenMapNullableContainerLambdasCustomStream(
                        CustomStream<NullableContainer<String>> stream) {
                    return stream
                            .filter(c -> c.get() != null)
                            .map(c -> c.get().length());
                }

              private CustomStream<Integer> filterThenMapMethodRefsCustomStream(
                  CustomStream<NullableContainer<String>> stream) {
                return stream
                    .filter(c -> c.get() != null && perhaps())
                    .map(NullableContainer::get)
                    .map(String::length);
              }

              private static class CheckFinalBeforeStream<T> {
                @Nullable private final T ref;

                public CheckFinalBeforeStream(@Nullable T ref) {
                  this.ref = ref;
                }

                private Stream<T> test1(Stream<T> stream) {
                  Preconditions.checkNotNull(ref);
                  final T asLocal = ref;
                  return stream.filter(s -> asLocal.equals(s));
                }

                private Stream<T> test2(Stream<T> stream) {
                  Preconditions.checkNotNull(ref);
                  // Safe because ref is final!
                  return stream.filter(s -> ref.equals(s));
                }

                private Stream<T> test3(Stream<T> stream) {
                  if (ref != null) {
                    // Safe because ref is final!
                    return stream.filter(s -> ref.equals(s));
                  } else {
                    return stream.filter(s -> "CONST".equals(s.toString()));
                  }
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void streamSupportPositiveCases() {
    defaultCompilationHelper
        .addSourceLines(
            "NullAwayStreamSupportPositiveCases.java",
            """
            package com.uber.nullaway.testdata;

            import com.google.common.base.Preconditions;
            import com.uber.nullaway.testdata.unannotated.CustomStreamWithoutModel;
            import java.util.function.Function;
            import java.util.function.Predicate;
            import java.util.stream.DoubleStream;
            import java.util.stream.IntStream;
            import java.util.stream.LongStream;
            import java.util.stream.Stream;
            import javax.annotation.Nullable;

            public class NullAwayStreamSupportPositiveCases {

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

              private Stream<Integer> filterWithIfThenMapNullableContainerNullableOnSomeBranch(
                  Stream<NullableContainer<String>> stream) {
                return stream
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) {
                            if (container.get() != null) {
                              return true;
                            } else {
                              return perhaps();
                            }
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> c) {
                            // BUG: Diagnostic contains: dereferenced expression
                            return c.get().length();
                          }
                        });
              }

              private static boolean perhaps() {
                return Math.random() > 0.5;
              }

              private Stream<Integer> filterWithIfThenMapNullableContainerNullableOnSomeBranchAnyOrder(
                  Stream<NullableContainer<String>> stream) {
                return stream
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) {
                            if (container.get() == null) {
                              return perhaps();
                            } else {
                              return true;
                            }
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> c1) {
                            // BUG: Diagnostic contains: dereferenced expression
                            return c1.get().length();
                          }
                        });
              }

              private Stream<Integer> filterWithOrExpressionThenMapNullableContainer(
                  Stream<NullableContainer<String>> stream) {
                return stream
                    .filter(
                        new Predicate<NullableContainer<String>>() {
                          @Override
                          public boolean test(NullableContainer<String> container) {
                            return container.get() != null || perhaps();
                          }
                        })
                    .map(
                        new Function<NullableContainer<String>, Integer>() {
                          @Override
                          public Integer apply(NullableContainer<String> container) {
                            // BUG: Diagnostic contains: dereferenced expression
                            return container.get().length();
                          }
                        });
              }

              private Stream<Integer> filterThenMapNullableContainerLambdas(
                  Stream<NullableContainer<String>> stream) {
                // BUG: Diagnostic contains: dereferenced expression
                return stream.filter(c -> c.get() != null || perhaps()).map(c -> c.get().length());
              }

              private IntStream mapToInt(Stream<NullableContainer<String>> stream) {
                // BUG: Diagnostic contains: dereferenced expression
                return stream.mapToInt(c -> c.get().length());
              }

              private LongStream mapToLong(Stream<NullableContainer<String>> stream) {
                // BUG: Diagnostic contains: dereferenced expression
                return stream.mapToLong(c -> c.get().length());
              }

              private DoubleStream mapToDouble(Stream<NullableContainer<String>> stream) {
                // BUG: Diagnostic contains: dereferenced expression
                return stream.mapToDouble(c -> c.get().length());
              }

              private void forEach(Stream<NullableContainer<String>> stream) {
                // BUG: Diagnostic contains: dereferenced expression
                stream.forEach(s -> System.out.println(s.get().length()));
              }

              private void forEachOrdered(Stream<NullableContainer<String>> stream) {
                // BUG: Diagnostic contains: dereferenced expression
                stream.forEachOrdered(s -> System.out.println(s.get().length()));
              }

              // CustomStreamWithoutModel is NOT modeled in TestLibraryModels
              private CustomStreamWithoutModel<Integer> filterThenMapLambdasCustomStream(CustomStreamWithoutModel<String> stream) {
                // Safe because generic is String, not @Nullable String
                return stream.filter(s -> s != null).map(s -> s.length());
              }

              private CustomStreamWithoutModel<Integer> filterThenMapNullableContainerLambdasCustomStream(
                      CustomStreamWithoutModel<NullableContainer<String>> stream) {
                return stream
                        .filter(c -> c.get() != null)
                        // BUG: Diagnostic contains: dereferenced expression
                        .map(c -> c.get().length());
              }

              private CustomStreamWithoutModel<Integer> filterThenMapMethodRefsCustomStream(
                      CustomStreamWithoutModel<NullableContainer<String>> stream) {
                return stream
                        .filter(c -> c.get() != null && perhaps())
                        .map(NullableContainer::get) // CSWoM<NullableContainer<String>> -> CSWoM<@Nullable String>
                        .map(String::length); // Should be an error with proper generics support!
              }

              private static class CheckNonfinalBeforeStream<T> {
                @Nullable private T ref;

                public CheckNonfinalBeforeStream(@Nullable T ref) {
                  this.ref = ref;
                }

                private Stream<T> test1(Stream<T> stream) {
                  Preconditions.checkNotNull(ref);
                  final T asLocal = ref;
                  return stream.filter(s -> asLocal.equals(s));
                }

                private Stream<T> test2(Stream<T> stream) {
                  Preconditions.checkNotNull(ref);
                  // no error since we propagate nullability facts to stream callbacks, which
                  // in sane code are invoked soon after the stream is created
                  return stream.filter(s -> ref.equals(s));
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void streamSupportCollectorsToMap() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.*;
            import java.util.stream.*;
            import java.util.function.Function;
            import javax.annotation.Nullable;
            class Test {
                static class Foo {
                  @Nullable String bar;
                  String baz = "baz";
                }
                Map<Integer, String> testNegative1() {
                  List<Foo> foos = new ArrayList<>();
                  return foos.stream()
                      .filter(foo -> foo.bar != null)
                      .collect(Collectors.toMap(foo -> foo.bar.length(), foo -> foo.baz));
                }
                Map<String, Integer> testNegative2() {
                  List<Foo> foos = new ArrayList<>();
                  return foos.stream()
                      .filter(foo -> foo.bar != null)
                      .collect(Collectors.toMap(foo -> foo.baz, foo -> foo.bar.length()));
                }
                Map<Integer, String> testNegative3() {
                  List<Foo> foos = new ArrayList<>();
                  return foos.stream()
                      .filter(foo -> foo.bar != null)
                      .collect(Collectors.toMap(
                        new Function<Foo,Integer>() { public Integer apply(Foo foo) { return foo.bar.length(); } },
                        foo -> foo.baz));
                }
                Map<Integer, String> testPositive1() {
                  List<Foo> foos = new ArrayList<>();
                  return foos.stream()
                      // BUG: Diagnostic contains: dereferenced expression 'foo.bar' is @Nullable
                      .collect(Collectors.toMap(foo -> foo.bar.length(), foo -> foo.baz));
                }
                Map<Integer, String> testPositive2() {
                  List<Foo> foos = new ArrayList<>();
                  return foos.stream()
                      .filter(foo -> foo.baz != null)
                      .collect(Collectors.toMap(
                        // BUG: Diagnostic contains: dereferenced expression 'foo.bar' is @Nullable
                        new Function<Foo,Integer>() { public Integer apply(Foo foo) { return foo.bar.length(); } },
                        foo -> foo.baz));
                }
            }
            """)
        .doTest();
  }

  @Test
  public void streamSupportCollectorsGroupingBy() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.*;
            import java.util.stream.*;
            import java.util.function.Function;
            import javax.annotation.Nullable;
            class Test {
                static class Foo {
                  @Nullable String bar;
                  String baz = "baz";
                }
                Map<Integer, List<Foo>> testNegative() {
                  List<Foo> foos = new ArrayList<>();
                  return foos.stream()
                      .filter(foo -> foo.bar != null)
                      .collect(Collectors.groupingBy(foo -> foo.bar.length()));
                }
                Map<Integer, List<Foo>> testPositive() {
                  List<Foo> foos = new ArrayList<>();
                  return foos.stream()
                      // BUG: Diagnostic contains: dereferenced expression 'foo.bar' is @Nullable
                      .collect(Collectors.groupingBy(foo -> foo.bar.length()));
                }
            }
            """)
        .doTest();
  }

  @Test
  public void streamSupportCollectToImmutableMap() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.google.common.collect.ImmutableMap;
            import java.util.*;
            import java.util.stream.*;
            import java.util.function.Function;
            import javax.annotation.Nullable;
            class Test {
                static class Foo {
                  @Nullable String bar;
                  String baz = "baz";
                }
                Map<Integer, String> testNegative() {
                  List<Foo> foos = new ArrayList<>();
                  return foos.stream()
                      .filter(foo -> foo.bar != null)
                      .collect(ImmutableMap.toImmutableMap(foo -> foo.bar.length(), foo -> foo.baz));
                }
                Map<Integer, String> testPositive() {
                  List<Foo> foos = new ArrayList<>();
                  return foos.stream()
                      // BUG: Diagnostic contains: dereferenced expression 'foo.bar' is @Nullable
                      .collect(ImmutableMap.toImmutableMap(foo -> foo.bar.length(), foo -> foo.baz));
                }
            }
            """)
        .doTest();
  }

  @Test
  public void supportObjectsIsNull() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Objects;
            import javax.annotation.Nullable;
            class Test {
              private void foo(@Nullable String s) {
                if (!Objects.isNull(s)) {
                  s.toString();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testJDKPathGetParentModel() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Optional;
            import java.nio.file.Files;
            import java.nio.file.Path;
            public class Test {
             Optional<Path> findConfig(Path searchDir) {
                Path configFile = searchDir.resolve("foo.yml");
                if (Files.exists(configFile)) {
                  return Optional.of(configFile);
                }
                // BUG: Diagnostic contains: passing @Nullable parameter 'searchDir.getParent()' where @NonNull
                return this.findConfig(searchDir.getParent());
             }
            }
            """)
        .doTest();
  }

  @Test
  public void defaultLibraryModelsObjectNonNull() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Objects;
            import javax.annotation.Nullable;
            public class Test {
              String foo(@Nullable Object o) {
                if (Objects.nonNull(o)) {
                 return o.toString();
                };
                return "";
              }
            }
            """)
        .doTest();
  }

  @Test
  public void defaultLibraryModelsClassIsInstance() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Objects;
            import javax.annotation.Nullable;
            public class Test {
              int classIsInstance(@Nullable String s) {
                if (CharSequence.class.isInstance(s)) {
                  return s.hashCode();
                } else {
                  // BUG: Diagnostic contains: dereferenced
                  return s.hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void interfaceLibraryModelMethodCall() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import com.uber.lib.unannotated.CustomInterface;
            public class Test {
              int interfaceMethodCall(CustomInterface c) {
                if (c.hasContent()) {
                  return c.getContent().hashCode();
                } else {
                  // BUG: Diagnostic contains: dereferenced
                  return c.getContent().hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void checkForNullSupport() {
    defaultCompilationHelper
        // This is just to check the behavior is the same between @Nullable and @CheckForNull
        .addSourceLines(
            "TestNullable.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class TestNullable {
              @Nullable
              Object nullable = new Object();
              public void setNullable(@Nullable Object nullable) {this.nullable = nullable;}
              // BUG: Diagnostic contains: dereferenced expression 'nullable' is @Nullable
              public void run() {System.out.println(nullable.toString());}
            }
            """)
        .addSourceLines(
            "TestCheckForNull.java",
            """
            package com.uber;
            import javax.annotation.CheckForNull;
            class TestCheckForNull {
              @CheckForNull
              Object checkForNull = new Object();
              public void setCheckForNull(@CheckForNull Object checkForNull) {this.checkForNull = checkForNull;}
              // BUG: Diagnostic contains: dereferenced expression 'checkForNull' is @Nullable
              public void run() {System.out.println(checkForNull.toString());}
            }
            """)
        .doTest();
  }

  @Test
  public void orElseLibraryModelSupport() {
    // Checks both Optional.orElse(...) support itself and the general nullImpliesNullParameters
    // Library Models mechanism for encoding @Contract(!null -> !null) as a library model.
    defaultCompilationHelper
        .addSourceLines(
            "TestOptionalOrElseNegative.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import java.util.Optional;
            class TestOptionalOrElseNegative {
              public Object foo(Optional<Object> o) {
                return o.orElse("Something");
              }
              public @Nullable Object bar(Optional<Object> o) {
                return o.orElse(null);
              }
            }
            """)
        .addSourceLines(
            "TestOptionalOrElsePositive.java",
            """
            package com.uber;
            import java.util.Optional;
            class TestOptionalOrElsePositive {
              public Object foo(Optional<Object> o) {
                // BUG: Diagnostic contains: returning @Nullable expression
                return o.orElse(null);
              }
              public void bar(Optional<Object> o) {
                // BUG: Diagnostic contains: dereferenced expression 'o.orElse(null)' is @Nullable
                System.out.println(o.orElse(null).toString());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overridingNativeModelsInAnnotatedCodeDoesNotPropagateTheModel() {
    // See https://github.com/uber/NullAway/issues/445
    defaultCompilationHelper
        .addSourceLines(
            "NonNullGetMessage.java",
            """
            package com.uber;
            import java.util.Objects;
            import javax.annotation.Nullable;
            class NonNullGetMessage extends RuntimeException {
              NonNullGetMessage(final String message) {
                 super(message);
              }
              @Override
              public String getMessage() {
                return Objects.requireNonNull(super.getMessage());
              }
              public static void foo(NonNullGetMessage e) {
                expectsNonNull(e.getMessage());
              }
              public static void expectsNonNull(String str) {
                System.out.println(str);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void overridingNativeModelsInAnnotatedCodeDoesNotGenerateSafetyHoles() {
    // See https://github.com/uber/NullAway/issues/445
    defaultCompilationHelper
        .addSourceLines(
            "NonNullGetMessage.java",
            """
            package com.uber;
            import java.util.Objects;
            import javax.annotation.Nullable;
            class NonNullGetMessage extends RuntimeException {
              NonNullGetMessage(@Nullable String message) {
                 super(message);
              }
              @Override
              public String getMessage() {
                // BUG: Diagnostic contains: returning @Nullable expression
                return super.getMessage();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void springAutowiredFieldTest() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.springframework.stereotype.Component;
            @Component
            public class Foo {
              @Nullable String bar;
              public void setBar(String s) {
                bar = s;
              }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service
            public class Test {
              @Autowired
              Foo f; // Initialized by spring.
              public void Fun() {
                f.setBar("hello");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void springValueFieldTest() {
    defaultCompilationHelper
        .addSourceLines(
            "Value.java",
            """
            package org.springframework.beans.factory.annotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Value {
              String value();
            }
            """)
        .addSourceLines(
            "NegativeCases.java",
            """
            package com.uber;
            import org.springframework.beans.factory.annotation.Value;
            import org.jspecify.annotations.Nullable;
            class NegativeCases {
              @Value("${app.name}")
              String propertyName;
              @Value("'literal'")
              String literalValue;
              @Nullable
              @Value("#{null}")
              String nullableSpelNullValue;
              void test() {
                propertyName.toString();
                literalValue.toString();
              }
            }
            class ValueFieldWithConstructor {
              private final String name;
              @Value("${app.description}")
              String description;
              ValueFieldWithConstructor(String name) {
                this.name = name;
              }
            }
            """)
        .addSourceLines(
            "PositiveCases.java",
            """
            package com.uber;
            import org.springframework.beans.factory.annotation.Value;
            class PositiveCases {
              @Value("#{null}")
              // BUG: Diagnostic contains: @NonNull field 'spelNullValue' not initialized
              String spelNullValue;
              @Value("${missing:#{null}}")
              // BUG: Diagnostic contains: @NonNull field 'placeholderWithNullDefault' not initialized
              String placeholderWithNullDefault;
            }
            """)
        .doTest();
  }

  /**
   * Adds source stubs for the Spring Boot test annotations that mark a field as initialized by the
   * Spring test context.
   *
   * @param helper the test helper to add the stubs to
   * @return the test helper, for chaining
   */
  private static DualModeCompilationTestHelper addSpringMockAnnotationStubs(
      DualModeCompilationTestHelper helper) {
    String bootPackage = "package org.springframework.boot.test.mock.mockito;";
    String overridePackage = "package org.springframework.test.context.bean.override.mockito;";
    return helper
        .addSourceLines(
            "MockBean.java",
            bootPackage,
            ANNOTATION_IMPORTS,
            TARGET_TYPE_FIELD,
            RETENTION_RUNTIME,
            "public @interface MockBean {}")
        .addSourceLines(
            "SpyBean.java",
            bootPackage,
            ANNOTATION_IMPORTS,
            TARGET_TYPE_FIELD,
            RETENTION_RUNTIME,
            "public @interface SpyBean {}")
        .addSourceLines(
            "MockitoBean.java",
            overridePackage,
            ANNOTATION_IMPORTS,
            TARGET_TYPE_FIELD,
            RETENTION_RUNTIME,
            "public @interface MockitoBean {}")
        .addSourceLines(
            "MockitoSpyBean.java",
            overridePackage,
            ANNOTATION_IMPORTS,
            TARGET_TYPE_FIELD,
            RETENTION_RUNTIME,
            "public @interface MockitoSpyBean {}");
  }

  @Test
  public void springTestAutowiredFieldTest() {
    addSpringMockAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.springframework.stereotype.Component;
            @Component
            public class Foo {
              @Nullable String bar;
              public void setBar(String s) {
                bar = s;
              }
            }
            """)
        .addSourceLines(
            "TestCase.java",
            """
            package com.uber;
            import org.junit.jupiter.api.Test;
            import org.springframework.boot.test.mock.mockito.SpyBean;
            import org.springframework.boot.test.mock.mockito.MockBean;
            import org.springframework.test.context.bean.override.mockito.MockitoBean;
            import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
            public class TestCase {
              @MockitoSpyBean
              private Foo sf62Spy; // Initialized by spring test (via Mockito).
              @MockitoBean
              private Foo sf62Mock; // Initialized by spring test (via Mockito).
              @SpyBean
              private Foo spy; // Initialized by spring test (via Mockito).
              @MockBean
              private Foo mock; // Initialized by spring test (via Mockito).
              @Test
              void springTest() {
                spy.setBar("hello");
                mock.setBar("hello");
                sf62Spy.setBar("hello");
                sf62Mock.setBar("hello");
              }
            }
            """)
        .doTest();
  }

  /**
   * Adds source stubs for the Mockito annotations that mark a field as initialized by Mockito.
   *
   * @param helper the test helper to add the stubs to
   * @return the test helper, for chaining
   */
  private static DualModeCompilationTestHelper addMockitoAnnotationStubs(
      DualModeCompilationTestHelper helper) {
    String mockitoPackage = "package org.mockito;";
    return helper
        .addSourceLines(
            "Captor.java",
            mockitoPackage,
            ANNOTATION_IMPORTS,
            TARGET_FIELD_PARAMETER,
            RETENTION_RUNTIME,
            "public @interface Captor {}")
        .addSourceLines(
            "InjectMocks.java",
            mockitoPackage,
            ANNOTATION_IMPORTS,
            TARGET_FIELD,
            RETENTION_RUNTIME,
            "public @interface InjectMocks {}")
        .addSourceLines(
            "Mock.java",
            mockitoPackage,
            ANNOTATION_IMPORTS,
            TARGET_FIELD_PARAMETER,
            RETENTION_RUNTIME,
            "public @interface Mock {}")
        .addSourceLines(
            "Spy.java",
            mockitoPackage,
            ANNOTATION_IMPORTS,
            TARGET_FIELD,
            RETENTION_RUNTIME,
            "public @interface Spy {}");
  }

  @Test
  public void mockitoAnnotationsOnFieldTest() {
    addMockitoAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "ArticleManager.java",
            // language=java
            """
            package com.uber;

            public class ArticleManager {
              private ArticleCalculator articleCalculator;
              private ArticleDatabase articleDatabase;
              public ArticleManager(ArticleCalculator articleCalculator, ArticleDatabase articleDatabase) {
                  this.articleCalculator = articleCalculator;
                  this.articleDatabase = articleDatabase;
              }
            }
            """)
        .addSourceLines(
            "ArticleCalculator.java",
            // language=java
            """
            package com.uber;

            public class ArticleCalculator { }
            """)
        .addSourceLines(
            "ArticleDatabase.java",
            // language=java
            """
            package com.uber;

            public class ArticleDatabase { }
            """)
        .addSourceLines(
            "TestCase.java",
            // language=java
            """
            package com.uber;

            import org.junit.jupiter.api.Test;
            import org.mockito.ArgumentCaptor;
            import org.mockito.Captor;
            import org.mockito.InjectMocks;
            import org.mockito.Mock;
            import org.mockito.Spy;

            class TestCase {
              @Captor
              private ArgumentCaptor<String> captor; // Initialized by mockito
              @Mock
              private ArticleCalculator articleCalculator; // Initialized by mockito
              @Spy
              private ArticleDatabase articleDatabase; // Initialized by mockito
              @InjectMocks
              private ArticleManager articleManager; // Initialized by mockito
            }
            """)
        .doTest();
  }

  @Test
  public void wireMockInjectFieldTest() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "InjectWireMock.java",
            "package org.wiremock.spring;",
            ANNOTATION_IMPORTS,
            TARGET_FIELD_PARAMETER,
            RETENTION_RUNTIME,
            "public @interface InjectWireMock {}")
        .addSourceLines(
            "TestCase.java",
            """
            package com.uber;
            import org.wiremock.spring.InjectWireMock;
            public class TestCase {
              @InjectWireMock
              Object wireMock; // Initialized by WireMock extension.
              void test() {
                wireMock.toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void junitTempDir() {
    defaultCompilationHelper
        .addSourceLines(
            "TestCase.java",
            """
            package com.uber;
            import java.io.File;
            import java.nio.file.Path;
            import org.junit.jupiter.api.BeforeAll;
            import org.junit.jupiter.api.Test;
            import org.junit.jupiter.api.io.TempDir;
            public class TestCase {
              @TempDir
              static Path staticTempDir;
              @TempDir
              File instanceTempDir;
              @BeforeAll
              static void staticTest() {
                staticTempDir.toFile();
              }
              @Test
              void instanceTest() {
                instanceTempDir.exists();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void assertJInjectSoftAssertionsTest() {
    defaultCompilationHelper
        .addSourceLines(
            "TestCase.java",
            """
            package com.uber;
            import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
            import org.assertj.core.api.SoftAssertions;
            import org.junit.jupiter.api.Test;
            public class TestCase {
              @InjectSoftAssertions
              SoftAssertions softAssertions;
              @Test
              void testWithSoftAssertions() {
                softAssertions.assertThat(true).isTrue();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void springAutowiredConstructorTest() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import org.springframework.stereotype.Component;
            @Component
            public class Foo {
              @Nullable String bar;
              public void setBar(String s) {
                bar = s;
              }
            }
            """)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.springframework.beans.factory.annotation.Autowired;
            import org.springframework.stereotype.Service;
            @Service
            public class Test {
              Foo f; // Initialized by spring.
              @Autowired
              public void init() {
                 f = new Foo();
              }
              public void Fun() {
                f.setBar("hello");
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testLombokBuilderWithGeneratedAsUnannotated() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:TreatGeneratedAsUnannotated=true"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import com.uber.lombok.LombokDTO;
            class Test {
              void testSetters(LombokDTO ldto) {
                 ldto.setNullableField(null);
                 // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                 ldto.setField(null);
              }
              String testGetterSafe(LombokDTO ldto) {
                 return ldto.getField();
              }
              String testGetterNullable(LombokDTO ldto) {
                 // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                 return ldto.getNullableField();
              }
              LombokDTO testBuilderSafe(@Nullable String s1, String s2) {
                 // Safe, because s2 is non-null and nullableField can take @Nullable
                 return LombokDTO.builder().nullableField(s1).field(s2).build();
              }
              LombokDTO testBuilderUnsafe(@Nullable String s1, @Nullable String s2) {
                 // No error, because the code of LombokDTO.Builder is @Generated and we are
                 // building with TreatGeneratedAsUnannotated=true
                 return LombokDTO.builder().nullableField(s1).field(s2).build();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void testLombokBuilderWithoutGeneratedAsUnannotated() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import com.uber.lombok.LombokDTO;
            class Test {
              void testSetters(LombokDTO ldto) {
                 ldto.setNullableField(null);
                 // BUG: Diagnostic contains: passing @Nullable parameter 'null' where @NonNull is required
                 ldto.setField(null);
              }
              String testGetterSafe(LombokDTO ldto) {
                 return ldto.getField();
              }
              String testGetterNullable(LombokDTO ldto) {
                 // BUG: Diagnostic contains: returning @Nullable expression from method with @NonNull return type
                 return ldto.getNullableField();
              }
              LombokDTO testBuilderSafe(@Nullable String s1, String s2) {
                 // Safe, because s2 is non-null and nullableField can take @Nullable
                 return LombokDTO.builder().nullableField(s1).field(s2).build();
              }
              LombokDTO testBuilderUnsafe(@Nullable String s1, @Nullable String s2) {
                 // BUG: Diagnostic contains: passing @Nullable parameter 's2' where @NonNull is required
                 return LombokDTO.builder().nullableField(s1).field(s2).build();
              }
            }
            """)
        .doTest();
  }

  /**
   * This test is solely to check if we can run through some of the {@link
   * com.uber.nullaway.handlers.LombokHandler} logic without crashing. It does not check that the
   * logic is correct.
   */
  @Test
  public void lombokHandlerRunsWithoutCrashing() {
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            class Test {
              @Nullable Object test;
              @lombok.Generated
              Object $default$test() {
                return new Object();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void systemConsoleNullable() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            class Test {
              void foo() {
                 // BUG: Diagnostic contains: dereferenced expression 'System.console()' is @Nullable
                System.console().toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void defaultLibraryModelsMapRemove() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
          package com.uber;
          import java.util.Map;
          class Test {
            void testMapRemove(Map<String, Object> map) {
              // BUG: Diagnostic contains: dereferenced expression 'map.remove("key")' is @Nullable
              map.remove("key").toString();
            }
          }
          """)
        .doTest();
  }

  @Test
  public void mapGetOrDefault() {
    String[] sourceLines =
        new String[] {
          "package com.uber;",
          "import java.util.HashMap;",
          "import java.util.Map;",
          "import com.google.common.collect.ImmutableMap;",
          "import org.jspecify.annotations.Nullable;",
          "class Test {",
          "  void testGetOrDefaultMap(Map<String, String> m, String nonNullString, @Nullable String nullableString) {",
          "    m.getOrDefault(\"key\", \"value\").toString();",
          "    m.getOrDefault(\"key\", nonNullString).toString();",
          "    // BUG: Diagnostic contains: dereferenced",
          "    m.getOrDefault(\"key\", null).toString();",
          "    // BUG: Diagnostic contains: dereferenced",
          "    m.getOrDefault(\"key\", nullableString).toString();",
          "  }",
          "  void testGetOrDefaultHashMap(HashMap<String, String> m, String nonNullString, @Nullable String nullableString) {",
          "    m.getOrDefault(\"key\", \"value\").toString();",
          "    m.getOrDefault(\"key\", nonNullString).toString();",
          "    // BUG: Diagnostic contains: dereferenced",
          "    m.getOrDefault(\"key\", null).toString();",
          "    // BUG: Diagnostic contains: dereferenced",
          "    m.getOrDefault(\"key\", nullableString).toString();",
          "  }",
          "  void testGetOrDefaultImmutableMap(ImmutableMap<String, String> im, String nonNullString, @Nullable String nullableString) {",
          "    im.getOrDefault(\"key\", \"value\").toString();",
          "    im.getOrDefault(\"key\", nonNullString).toString();",
          "    // BUG: Diagnostic contains: dereferenced",
          "    im.getOrDefault(\"key\", null).toString();",
          "    // BUG: Diagnostic contains: dereferenced",
          "    im.getOrDefault(\"key\", nullableString).toString();",
          "  }",
          "}"
        };
    // test *without* restrictive annotations enabled
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber"))
        .addSourceLines("Test.java", sourceLines)
        .doTest();
    // test *with* restrictive annotations enabled
    makeTestHelperWithArgs(
            Arrays.asList(
                "-d",
                temporaryFolder.getRoot().getAbsolutePath(),
                "-XepOpt:NullAway:AnnotatedPackages=com.uber",
                "-XepOpt:NullAway:AcknowledgeRestrictiveAnnotations=true"))
        .addSourceLines("Test.java", sourceLines)
        .doTest();
  }

  @Test
  public void defaultLibraryModelsClassCast() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import org.jspecify.annotations.Nullable;
            class Test {
              void castNullable(@Nullable String s) {
                // BUG: Diagnostic contains: dereferenced
                CharSequence.class.cast(s).hashCode();
              }
              void castNonnull(String s1, @Nullable String s2) {
                CharSequence.class.cast(s1).hashCode();
                if (s2 instanceof CharSequence) {
                  CharSequence.class.cast(s2).hashCode();
                }
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateNotNull() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String s) {
                Validate.notNull(s);
                int l = s.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateNotNullWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String s) {
                Validate.notNull(s, "Message");
                int l = s.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateArrayNotEmptyWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String[] s) {
                Validate.notEmpty(s, "Message");
                int l = s.length;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateArrayNotEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String[] s) {
                Validate.notEmpty(s);
                int l = s.length;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateListNotEmptyWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            import java.util.List;
            public class Foo {
              public void bar(@Nullable List<String> s) {
                Validate.notEmpty(s, "Message");
                int l = s.size();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateListNotEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            import java.util.List;
            public class Foo {
              public void bar(@Nullable List<String> s) {
                Validate.notEmpty(s);
                int l = s.size();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateMapNotEmptyWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            import java.util.Map;
            public class Foo {
              public void bar(@Nullable Map<String, String> s) {
                Validate.notEmpty(s, "Message");
                int l = s.size();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateMapNotEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            import java.util.Map;
            public class Foo {
              public void bar(@Nullable Map<String, String> s) {
                Validate.notEmpty(s);
                int l = s.size();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateStringNotEmptyWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String s) {
                Validate.notEmpty(s, "Message");
                int l = s.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateStringNotEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String s) {
                Validate.notEmpty(s);
                int l = s.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateStringNotBlankWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String s) {
                Validate.notBlank(s, "Message");
                int l = s.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateStringNotBlank() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String s) {
                Validate.notBlank(s);
                int l = s.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateArrayNoNullElementsWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String[] s) {
                Validate.noNullElements(s, "Message");
                int l = s.length;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateArrayNoNullElements() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String[] s) {
                Validate.noNullElements(s);
                int l = s.length;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateIterableNoNullElementsWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            import java.util.Iterator;
            public class Foo {
              public void bar(@Nullable Iterable<String> s) {
                Validate.noNullElements(s, "Message");
                Iterator<String> l = s.iterator();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateIterableNoNullElements() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            import java.util.Iterator;
            public class Foo {
              public void bar(@Nullable Iterable<String> s) {
                Validate.noNullElements(s);
                Iterator<String> l = s.iterator();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateArrayValidIndexWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String[] s) {
                Validate.validIndex(s, 0, "Message");
                int l = s.length;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateArrayValidIndex() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String[] s) {
                Validate.validIndex(s, 0);
                int l = s.length;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateCollectionValidIndexWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            import java.util.List;
            public class Foo {
              public void bar(@Nullable List<String> s) {
                Validate.validIndex(s, 0, "Message");
                int l = s.size();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateCollectionValidIndex() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            import java.util.List;
            public class Foo {
              public void bar(@Nullable List<String> s) {
                Validate.validIndex(s, 0);
                int l = s.size();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateStringValidIndexWithMessage() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String s) {
                Validate.validIndex(s, 0, "Message");
                int l = s.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheValidateStringValidIndex() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.lang3.Validate;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public void bar(@Nullable String s) {
                Validate.validIndex(s, 0);
                int l = s.length();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheCollectionsCollectionUtilsIsNotEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.collections.CollectionUtils;
            import org.jetbrains.annotations.Nullable;
            import java.util.List;
            public class Foo {
              public void bar(@Nullable List<String> s) {
                if(CollectionUtils.isNotEmpty(s))
                  s.get(0);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheCollections4CollectionUtilsIsNotEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.collections4.CollectionUtils;
            import org.jetbrains.annotations.Nullable;
            import java.util.List;
            public class Foo {
              public void bar(@Nullable List<String> s) {
                if(CollectionUtils.isNotEmpty(s))
                  s.get(0);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheCollectionsCollectionUtilsIsEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.collections.CollectionUtils;
            import org.jetbrains.annotations.Nullable;
            import java.util.List;
            public class Foo {
              public void bar(@Nullable List<String> s) {
                if(CollectionUtils.isEmpty(s)) {
                  return;
                }
                s.get(0);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void apacheCollections4CollectionUtilsIsEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import org.apache.commons.collections4.CollectionUtils;
            import org.jetbrains.annotations.Nullable;
            import java.util.List;
            public class Foo {
              public void bar(@Nullable List<String> s) {
                if(CollectionUtils.isEmpty(s)) {
                  return;
                }
                s.get(0);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void amazonAwsUtilCollectionUtilsIsNullOrEmpty() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import software.amazon.awssdk.utils.CollectionUtils;
            import org.jetbrains.annotations.Nullable;
            import java.util.List;
            public class Foo {
              public void bar(@Nullable List<String> s) {
                if(CollectionUtils.isNullOrEmpty(s)) {
                  return;
                }
                s.get(0);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void amazonAwsStringUtilsIsEmptyOrIsBlank() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import software.amazon.awssdk.utils.StringUtils;
            import org.jetbrains.annotations.Nullable;
            import java.util.List;
            public class Foo {
              public void bar(@Nullable String s) {
                if(StringUtils.isEmpty(s)) {
                  return;
                }
                s.hashCode();
              }
              public void baz(@Nullable String s) {
                if(StringUtils.isBlank(s)) {
                  return;
                }
                s.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void defaultLibraryModelsObjectToString() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import java.util.Objects;
            import javax.annotation.Nullable;
            public class Test {
              void objectsToString(@Nullable Object o) {
                String p = Objects.toString(o, "foo");
                String n = Objects.toString(o, null);
                p.hashCode();
                // BUG: Diagnostic contains: dereferenced
                n.hashCode();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void filesIsDirectory() {
    defaultCompilationHelper
        .addSourceLines(
            "Foo.java",
            """
            package com.uber;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import org.jetbrains.annotations.Nullable;
            public class Foo {
              public boolean bar(@Nullable Path p) {
                // BUG: Diagnostic contains: passing @Nullable parameter 'p' where @NonNull is required
                return Files.isDirectory(p);
              }
            }
            """)
        .doTest();
  }

  @Test
  public void reactorFluxFilterThenMap() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import reactor.core.publisher.Flux;
            class Test {
              static class Foo {
                @Nullable String bar;
              }
              static class NullableContainer<T> {
                @Nullable private T ref;
                public NullableContainer() { ref = null; }
                @Nullable public T get() { return ref; }
              }
              void testNegativeField(Flux<Foo> flux) {
                flux.filter(foo -> foo.bar != null).map(foo -> foo.bar.length());
              }
              void testNegativeMethodReturn(Flux<NullableContainer<String>> flux) {
                flux.filter(c -> c.get() != null).map(c -> c.get().length());
              }
              void testPositiveField(Flux<Foo> flux) {
                // BUG: Diagnostic contains: dereferenced expression 'foo.bar' is @Nullable
                flux.map(foo -> foo.bar.length());
              }
              void testPositiveMethodReturn(Flux<NullableContainer<String>> flux) {
                // BUG: Diagnostic contains: dereferenced expression
                flux.map(c -> c.get().length());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void reactorFluxFilterPassthroughThenMap() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import reactor.core.publisher.Flux;
            class Test {
              static class Foo {
                @Nullable String bar;
              }
              void testDistinct(Flux<Foo> flux) {
                flux.filter(foo -> foo.bar != null).distinct().map(foo -> foo.bar.length());
              }
              void testDistinctUntilChanged(Flux<Foo> flux) {
                flux.filter(foo -> foo.bar != null).distinctUntilChanged().map(foo -> foo.bar.length());
              }
              void testTake(Flux<Foo> flux) {
                flux.filter(foo -> foo.bar != null).take(10).map(foo -> foo.bar.length());
              }
              void testSkip(Flux<Foo> flux) {
                flux.filter(foo -> foo.bar != null).skip(1).map(foo -> foo.bar.length());
              }
            }
            """)
        .doTest();
  }

  @Test
  public void reactorFluxFilterDoOnNextThenMap() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import reactor.core.publisher.Flux;
            class Test {
              static class Foo {
                @Nullable String bar;
              }
              void testNegative(Flux<Foo> flux) {
                flux
                    .filter(foo -> foo.bar != null)
                    .doOnNext(foo -> { if (foo.bar.length() == 0) throw new RuntimeException(); })
                    .map(foo -> foo.bar.length());
              }
              void testPositive(Flux<Foo> flux) {
                flux.doOnNext(foo -> {
                  // BUG: Diagnostic contains: dereferenced expression 'foo.bar' is @Nullable
                  System.out.println(foo.bar.length());
                });
              }
            }
            """)
        .doTest();
  }

  @Test
  public void reactorFluxFilterThenFlatMap() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import reactor.core.publisher.Flux;
            class Test {
              static class Foo {
                @Nullable String bar;
              }
              void testFlatMap(Flux<Foo> flux) {
                flux.filter(foo -> foo.bar != null).flatMap(foo -> Flux.just(foo.bar.length()));
              }
              void testConcatMap(Flux<Foo> flux) {
                flux.filter(foo -> foo.bar != null).concatMap(foo -> Flux.just(foo.bar.length()));
              }
            }
            """)
        .doTest();
  }

  @Test
  public void reactorFluxFilterOrConditionNoNullSafety() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import reactor.core.publisher.Flux;
            class Test {
              static class Foo {
                @Nullable String bar;
              }
              private static boolean perhaps() { return Math.random() > 0.5; }
              void testPositive(Flux<Foo> flux) {
                flux
                    .filter(foo -> foo.bar != null || perhaps())
                    .map(foo -> {
                      // BUG: Diagnostic contains: dereferenced expression 'foo.bar' is @Nullable
                      return foo.bar.length();
                    });
              }
            }
            """)
        .doTest();
  }

  @Test
  public void reactorMonoFilterThenMap() {
    defaultCompilationHelper
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import javax.annotation.Nullable;
            import reactor.core.publisher.Mono;
            class Test {
              static class Foo {
                @Nullable String bar;
              }
              void testNegative(Mono<Foo> mono) {
                mono.filter(foo -> foo.bar != null).map(foo -> foo.bar.length());
              }
              void testNegativeFlatMap(Mono<Foo> mono) {
                mono.filter(foo -> foo.bar != null).flatMap(foo -> Mono.just(foo.bar.length()));
              }
              void testPositiveMapWithoutFilter(Mono<Foo> mono) {
                // BUG: Diagnostic contains: dereferenced expression 'foo.bar' is @Nullable
                mono.map(foo -> foo.bar.length());
              }
              void testPositiveDoOnNextWithoutFilter(Mono<Foo> mono) {
                mono.doOnNext(foo -> {
                  // BUG: Diagnostic contains: dereferenced expression 'foo.bar' is @Nullable
                  System.out.println(foo.bar.length());
                });
              }
            }
            """)
        .doTest();
  }
}
