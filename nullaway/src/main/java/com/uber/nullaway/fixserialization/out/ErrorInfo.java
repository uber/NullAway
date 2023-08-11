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

import static com.uber.nullaway.ErrorMessage.MessageTypes.FIELD_NO_INIT;
import static com.uber.nullaway.ErrorMessage.MessageTypes.METHOD_NO_INIT;

import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.util.JCDiagnostic;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.fixserialization.Serializer;
import java.nio.file.Path;
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

  /** Path to the containing source file where this error is reported. */
  @Nullable private final Path path;

  public ErrorInfo(
      TreePath path, Tree errorTree, ErrorMessage errorMessage, @Nullable Symbol nonnullTarget) {
    this.classAndMemberInfo =
        (errorMessage.getMessageType().equals(FIELD_NO_INIT)
                || errorMessage.getMessageType().equals(METHOD_NO_INIT))
            ? new ClassAndMemberInfo(errorTree)
            : new ClassAndMemberInfo(path);
    this.errorMessage = errorMessage;
    this.nonnullTarget = nonnullTarget;
    JCDiagnostic.DiagnosticPosition treePosition = (JCDiagnostic.DiagnosticPosition) errorTree;
    this.offset = treePosition.getStartPosition();
    this.path =
        Serializer.pathToSourceFileFromURI(path.getCompilationUnit().getSourceFile().toUri());
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
    return classAndMemberInfo.getClazz();
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
   * Returns Path to the containing source file where this error is reported.
   *
   * @return Path to the containing source file where this error is reported.
   */
  @Nullable
  public Path getPath() {
    return path;
  }

  /** Finds the class and member of program point where the error is reported. */
  public void initEnclosing() {
    classAndMemberInfo.findValues();
  }
}
