/*
 * Copyright (c) 2025 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to do so, subject to the following conditions:
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

package com.uber.nullaway;

import com.google.auto.service.AutoService;
import com.google.errorprone.BugPattern;
import com.google.errorprone.BugPattern.SeverityLevel;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.tools.javac.code.Symbol;
import javax.lang.model.element.NestingKind;

/**
 * Checks that every top-level class is explicitly null-marked or is inside a null-marked package or
 * module.
 */
@AutoService(BugChecker.class)
@BugPattern(
    severity = SeverityLevel.SUGGESTION,
    summary =
        "Top-level classes must either be directly annotated with @NullMarked/@NullUnmarked or be in a"
            + " package or module that is explicitly @NullMarked/@NullUnmarked.")
public final class RequireExplicitNullMarking extends BugChecker
    implements BugChecker.ClassTreeMatcher {

  /**
   * The BugPattern annotation on this class, used below to determine whether to report a match or
   * not.
   */
  private static final BugPattern BUG_PATTERN =
      RequireExplicitNullMarking.class.getAnnotation(BugPattern.class);

  @Override
  public Description matchClass(ClassTree classTree, VisitorState state) {
    Symbol.ClassSymbol classSymbol = ASTHelpers.getSymbol(classTree);
    if (classSymbol == null) {
      return Description.NO_MATCH;
    }
    if (classSymbol.getNestingKind() != NestingKind.TOP_LEVEL) {
      return Description.NO_MATCH;
    }
    for (Symbol symbol = classSymbol; symbol != null; symbol = symbol.getEnclosingElement()) {
      if (hasNullMarkedOrNullUnmarkedAnnotation(symbol)) {
        return Description.NO_MATCH;
      }
    }
    SeverityLevel severityLevel =
        state.severityMap().getOrDefault(BUG_PATTERN.name(), BUG_PATTERN.severity());
    // If the severity is SUGGESTION, we do not want to report a match, to avoid noisy NOTE-level
    // messages from javac.
    return SeverityLevel.SUGGESTION.equals(severityLevel)
        ? Description.NO_MATCH
        : describeMatch(classTree);
  }

  private static boolean hasNullMarkedOrNullUnmarkedAnnotation(Symbol symbol) {
    return ASTHelpers.hasDirectAnnotationWithSimpleName(symbol, "NullMarked")
        || ASTHelpers.hasDirectAnnotationWithSimpleName(symbol, "NullUnmarked");
  }
}
