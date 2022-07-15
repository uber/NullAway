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
import com.google.errorprone.VisitorState;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.fixserialization.location.FixLocation;
import com.uber.nullaway.fixserialization.out.ErrorInfo;
import com.uber.nullaway.fixserialization.out.SuggestedNullableFixInfo;

/** A facade class to interact with fix serialization package. */
public class SerializationService {

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
    FixLocation location = FixLocation.createFixLocationFromSymbol(target);
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
   * @param errorMessage Error caused by the target.
   */
  public static void serializeReportingError(
      Config config, VisitorState state, ErrorMessage errorMessage) {
    Serializer serializer = config.getSerializationConfig().getSerializer();
    Preconditions.checkNotNull(
        serializer, "Serializer shouldn't be null at this point, error in configuration setting!");
    serializer.serializeErrorInfo(new ErrorInfo(state.getPath(), errorMessage));
  }

  /**
   * Builds the {@link SuggestedNullableFixInfo} instance based on the {@link ErrorMessage} type.
   */
  private static SuggestedNullableFixInfo buildFixMetadata(
      TreePath path, ErrorMessage errorMessage, FixLocation location) {
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
