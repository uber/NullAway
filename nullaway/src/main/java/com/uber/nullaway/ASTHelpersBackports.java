package com.uber.nullaway;

import com.sun.tools.javac.code.Symbol;
import java.util.Optional;
import javax.lang.model.element.ElementKind;

/**
 * Backports of {@link com.google.errorprone.util.ASTHelpers} routines to maintain compatibility
 * with older Error Prone versions
 */
public class ASTHelpersBackports {

  private ASTHelpersBackports() {}

  /** We can remove this method when we require Error Prone 2.49.0 */
  public static Optional<Symbol.PackageSymbol> enclosingPackage(Symbol sym) {
    Symbol curr = sym;
    while (curr != null) {
      if (curr.getKind().equals(ElementKind.PACKAGE)) {
        return Optional.of((Symbol.PackageSymbol) curr);
      }
      curr = curr.owner;
    }
    return Optional.empty();
  }
}
