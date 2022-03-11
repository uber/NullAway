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
import javax.lang.model.element.ElementKind;

/** abstract base class for {@link FixLocation}. */
public abstract class AbstractFixLocation implements FixLocation {

  /** Element kind of the targeted symbol */
  protected final ElementKind type;
  /** URI of the file containing the symbol. */
  protected final URI uri;
  /** Enclosing class of the symbol. */
  protected final Symbol.ClassSymbol enclosingClass;

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
  }
}
