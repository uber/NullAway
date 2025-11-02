package com.uber.nullaway.generics;

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.util.Options;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Helper methods for checking validity of the javac configuration for JSpecify mode and for
 * configuring NullAway compilation tests that rely on JSpecify annotations.
 *
 * <p>For tests, this utility ensures that they always pass {@code
 * -XepOpt:NullAway:JSpecifyMode=true} and {@code -XDaddTypeAnnotationsToSymbol=true} together.
 */
public final class JSpecifyJavacConfig {

  public static final String JSPECIFY_MODE_FLAG = "-XepOpt:NullAway:JSpecifyMode=true";
  public static final String ADD_TYPE_ANNOTATIONS_FLAG = "-XDaddTypeAnnotationsToSymbol=true";

  private static final List<String> JSPECIFY_MODE_ARGS =
      List.of(JSPECIFY_MODE_FLAG, ADD_TYPE_ANNOTATIONS_FLAG);

  private JSpecifyJavacConfig() {}

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

  /**
   * Checks that in JSpecify mode, either (1) we are running on JDK 22 or above, or (2) the user has
   * passed {@code -XDaddTypeAnnotationsToSymbol=true} to javac
   *
   * @param state the visitor state
   * @return true if the javac configuration is valid for JSpecify mode, false otherwise
   */
  public static boolean isValidJavacConfigForJSpecifyMode(VisitorState state) {
    Runtime.Version version = Runtime.version();
    if (version.feature() < 22) {
      Options opts = Options.instance(state.context);
      String key = "addTypeAnnotationsToSymbol";
      if (!opts.isSet(key)) {
        return false;
      }
      return Boolean.parseBoolean(opts.get(key));
    } else {
      // JDK 22+ always has type annotations on symbols
      return true;
    }
  }
}
