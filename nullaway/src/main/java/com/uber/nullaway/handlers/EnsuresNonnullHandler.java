/*
 * Copyright (c) 2017 Uber Technologies, Inc.
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

package com.uber.nullaway.handlers;

import com.sun.tools.javac.code.Symbol;
import java.util.Map;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/**
 * This Handler parses the jetbrains @Contract annotation and honors the nullness spec defined there
 * on a best effort basis.
 *
 * <p>Currently, we can only reason about cases where the contract specifies that the return value
 * of the method depends on the nullness value of a single argument. This means we can reason about
 * rules like the following:
 *
 * <ul>
 *   <li>@Contract("null -> true")
 *   <li>@Contract("_, null, _ -> false")
 *   <li>@Contract("!null, _ -> false; null, _ -> true")
 *   <li>@Contract("!null -> !null")
 * </ul>
 *
 * In the last case, nullness will be propagated iff the nullness of the argument is already known
 * at invocation.
 *
 * <p>However, when the return depends on multiple arguments, this handler usually ignores the rule,
 * since it is not clear which of the values in question are null or not. For example,
 * for @Contract("null, null -> true") we know nothing when the method returns true (because truth
 * of the consequent doesn't imply truth of the antecedent), and if it return false, we only know
 * that at least one of the two arguments was non-null, but can't know for sure which one. NullAway
 * doesn't reason about multiple value conditional nullness constraints in any general way.
 *
 * <p>In some cases, this handler can determine that some arguments are already known to be non-null
 * and reason in terms of the remaining (under-constrained) arguments, to see if the final value of
 * this method depends on the nullness of a single argument for this callsite, even if the @Contract
 * clause is given in terms of many. This is not behavior that should be counted on, but it is
 * sound.
 */
@SuppressWarnings({"ALL", "UnusedMethod"})
public class EnsuresNonnullHandler extends BaseNoOpHandler {

  private static final String annotName = "com.uber.nullaway.qual.EnsuresNonnull";

  /**
   * Retrieve the string value inside an @Contract annotation without statically depending on the
   * type.
   *
   * @param sym A method which has an @Contract annotation.
   * @return The string value spec inside the annotation.
   */
  private static @Nullable String getContractFromAnnotation(Symbol.MethodSymbol sym) {
    for (AnnotationMirror annotation : sym.getAnnotationMirrors()) {
      Element element = annotation.getAnnotationType().asElement();
      assert element.getKind().equals(ElementKind.ANNOTATION_TYPE);
      if (((TypeElement) element).getQualifiedName().contentEquals(annotName)) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e :
            annotation.getElementValues().entrySet()) {
          if (e.getKey().getSimpleName().contentEquals("value")) {
            String value = e.getValue().toString();
            if (value.startsWith("\"") && value.endsWith("\"")) {
              value = value.substring(1, value.length() - 1);
            }
            return value;
          }
        }
      }
    }
    return null;
  }
}
