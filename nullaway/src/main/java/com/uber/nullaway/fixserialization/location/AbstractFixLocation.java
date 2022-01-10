/*
 * Copyright (c) 2022 Uber Technologies, Inc.
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

package com.uber.nullaway.fixserialization.location;

import com.google.common.base.Preconditions;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import java.net.URI;
import javax.annotation.Nullable;
import javax.lang.model.element.ElementKind;

/** abstract base class for {@link FixLocation}. */
public abstract class AbstractFixLocation implements FixLocation {

  protected final ElementKind type;
  protected final URI uri;
  protected final Symbol.ClassSymbol enclosingClass;
  /**
   * Symbol of the method if the target is either a method or a method parameter and null otherwise.
   */
  @Nullable protected Symbol.MethodSymbol enclosingMethod;
  /**
   * Symbol of the element if the target is either a class field or a method parameter and null
   * otherwise.
   */
  @Nullable protected Symbol.VarSymbol variableSymbol;
  /** Index of the element in String if target is a method argument and null otherwise. */
  @Nullable protected String index = null;

  public AbstractFixLocation(ElementKind type, Symbol target) {
    Preconditions.checkArgument(
        type.equals(target.getKind()),
        "Cannot instantiate element of type: "
            + target.getKind()
            + " with location type of: "
            + type
            + ".");
    this.type = type;
    this.enclosingClass = ASTHelpers.enclosingClass(target);
    this.uri = enclosingClass.sourcefile.toUri();
    this.initialize(target);
  }

  /**
   * initializes properties based on the type of the target.
   *
   * @param target Target element.
   */
  protected abstract void initialize(Symbol target);

  /**
   * returns the appropriate subtype of {@link FixLocation} based on the target kind.
   *
   * @param target Target element.
   * @return subtype of {@link FixLocation} matching target's type.
   */
  public static FixLocation createFixLocationFromSymbol(Symbol target) {
    switch (target.getKind()) {
      case PARAMETER:
        return new MethodParameterLocation(target);
      case METHOD:
        return new MethodLocation(target);
      case FIELD:
        return new FieldLocation(target);
      default:
        throw new IllegalArgumentException("Cannot locate node: " + target);
    }
  }

  /**
   * Creates header of an output file containing all {@link FixLocation} written in string which
   * values are separated tabs.
   *
   * @return string representation of the header separated by tabs.
   */
  public static String header() {
    return "location"
        + '\t'
        + "class"
        + '\t'
        + "method"
        + '\t'
        + "param"
        + '\t'
        + "index"
        + '\t'
        + "uri";
  }

  /**
   * returns string representation of content of an object.
   *
   * @return string representation of contents of an object in a line seperated by tabs.
   */
  @Override
  public String tabSeparatedToString() {
    return type.toString()
        + '\t'
        + enclosingClass.toString()
        + '\t'
        + (enclosingMethod != null ? enclosingMethod.toString() : "null")
        + '\t'
        + (variableSymbol != null ? variableSymbol.toString() : "null")
        + '\t'
        + (index != null ? index : "null")
        + '\t'
        + uri.toASCIIString();
  }
}
