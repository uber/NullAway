/*
 * Copyright (c) 2017 Uber Technologies, Inc.
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
