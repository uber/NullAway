package com.uber.nullaway.testhelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared helper for configuring NullAway compilation tests that rely on JSpecify annotations.
 *
 * <p>This utility ensures tests always pass both {@code -XepOpt:NullAway:JSpecifyMode=true} and
 * {@code -XDaddTypeAnnotationsToSymbol=true} together.
 */
public final class NullAwayJSpecifyConfig {

  public static final String JSPECIFY_MODE_FLAG = "-XepOpt:NullAway:JSpecifyMode=true";
  public static final String ADD_TYPE_ANNOTATIONS_FLAG = "-XDaddTypeAnnotationsToSymbol=true";

  private static final List<String> JSPECIFY_MODE_ARGS =
      List.of(JSPECIFY_MODE_FLAG, ADD_TYPE_ANNOTATIONS_FLAG);

  private NullAwayJSpecifyConfig() {}

  /** Returns the compiler arguments required to enable JSpecify mode in tests. */
  public static List<String> jspecifyModeArgs() {
    return JSPECIFY_MODE_ARGS;
  }

  /**
   * Returns a copy of {@code args} with the JSpecify-specific compiler arguments appended.
   *
   * @param args the base compiler arguments
   */
  public static List<String> withJSpecifyModeArgs(List<String> args) {
    List<String> result = new ArrayList<>(args.size() + JSPECIFY_MODE_ARGS.size());
    result.addAll(args);
    result.addAll(JSPECIFY_MODE_ARGS);
    return Collections.unmodifiableList(result);
  }
}
