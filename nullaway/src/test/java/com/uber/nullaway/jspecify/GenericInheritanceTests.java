package com.uber.nullaway.jspecify;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import java.util.Arrays;
import org.junit.Ignore;
import org.junit.Test;

public class GenericInheritanceTests extends NullAwayTestsBase {

  @Test
  public void nullableTypeArgInExtends() {
    makeHelper()
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Foo {",
            "  interface Supplier<T extends @Nullable Object> {",
            "    T get();",
            "  }",
            "  interface SubSupplier<T2 extends @Nullable Object> extends Supplier<@Nullable T2> {}",
            "  static void helper(SubSupplier<Foo> sup) {",
            "  }",
            "  static void main() {",
            "    helper(() -> null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nullableTypeArgInExtendsMulti() {
    makeHelper()
        .addSourceLines(
            "Example.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Example {",
            "  /** Declaring interface with *two* type parameters. */",
            "  interface BiSupplier<",
            "      K extends @Nullable Object,",
            "      V extends @Nullable Object> {",
            "    K key();",
            "  }",
            "",
            "  /**",
            "   * Sub‑interface swaps the order of the type variables *and*",
            "   * annotates only the first one with {@code @Nullable}.",
            "   *",
            "   *   - Its first actual argument is {@code @Nullable V2}",
            "   *   - Its second actual argument is {@code K2}",
            "   */",
            "  interface FlippedSupplier<",
            "      K2 extends @Nullable Object,",
            "      V2 extends @Nullable Object>",
            "      extends BiSupplier<@Nullable V2, K2> {}",
            "  static void helper(FlippedSupplier<String, String> sup) {}",
            "  static void main() {",
            "    helper(() -> null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void multiLevel() {
    makeHelper()
        .addSourceLines(
            "Chain.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Chain {",
            "  interface Base<T extends @Nullable Object> {",
            "    T get();",
            "  }",
            "  interface Level1<U extends @Nullable Object>",
            "      extends Base<U> {}",
            "  interface Level2<V extends @Nullable Object>",
            "      extends Level1<V> {}",
            "  interface Level3<W extends @Nullable Object>",
            "      extends Level2<@Nullable W> {}",
            "  static void helperNegative(Level3<Foo> sup) {}",
            "  static void helperPositive(Level2<Foo> sup) {}",
            "  static final class Foo {}",
            "  public static void main(String[] args) {",
            "    helperNegative(() -> null);",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    helperPositive(() -> null);",
            "  }",
            "}")
        .doTest();
  }

  @Ignore("https://github.com/uber/NullAway/issues/1212")
  @Test
  public void withClasses() {
    makeHelper()
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Foo {",
            "  interface Supplier<T extends @Nullable Object> {",
            "    T get();",
            "  }",
            "  static class SupplierImpl<T2 extends @Nullable Object> implements Supplier<T2> {",
            "    Supplier<T2> impl;",
            "    SupplierImpl(Supplier<T2> delegate) {",
            "      impl = delegate;",
            "    }",
            "    @Override",
            "    public T2 get() {",
            "      return impl.get();",
            "    }",
            "  }",
            "  static class ConcreteImpl extends SupplierImpl<@Nullable Foo> {",
            "    ConcreteImpl(Supplier<@Nullable Foo> delegate) {",
            "      super(delegate);",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void nonnullInExtends() {
    makeHelper()
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "import org.jspecify.annotations.NonNull;",
            "",
            "@NullMarked",
            "class Foo {",
            "  interface Supplier<T extends @Nullable Object> {",
            "    T get();",
            "  }",
            "",
            "  interface SubSupplier<T2 extends @Nullable Object> extends Supplier<@NonNull T2> {}",
            "",
            "  static void helper(SubSupplier<@Nullable Foo> sup) {}",
            "",
            "  static void main() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    helper(() -> null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void annotOnTypeVarStillWins() {
    makeHelper()
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "import org.jspecify.annotations.NonNull;",
            "",
            "@NullMarked",
            "class Foo {",
            "  interface Supplier<T extends @Nullable Object> {",
            "    @NonNull T get();",
            "  }",
            "",
            "  interface SubSupplier<T2 extends @Nullable Object> extends Supplier<@Nullable T2> {}",
            "",
            "  static void helper(SubSupplier<Foo> sup) {}",
            "",
            "  static void main() {",
            "    // BUG: Diagnostic contains: returning @Nullable expression",
            "    helper(() -> null);",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void methodInvocationTypeVarReceiver() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.*;",
            "@NullMarked",
            "public class Test {",
            "  public static abstract class AbstractLinkedDeque<E> {",
            "    public abstract @Nullable E getPrevious(E e);",
            "  }",
            "  public static final class WriteOrderDeque<",
            "          E extends WriteOrderDeque.WriteOrder<E>>",
            "      extends AbstractLinkedDeque<E> {",
            "    @Override",
            "    public @Nullable E getPrevious(E e) {",
            "      return e.getPreviousInWriteOrder();",
            "    }",
            "    public interface WriteOrder<T extends WriteOrder<T>> {",
            "      @Nullable T getPreviousInWriteOrder();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  /** For issue 1264. Checks that we don't crash when overriding with raw types. */
  @Test
  public void overrideWithRawTypes() {
    makeHelper()
        .addSourceLines(
            "Test.java",
            "import org.jspecify.annotations.NullMarked;",
            "@NullMarked",
            "public class Test {",
            "  static class A<T> {}",
            "  static class B<T> {",
            "    public <X> X accept(A<X> a) {",
            "      throw new RuntimeException();",
            "    }",
            "  }",
            "  static class Raw extends B {",
            "    @Override",
            "    public Object accept(A a) {",
            "      throw new RuntimeException();",
            "    }",
            "  }",
            "}")
        .doTest();
  }

  @Test
  public void interfaceInheritance() {
    makeHelper()
        .addSourceLines(
            "Foo.java",
            "import org.jspecify.annotations.NullMarked;",
            "import org.jspecify.annotations.Nullable;",
            "@NullMarked",
            "class Foo {",
            "  static interface Base<T extends @Nullable Object> {}",
            "  static interface Sub<T> extends Base<@Nullable T> {}",
            "",
            "  static void foo(Base<@Nullable Object> arg) {}",
            "  static void bar(Sub<Object> arg) {",
            "    foo(arg);",
            "  }",
            "}")
        .doTest();
  }

  private CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        Arrays.asList(
            "-XepOpt:NullAway:AnnotatedPackages=com.uber", "-XepOpt:NullAway:JSpecifyMode=true"));
  }
}
