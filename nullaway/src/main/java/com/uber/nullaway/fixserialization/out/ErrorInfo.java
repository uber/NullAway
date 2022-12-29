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

package com.uber.nullaway.fixserialization.out;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.util.JCDiagnostic;
import com.uber.nullaway.ErrorMessage;
import java.net.URI;
import javax.annotation.Nullable;

/** Stores information regarding an error which will be reported by NullAway. */
public class ErrorInfo {

  private final ErrorMessage errorMessage;
  private final ClassAndMemberInfo classAndMemberInfo;

  /**
   * if non-null, this error involved a pseudo-assignment of a @Nullable expression into a @NonNull
   * target, and this field is the Symbol for that target.
   */
  @Nullable private final Symbol nonnullTarget;
  /**
   * In cases where {@link ErrorInfo#nonnullTarget} is {@code null}, we serialize this value at its
   * placeholder in the output tsv file.
   */
  public static final String EMPTY_NONNULL_TARGET_LOCATION_STRING =
      "null\tnull\tnull\tnull\tnull\tnull";

  /** Offset of program point where this error is reported. */
  private final int offset;
  /** Uri to the containing source file where this error is reported. */
  private final URI uri;

  public ErrorInfo(TreePath path, ErrorMessage errorMessage, @Nullable Symbol nonnullTarget) {
    this.classAndMemberInfo = new ClassAndMemberInfo(path);
    this.errorMessage = errorMessage;
    this.nonnullTarget = nonnullTarget;
    JCDiagnostic.DiagnosticPosition treePosition = (JCDiagnostic.DiagnosticPosition) path.getLeaf();
    this.offset = treePosition.getStartPosition();
    this.uri = path.getCompilationUnit().getSourceFile().toUri();
  }

  /**
   * Getter for error message.
   *
   * @return Error message.
   */
  public ErrorMessage getErrorMessage() {
    return errorMessage;
  }

  /**
   * Region member where this error is reported by NullAway.
   *
   * @return Enclosing region member. Returns {@code null} if the values are not computed yet.
   */
  @Nullable
  public Symbol getRegionMember() {
    return classAndMemberInfo.getMember();
  }

  /**
   * Region class where this error is reported by NullAway.
   *
   * @return Enclosing region class. Returns {@code null} if the values are not computed yet.
   */
  @Nullable
  public Symbol getRegionClass() {
    return classAndMemberInfo.getClazz() == null
        ? null
        : ASTHelpers.getSymbol(classAndMemberInfo.getClazz());
  }

  /**
   * Returns the symbol of a {@code @Nonnull} element which was involved in a pseudo-assignment of a
   * {@code @Nullable} expression into a {@code @Nonnull} target and caused this error to be
   * reported if such element exists, otherwise, it will return {@code null}.
   *
   * @return The symbol of the {@code @Nonnull} element if exists, and {@code null} otherwise.
   */
  @Nullable
  public Symbol getNonnullTarget() {
    return nonnullTarget;
  }

  /**
   * Returns offset of program point where this error is reported.
   *
   * @return Offset of program point where this error is reported.
   */
  public int getOffset() {
    return offset;
  }

  /**
   * Returns URI to the containing source file where this error is reported.
   *
   * @return URI to the containing source file where this error is reported.
   */
  public URI getUri() {
    return uri;
  }

  /** Finds the class and member of program point where the error is reported. */
  public void initEnclosing() {
    classAndMemberInfo.findValues();
  }
}
