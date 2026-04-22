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
import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.util.JCDiagnostic;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.fixserialization.Serializer;
import com.uber.nullaway.fixserialization.adapters.SerializationAdapter;
import com.uber.nullaway.fixserialization.location.SymbolLocation;
import com.uber.nullaway.fixserialization.scanners.OriginTrace;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Stores information regarding an error which will be reported by NullAway. */
public class ErrorInfo {

  private final ErrorMessage errorMessage;
  private final ClassAndMemberInfo classAndMemberInfo;

  /**
   * if non-null, this error involved a pseudo-assignment of a @Nullable expression into a @NonNull
   * target, and this field is the Symbol for that target.
   */
  private final @Nullable Symbol nonnullTarget;

  /**
   * In cases where {@link ErrorInfo#nonnullTarget} is {@code null}, we serialize this value at its
   * placeholder in the output tsv file.
   */
  public static final String EMPTY_NONNULL_TARGET_LOCATION_STRING =
      "null\tnull\tnull\tnull\tnull\tnull";

  /** Offset of program point where this error is reported. */
  private final int offset;

  /** Path to the containing source file where this error is reported. */
  private final @Nullable Path path;

  /** Extra argument regarding the error required to generate a fix automatically. */
  private final Map<String, String> infos;

  private final Set<OriginTrace> origins;

  public ErrorInfo(
      TreePath path,
      Tree errorTree,
      ErrorMessage errorMessage,
      @Nullable Symbol nonnullTarget,
      Set<OriginTrace> origins,
      Map<String, String> args) {
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
    this.origins = origins;
    this.infos = args;
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
  public @Nullable Symbol getRegionMember() {
    return classAndMemberInfo.getMember();
  }

  /**
   * Region class where this error is reported by NullAway.
   *
   * @return Enclosing region class. Returns {@code null} if the values are not computed yet.
   */
  public @Nullable Symbol getRegionClass() {
    return classAndMemberInfo.getClazz();
  }

  /**
   * Returns the symbol of a {@code @Nonnull} element which was involved in a pseudo-assignment of a
   * {@code @Nullable} expression into a {@code @Nonnull} target and caused this error to be
   * reported if such element exists, otherwise, it will return {@code null}.
   *
   * @return The symbol of the {@code @Nonnull} element if exists, and {@code null} otherwise.
   */
  public @Nullable Symbol getNonnullTarget() {
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
  public @Nullable Path getPath() {
    return path;
  }

  /**
   * Returns extra information regarding the error required to generate a fix automatically.
   *
   * @return Map from info keys to their values.
   */
  public Map<String, String> getInfos() {
    return infos;
  }

  /** Finds the class and member of program point where the error is reported. */
  public void initEnclosing() {
    classAndMemberInfo.findValues();
  }

  /**
   * Appends an XML representation of the error information to {@code sb}.
   *
   * @param sb the buffer to append to.
   * @param serializationAdapter adapter used to serialize symbols.
   */
  public void appendXml(StringBuilder sb, SerializationAdapter serializationAdapter) {
    sb.append("<error>");
    Serializer.appendXmlElement(sb, "message_type", errorMessage.getMessageType().toString());
    Serializer.appendXmlElement(sb, "message", errorMessage.getMessage());
    Serializer.appendXmlElement(
        sb, "enc_class", Serializer.serializeSymbol(getRegionClass(), serializationAdapter));
    Serializer.appendXmlElement(
        sb, "enc_member", Serializer.serializeSymbol(getRegionMember(), serializationAdapter));
    Serializer.appendXmlElement(sb, "offset", Integer.toString(offset));
    Serializer.appendXmlElement(sb, "path", path != null ? path.toString() : "null");
    if (nonnullTarget != null) {
      sb.append("<nonnull_target>");
      SymbolLocation.createLocationFromSymbol(nonnullTarget)
          .appendXmlFields(sb, serializationAdapter);
      sb.append("</nonnull_target>");
    }
    if (!origins.isEmpty()) {
      sb.append("<origins>");
      for (OriginTrace trace : origins) {
        Symbol sym = trace.origin();
        sb.append("<origin>");
        sb.append("<location>");
        SymbolLocation.createLocationFromSymbol(sym).appendXmlFields(sb, serializationAdapter);
        sb.append("</location>");
        Serializer.appendXmlElement(sb, "kind", sym.getKind().toString().toLowerCase(Locale.ROOT));
        Serializer.appendXmlElement(
            sb, "class", Serializer.serializeSymbol(sym.enclClass(), serializationAdapter));
        Serializer.appendXmlElement(sb, "isAnnotated", Boolean.toString(isAnnotated(sym)));
        Serializer.appendXmlElement(sb, "expression", trace.trace().toString());
        Serializer.appendXmlElement(
            sb, "position", Integer.toString(((JCTree) trace.trace()).pos().getStartPosition()));
        Serializer.appendXmlElement(
            sb, "symbol", Serializer.serializeSymbol(sym, serializationAdapter));
        sb.append("</origin>");
      }
      sb.append("</origins>");
    }
    sb.append("<infos>");
    for (Map.Entry<String, String> entry : infos.entrySet()) {
      Serializer.appendXmlElement(sb, entry.getKey(), entry.getValue());
    }
    sb.append("</infos>");
    sb.append("</error>");
  }

  /**
   * Checks if the symbol is from annotated code.
   *
   * @param symbol The symbol to check.
   * @return True if the symbol is from annotated code, false otherwise.
   */
  private static boolean isAnnotated(Symbol symbol) {
    // TODO for now we follow a very simple heuristic to determine if a symbol is annotated and
    // check if the path to the symbol exists
    Symbol.ClassSymbol enclosingClass = castToNonNull(ASTHelpers.enclosingClass(symbol));
    URI pathInURI = enclosingClass.sourcefile != null ? enclosingClass.sourcefile.toUri() : null;
    return Serializer.pathToSourceFileFromURI(pathInURI) != null;
  }
}
