package com.uber.nullaway;

/** Test data sources shared by multiple test suites. */
public final class NullAwayTestDataConstants {

  /** Source for {@code com.uber.nullaway.testdata.Util}, the default CastToNonNullMethod. */
  public static final String UTIL_SOURCE =
      """
      package com.uber.nullaway.testdata;

      import javax.annotation.Nullable;

      public class Util {

        public static <T> T castToNonNull(@Nullable T x) {
          if (x == null) {
            throw new RuntimeException();
          }
          return x;
        }

        public static <T> T castToNonNull(@Nullable T x, String msg) {
          if (x == null) {
            throw new RuntimeException(msg);
          }
          return x;
        }

        public static <T> T castToNonNull(String msg, @Nullable T x, int counter) {
          // counter is needed to distinguish this method from the previous one when T == String
          if (x == null) {
            throw new RuntimeException(msg);
          }
          return x;
        }

        public static <T> T id(T x) {
          return x;
        }
      }
      """;

  private NullAwayTestDataConstants() {}
}
