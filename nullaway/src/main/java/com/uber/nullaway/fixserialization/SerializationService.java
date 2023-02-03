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
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.VisitorState;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.fixserialization.location.SymbolLocation;
import com.uber.nullaway.fixserialization.out.ErrorInfo;
import com.uber.nullaway.fixserialization.out.SuggestedNullableFixInfo;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

/** A facade class to interact with fix serialization package. */
public class SerializationService {

  /** Special characters that need to be escaped in TSV files. */
  private static final ImmutableMap<Character, Character> escapes =
      ImmutableMap.of(
          '\n', 'n',
          '\t', 't',
          '\f', 'f',
          '\b', 'b',
          '\r', 'r');

  /**
   * Escapes special characters in string to conform with TSV file formats. The most common
   * convention for lossless conversion is to escape special characters with a backslash according
   * to <a
   * href="https://en.wikipedia.org/wiki/Tab-separated_values#Conventions_for_lossless_conversion_to_TSV">
   * Conventions for lossless conversion to TSV</a>
   *
   * @param str String to process.
   * @return returns modified str which its special characters are escaped.
   */
  public static String escapeSpecialCharacters(String str) {
    // regex needs "\\" to match character '\', each must also be escaped in string to create "\\",
    // therefore we need four "\".
    // escape existing backslashes
    str = str.replaceAll(Pattern.quote("\\"), Matcher.quoteReplacement("\\\\"));
    // escape special characters
    for (Map.Entry<Character, Character> entry : escapes.entrySet()) {
      str =
          str.replaceAll(
              String.valueOf(entry.getKey()), Matcher.quoteReplacement("\\" + entry.getValue()));
    }
    return str;
  }

  /**
   * Serializes the suggested type change of an element in the source code that can resolve the
   * error. We do not want suggested fix changes to override explicit annotations in the code,
   * therefore, if the target element has an explicit {@code @Nonnull} annotation, no type change is
   * suggested.
   *
   * @param config NullAway config.
   * @param state Visitor state.
   * @param target Target element to alternate it's type.
   * @param errorMessage Error caused by the target.
   */
  public static void serializeFixSuggestion(
      Config config, VisitorState state, Symbol target, ErrorMessage errorMessage) {
    FixSerializationConfig serializationConfig = config.getSerializationConfig();
    if (!serializationConfig.suggestEnabled) {
      return;
    }
    // Skip if the element has an explicit @Nonnull annotation.
    if (Nullness.hasNonNullAnnotation(target, config)) {
      return;
    }
    Trees trees = Trees.instance(JavacProcessingEnvironment.instance(state.context));
    // Skip if the element is received as bytecode.
    if (trees.getPath(target) == null) {
      return;
    }
    SymbolLocation location = SymbolLocation.createLocationFromSymbol(target);
    SuggestedNullableFixInfo suggestedNullableFixInfo =
        buildFixMetadata(state.getPath(), errorMessage, location);
    Serializer serializer = serializationConfig.getSerializer();
    Preconditions.checkNotNull(
        serializer, "Serializer shouldn't be null at this point, error in configuration setting!");
    serializer.serializeSuggestedFixInfo(
        suggestedNullableFixInfo, serializationConfig.suggestEnclosing);
  }

  /**
   * Serializes the reporting error.
   *
   * @param config NullAway config.
   * @param state Visitor state.
   * @param errorTree Tree of the element involved in the reporting error.
   * @param errorMessage Error caused by the target.
   */
  public static void serializeReportingError(
      Config config,
      VisitorState state,
      Tree errorTree,
      @Nullable Symbol target,
      ErrorMessage errorMessage) {
    Serializer serializer = config.getSerializationConfig().getSerializer();
    Preconditions.checkNotNull(
        serializer, "Serializer shouldn't be null at this point, error in configuration setting!");
    serializer.serializeErrorInfo(new ErrorInfo(state.getPath(), errorTree, errorMessage, target));
  }

  /**
   * Builds the {@link SuggestedNullableFixInfo} instance based on the {@link ErrorMessage} type.
   */
  private static SuggestedNullableFixInfo buildFixMetadata(
      TreePath path, ErrorMessage errorMessage, SymbolLocation location) {
    SuggestedNullableFixInfo suggestedNullableFixInfo;
    switch (errorMessage.getMessageType()) {
      case RETURN_NULLABLE:
      case WRONG_OVERRIDE_RETURN:
      case WRONG_OVERRIDE_PARAM:
      case PASS_NULLABLE:
      case FIELD_NO_INIT:
      case ASSIGN_FIELD_NULLABLE:
      case METHOD_NO_INIT:
        suggestedNullableFixInfo = new SuggestedNullableFixInfo(path, location, errorMessage);
        break;
      default:
        throw new IllegalStateException(
            "Cannot suggest a type to resolve error of type: " + errorMessage.getMessageType());
    }
    return suggestedNullableFixInfo;
  }
}
