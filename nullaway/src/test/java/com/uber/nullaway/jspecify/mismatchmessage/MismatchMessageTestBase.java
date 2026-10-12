package com.uber.nullaway.jspecify.mismatchmessage;

import com.google.errorprone.CompilationTestHelper;
import com.uber.nullaway.NullAwayTestsBase;
import com.uber.nullaway.generics.JSpecifyJavacConfig;
import java.util.List;
import java.util.function.Predicate;

/**
 * Compiles a {@code @NullMarked} class in JSpecify mode and compares the whole text of the generic
 * nullability diagnostic reported at one line of it.
 *
 * <p>The comparison is exact, so an expected message also states which lines the diagnostic does
 * not carry, such as a {@code did you mean} line or a {@code consider changing the required type}
 * line. A failure prints the expected message beside every diagnostic the compilation reported.
 */
abstract class MismatchMessageTestBase extends NullAwayTestsBase {

  /** Returns a helper that runs NullAway in JSpecify mode over {@code @NullMarked} code only. */
  CompilationTestHelper makeHelper() {
    return makeTestHelperWithArgs(
        JSpecifyJavacConfig.withJSpecifyModeArgs(List.of("-XepOpt:NullAway:OnlyNullMarked=true")));
  }

  /**
   * Compiles {@code members} as the body of {@code @NullMarked class Test} and expects the line
   * after {@code // BUG: Diagnostic matches: MESSAGE} to report exactly {@code expected}.
   *
   * <p>The class imports {@code java.util.*} and the JSpecify annotations {@code NonNull}, {@code
   * NullMarked}, {@code NullUnmarked}, and {@code Nullable}. Every other line of {@code members}
   * must compile without a NullAway diagnostic.
   *
   * @param expected the message without the {@code [NullAway]} prefix and the link line, ending in
   *     a line break
   * @param members the declarations of {@code class Test}, one line of which carries the marker
   */
  void assertMessage(String expected, String members) {
    makeHelper()
        .expectErrorMessage("MESSAGE", messageIs(expected))
        .addSourceLines("Test.java", nullMarkedClass(members))
        .doTest();
  }

  /**
   * Returns the source of {@code @NullMarked class Test} with the given body and the imports {@link
   * #assertMessage} documents.
   */
  static String nullMarkedClass(String members) {
    return """
        import java.util.*;
        import org.jspecify.annotations.NonNull;
        import org.jspecify.annotations.NullMarked;
        import org.jspecify.annotations.NullUnmarked;
        import org.jspecify.annotations.Nullable;
        @NullMarked
        class Test {
        %s}
        """
        .formatted(members);
  }

  /**
   * Returns a matcher for a diagnostic whose text, without the {@code [NullAway]} prefix and the
   * link line, equals {@code expected}.
   */
  static Predicate<String> messageIs(String expected) {
    return new MessageIs(expected);
  }

  /**
   * Matches one diagnostic text exactly. Error Prone prints the matcher when no diagnostic on the
   * marked line matches it, so {@link #toString} is the expected message.
   */
  private record MessageIs(String expected) implements Predicate<String> {

    private static final String PREFIX = "[NullAway] ";
    private static final String LINK_LINE = "    (see http://t.uber.com/nullaway )";

    @Override
    public boolean test(String message) {
      if (!message.startsWith(PREFIX) || !message.endsWith(LINK_LINE)) {
        return false;
      }
      return message
          .substring(PREFIX.length(), message.length() - LINK_LINE.length())
          .equals(expected);
    }

    @Override
    public String toString() {
      return "the message\n" + expected;
    }
  }
}
