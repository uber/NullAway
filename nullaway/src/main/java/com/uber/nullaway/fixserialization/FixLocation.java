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

package com.uber.nullaway.fixserialization;

import com.google.common.base.Preconditions;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import java.net.URI;
import javax.lang.model.element.ElementKind;

/** Holds all the information to target a specific element in the source code. */
public class FixLocation implements SeparatedValueDisplay {

  private final URI uri;
  private final Symbol.ClassSymbol enclosingClass;
  private Symbol.MethodSymbol enclosingMethod;
  /** Symbol of the element if the target is either a class field or a method parameter. */
  private Symbol.VarSymbol variableSymbol;

  private Kind kind;
  /** Index of the element if target is a method argument. */
  int index = 0;

  public enum Kind {
    CLASS_FIELD("CLASS_FIELD"),
    METHOD_PARAM("METHOD_PARAM"),
    METHOD_RETURN("METHOD_RETURN");
    public final String label;

    Kind(String label) {
      this.label = label;
    }
  }

  /**
   * Initializes properties based on the type of the target.
   *
   * @param target Target element.
   */
  public FixLocation(Symbol target) {
    switch (target.getKind()) {
      case PARAMETER:
        createMethodParamLocation(target);
        break;
      case METHOD:
        createMethodLocation(target);
        break;
      case FIELD:
        createFieldLocation(target);
        break;
      default:
        throw new IllegalArgumentException("Cannot locate node: " + target);
    }
    this.enclosingClass = ASTHelpers.enclosingClass(target);
    this.uri = enclosingClass.sourcefile.toUri();
  }

  private void createFieldLocation(Symbol field) {
    Preconditions.checkArgument(field.getKind() == ElementKind.FIELD);
    variableSymbol = (Symbol.VarSymbol) field;
    kind = Kind.CLASS_FIELD;
  }

  private void createMethodLocation(Symbol method) {
    Preconditions.checkArgument(method.getKind() == ElementKind.METHOD);
    kind = Kind.METHOD_RETURN;
    enclosingMethod = (Symbol.MethodSymbol) method;
  }

  private void createMethodParamLocation(Symbol parameter) {
    Preconditions.checkArgument(parameter.getKind() == ElementKind.PARAMETER);
    this.kind = Kind.METHOD_PARAM;
    this.variableSymbol = (Symbol.VarSymbol) parameter;
    Symbol enclosingMethod = parameter;
    // Look for the enclosing method.
    while (enclosingMethod != null && enclosingMethod.getKind() != ElementKind.METHOD) {
      enclosingMethod = enclosingMethod.owner;
    }
    Preconditions.checkNotNull(enclosingMethod);
    this.enclosingMethod = (Symbol.MethodSymbol) enclosingMethod;
    for (int i = 0; i < this.enclosingMethod.getParameters().size(); i++) {
      if (this.enclosingMethod.getParameters().get(i).equals(parameter)) {
        index = i;
        break;
      }
    }
  }

  @Override
  public String display(String delimiter) {
    return kind.label
        + delimiter
        + enclosingClass.toString()
        + delimiter
        + (enclosingMethod != null ? enclosingMethod.toString() : "null")
        + delimiter
        + (variableSymbol != null ? variableSymbol.toString() : "null")
        + delimiter
        + index
        + delimiter
        + (uri != null ? uri.toASCIIString() : "null");
  }

  /**
   * creates header of a csv file containing all {@link FixLocation}.
   *
   * @param delimiter the delimiter.
   * @return string representation of the header separated by the {@code delimiter}.
   */
  public static String header(String delimiter) {
    return "location"
        + delimiter
        + "class"
        + delimiter
        + "method"
        + delimiter
        + "param"
        + delimiter
        + "index"
        + delimiter
        + "uri";
  }
}
