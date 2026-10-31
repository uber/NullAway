package com.uber.nullaway.oracles;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reduces the stack trace of a NullAway crash to a signature that stays the same for every input
 * that hits the same defect.
 *
 * <p>The signature is the class of the root cause, the last exception of the {@code Caused by}
 * chain, followed by the first {@code com.uber.nullaway} method on that exception's stack, such as
 * {@code java.lang.NullPointerException at
 * com.uber.nullaway.dataflow.NullnessStoreInitializer.getEnvNullnessStoreForClass}. Methods of this
 * package are skipped, since every crash passes through them. The signature holds no line number,
 * and the number javac gives an anonymous class or a lambda body becomes {@code $_}, so an edit
 * elsewhere in the same NullAway file leaves it unchanged.
 *
 * <p>The outermost exception does not identify a defect: the Checker Framework's dataflow analysis
 * wraps whatever a transfer function throws, so unrelated defects share its frame in {@code
 * RunOnceForwardAnalysisImpl.performAnalysis}.
 */
final class CrashSignature {

  /** What a signature names in place of a method when the root cause has no NullAway frame. */
  static final String NO_NULLAWAY_FRAME = "no NullAway frame";

  private static final String STACK_TRACE_HEADER = "Stack Trace:";
  private static final String CAUSED_BY = "Caused by: ";

  /**
   * A frame, with an optional module prefix such as {@code jdk.compiler/}; group 1 is the method.
   */
  private static final Pattern FRAME = Pattern.compile("^at (?:[\\w.$]+/)?([\\w.$<>]+)\\(");

  /**
   * The number javac gives an anonymous class, such as {@code NullAway$2}, or a lambda body, such
   * as {@code lambda$matchClass$3}, in the order of the declarations in the file.
   */
  private static final Pattern ORDINAL = Pattern.compile("\\$\\d+(?=[.$]|$)");

  private static final Pattern ELIDED = Pattern.compile("^\\.\\.\\. (\\d+) more$");
  private static final Pattern EXCEPTION_CLASS = Pattern.compile("^([\\w.$]+)");

  private CrashSignature() {}

  /**
   * One exception of a printed {@code Caused by} chain.
   *
   * @param header the line that names the exception, without {@code Caused by: }
   * @param frames the methods of the printed frames, outermost call last
   * @param elided the count from a trailing {@code ... N more} line: the last {@code elided} frames
   *     of the enclosing exception's stack, which this exception shares and the trace omits
   */
  private record Segment(String header, List<String> frames, int elided) {}

  /**
   * Returns the signature of the crash whose stack trace is {@code detail}: either the message of
   * Error Prone's crash diagnostic, which holds the trace after a {@code Stack Trace:} line, or a
   * trace printed by {@link Throwable#printStackTrace()}.
   */
  static String of(String detail) {
    int header = detail.indexOf(STACK_TRACE_HEADER);
    String trace = header < 0 ? detail : detail.substring(header + STACK_TRACE_HEADER.length());
    List<Segment> chain = parse(trace);
    if (chain.isEmpty()) {
      return "unknown exception at " + NO_NULLAWAY_FRAME;
    }
    Segment root = chain.get(chain.size() - 1);
    Matcher exception = EXCEPTION_CLASS.matcher(root.header());
    String exceptionClass = exception.find() ? exception.group(1) : "unknown exception";
    String method =
        stack(chain, chain.size() - 1).stream()
            .filter(
                f ->
                    f.startsWith("com.uber.nullaway.")
                        && !f.startsWith("com.uber.nullaway.oracles."))
            .findFirst()
            .map(m -> ORDINAL.matcher(m).replaceAll("\\$_"))
            .orElse(NO_NULLAWAY_FRAME);
    return exceptionClass + " at " + method;
  }

  /**
   * Returns the full stack of the exception at {@code index} in {@code chain}: its printed frames,
   * followed by the frames the trace elided and the enclosing exception's stack supplies.
   */
  private static List<String> stack(List<Segment> chain, int index) {
    Segment segment = chain.get(index);
    List<String> frames = new ArrayList<>(segment.frames());
    if (segment.elided() > 0 && index > 0) {
      List<String> enclosing = stack(chain, index - 1);
      int from = Math.max(0, enclosing.size() - segment.elided());
      frames.addAll(enclosing.subList(from, enclosing.size()));
    }
    return frames;
  }

  /**
   * Splits {@code trace} into the outermost exception and its causes, in that order. A suppressed
   * exception and its causes contribute no frames. {@link Throwable#printStackTrace()} indents a
   * suppressed exception one tab deeper than the exception that carries it, and prints the causes
   * of a suppressed exception at the indentation of its {@code Suppressed:} line, so a {@code
   * Caused by} line belongs to the chain only when it is indented less than that line.
   */
  private static List<Segment> parse(String trace) {
    List<String> lines = trace.lines().toList();
    List<Segment> chain = new ArrayList<>();
    String header = null;
    List<String> frames = new ArrayList<>();
    int elided = 0;
    int suppressedIndent = -1;
    for (String line : lines) {
      String stripped = line.strip();
      if (stripped.isEmpty()) {
        continue;
      }
      boolean cause =
          stripped.startsWith(CAUSED_BY)
              && (suppressedIndent < 0 || indentOf(line) < suppressedIndent);
      if (header == null || cause) {
        if (header != null) {
          chain.add(new Segment(header, frames, elided));
        }
        header = cause ? stripped.substring(CAUSED_BY.length()) : stripped;
        frames = new ArrayList<>();
        elided = 0;
        suppressedIndent = -1;
        continue;
      }
      if (stripped.startsWith("Suppressed: ") && suppressedIndent < 0) {
        suppressedIndent = indentOf(line);
      }
      if (suppressedIndent >= 0) {
        continue;
      }
      Matcher frame = FRAME.matcher(stripped);
      Matcher more = ELIDED.matcher(stripped);
      if (frame.find()) {
        frames.add(frame.group(1));
      } else if (more.matches()) {
        elided = Integer.parseInt(more.group(1));
      }
    }
    if (header != null) {
      chain.add(new Segment(header, frames, elided));
    }
    return chain;
  }

  private static int indentOf(String line) {
    return line.length() - line.stripLeading().length();
  }
}
