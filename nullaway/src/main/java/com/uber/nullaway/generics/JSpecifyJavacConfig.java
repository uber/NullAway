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
 * -XepOpt:NullAway:JSpecifyMode=true}, {@code -XDaddTypeAnnotationsToSymbol=true}, and the JSpecify
 * experimental-features flag together. The experimental-features flag enables wildcard support,
 * generic inference failure warnings, and JSpecify JDK models.
 */
public final class JSpecifyJavacConfig {

  public static final String JSPECIFY_MODE_FLAG = "-XepOpt:NullAway:JSpecifyMode=true";
  public static final String ADD_TYPE_ANNOTATIONS_FLAG_NAME = "addTypeAnnotationsToSymbol";
  public static final String ADD_TYPE_ANNOTATIONS_FLAG =
      "-XD" + ADD_TYPE_ANNOTATIONS_FLAG_NAME + "=true";
  public static final String HANDLE_WILDCARD_GENERICS_FLAG =
      "-XepOpt:NullAway:HandleWildcardGenerics=true";
  public static final String JSPECIFY_EXPERIMENTAL = "-XepOpt:NullAway:JSpecifyExperimental=true";

  private static final List<String> JSPECIFY_MODE_ARGS =
      List.of(JSPECIFY_MODE_FLAG, ADD_TYPE_ANNOTATIONS_FLAG, JSPECIFY_EXPERIMENTAL);

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

  public enum JavacConfigValidityResult {
    /** valid configuration */
    VALID,
    /** {@code -XDaddTypeAnnotationsToSymbol=true} is missing */
    FLAG_NOT_SET_TO_TRUE,
    /** JDK 17 or 21 and {@code -XDaddTypeAnnotationsToSymbol} is not supported */
    FLAG_NOT_SUPPORTED_BY_JAVAC
  }

  /**
   * Checks that in JSpecify mode, either (1) we are running on JDK 22 or above, or (2) the user has
   * passed {@code -XDaddTypeAnnotationsToSymbol=true} to javac _and_ the running javac version
   * supports that flag
   *
   * @param state the visitor state
   * @return true if the javac configuration is valid for JSpecify mode, false otherwise
   */
  public static JavacConfigValidityResult isValidJavacConfigForJSpecifyMode(VisitorState state) {
    Runtime.Version version = Runtime.version();
    if (version.feature() < 22) {
      Options opts = Options.instance(state.context);
      // The flag must be set to true
      if (!opts.isSet(ADD_TYPE_ANNOTATIONS_FLAG_NAME)
          || !Boolean.parseBoolean(opts.get(ADD_TYPE_ANNOTATIONS_FLAG_NAME))) {
        return JavacConfigValidityResult.FLAG_NOT_SET_TO_TRUE;
      }
      // we must also be running on a JDK version that supports the flag
      return javacSupportsAddTypeAnnotationsToSymbol()
          ? JavacConfigValidityResult.VALID
          : JavacConfigValidityResult.FLAG_NOT_SUPPORTED_BY_JAVAC;
    } else {
      // JDK 22+ always has type annotations on symbols
      return JavacConfigValidityResult.VALID;
    }
  }

  /**
   * Detects whether the {@code -XDaddTypeAnnotationsToSymbol} flag is supported on JDK 17 or 21, by
   * using reflection to detect the presence of a corresponding field on {@code ClassReader}. This
   * is fragile, but, the relevant field name in JDKs 17 / 21 is unlikely to change.
   */
  static boolean javacSupportsAddTypeAnnotationsToSymbol() {
    try {
      Class<?> classReader = Class.forName("com.sun.tools.javac.jvm.ClassReader");
      var ignored = classReader.getDeclaredField("addTypeAnnotationsToSymbol");
      return true;
    } catch (ClassNotFoundException | NoSuchFieldException e) {
      return false;
    }
  }
}
