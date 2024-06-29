package com.uber.nullaway;

import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import java.util.List;

/**
 * Methods backported from {@link com.google.errorprone.util.ASTHelpers} since we do not yet require
 * a recent-enough Error Prone version. The methods should be removed once we bump our minimum Error
 * Prone version accordingly. We also include methods where new overloads have been added in recent
 * Error Prone versions, to ensure binary compatibility when compiling against a new version but
 * running on an old version.
 */
public class ASTHelpersBackports {

  private ASTHelpersBackports() {}

  /**
   * Returns true if the symbol is static. Returns {@code false} for module symbols. Remove once we
   * require Error Prone 2.16.0 or higher.
   */
  @SuppressWarnings("ASTHelpersSuggestions")
  public static boolean isStatic(Symbol symbol) {
    if (symbol.getKind().name().equals("MODULE")) {
      return false;
    }
    return symbol.isStatic();
  }

  /**
   * A wrapper for {@link Symbol#getEnclosedElements} to avoid binary compatibility issues for
   * covariant overrides in subtypes of {@link Symbol}.
   *
   * <p>Same as this ASTHelpers method in Error Prone:
   * https://github.com/google/error-prone/blame/a1318e4b0da4347dff7508108835d77c470a7198/check_api/src/main/java/com/google/errorprone/util/ASTHelpers.java#L1148
   * TODO: delete this method and switch to ASTHelpers once we can require Error Prone 2.20.0
   */
  @SuppressWarnings("ASTHelpersSuggestions")
  public static List<Symbol> getEnclosedElements(Symbol symbol) {
    return symbol.getEnclosedElements();
  }

  /**
   * A wrapper for {@link ASTHelpers#hasDirectAnnotationWithSimpleName(Symbol, String)} to avoid
   * binary compatibility issues with new overloads in recent Error Prone versions. NullAway code
   * should only use this method and not call the corresponding ASTHelpers methods directly.
   *
   * <p>TODO: delete this method and switch to ASTHelpers once we can require Error Prone 2.24.0
   *
   * @param sym the symbol
   * @param simpleName the simple name
   * @return {@code true} iff the symbol has a direct annotation with the given simple name
   */
  public static boolean hasDirectAnnotationWithSimpleName(Symbol sym, String simpleName) {
    return ASTHelpers.hasDirectAnnotationWithSimpleName(sym, simpleName);
  }
}
