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
import java.util.Set;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
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

  /** Structured metadata about the nullable expression, if available. */
  private final @Nullable NullableExpressionInfo nullableExpressionInfo;

  private final Set<OriginTrace> origins;

  public ErrorInfo(
      TreePath path,
      Tree errorTree,
      ErrorMessage errorMessage,
      @Nullable Symbol nonnullTarget,
      Set<OriginTrace> origins,
      @Nullable NullableExpressionInfo nullableExpressionInfo) {
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
    this.nullableExpressionInfo = nullableExpressionInfo;
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
   * Returns structured metadata about the nullable expression at the error site, if available.
   *
   * @return the nullable expression info, or {@code null} if not applicable.
   */
  public @Nullable NullableExpressionInfo getNullableExpressionInfo() {
    return nullableExpressionInfo;
  }

  /** Finds the class and member of program point where the error is reported. */
  public void initEnclosing() {
    classAndMemberInfo.findValues();
  }

  /**
   * Writes an XML representation of the error information to {@code writer}.
   *
   * @param writer the writer to emit to.
   * @param adapter adapter used to serialize symbols.
   */
  public void writeXml(XMLStreamWriter writer, SerializationAdapter adapter)
      throws XMLStreamException {
    writer.writeStartElement("error");
    Serializer.writeTextElement(writer, "message_type", errorMessage.getMessageType().toString());
    Serializer.writeTextElement(writer, "message", errorMessage.getMessage());
    Serializer.writeTextElement(
        writer, "enc_class", Serializer.serializeSymbol(getRegionClass(), adapter));
    Serializer.writeTextElement(
        writer, "enc_member", Serializer.serializeSymbol(getRegionMember(), adapter));
    Serializer.writeTextElement(writer, "offset", Integer.toString(offset));
    Serializer.writeTextElement(writer, "path", path != null ? path.toString() : "null");
    if (nonnullTarget != null) {
      writer.writeStartElement("nonnull_target");
      SymbolLocation.createLocationFromSymbol(nonnullTarget).writeXmlFields(writer, adapter);
      writer.writeEndElement();
    }
    if (!origins.isEmpty()) {
      writer.writeStartElement("origins");
      for (OriginTrace trace : origins) {
        Symbol sym = trace.origin();
        writer.writeStartElement("origin");
        writer.writeStartElement("location");
        SymbolLocation.createLocationFromSymbol(sym).writeXmlFields(writer, adapter);
        writer.writeEndElement();
        Serializer.writeTextElement(
            writer, "kind", sym.getKind().toString().toLowerCase(Locale.ROOT));
        Serializer.writeTextElement(
            writer, "class", Serializer.serializeSymbol(sym.enclClass(), adapter));
        Serializer.writeTextElement(writer, "isAnnotated", Boolean.toString(isAnnotated(sym)));
        Serializer.writeTextElement(writer, "expression", trace.trace().toString());
        Serializer.writeTextElement(
            writer,
            "position",
            Integer.toString(((JCTree) trace.trace()).pos().getStartPosition()));
        Serializer.writeTextElement(writer, "symbol", Serializer.serializeSymbol(sym, adapter));
        writer.writeEndElement();
      }
      writer.writeEndElement();
    }
    if (nullableExpressionInfo != null) {
      nullableExpressionInfo.writeXml(writer);
    }
    writer.writeEndElement();
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
