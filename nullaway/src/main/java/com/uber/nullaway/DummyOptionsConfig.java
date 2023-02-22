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

package com.uber.nullaway;

import static com.uber.nullaway.ErrorProneCLIFlagsConfig.EP_FL_NAMESPACE;
import static com.uber.nullaway.ErrorProneCLIFlagsConfig.FL_ANNOTATED_PACKAGES;

import com.google.common.collect.ImmutableSet;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.fixserialization.FixSerializationConfig;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Dummy Config class required for the {@link NullAway} empty constructor.
 *
 * <p>Error Prone requires us to have an empty constructor for each Plugin, in addition to the
 * constructor taking an ErrorProneFlags object. The {@link NullAway} plugin should never be used
 * without at least the {@link ErrorProneCLIFlagsConfig#FL_ANNOTATED_PACKAGES} flag set, so this
 * config object will throw an exception if used when actually running the analysis.
 */
public class DummyOptionsConfig implements Config {

  public static final String ERROR_MESSAGE =
      "To run the "
          + EP_FL_NAMESPACE
          + " analysis plugin please specify analysis "
          + "options with -XepOpt:"
          + EP_FL_NAMESPACE
          + ":[...]. You should at least specify annotated packages, "
          + "using the -XepOpt:"
          + FL_ANNOTATED_PACKAGES
          + "=[...] flag.";

  public DummyOptionsConfig() {}

  @Override
  public boolean fromExplicitlyAnnotatedPackage(String className) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean fromExplicitlyUnannotatedPackage(String className) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean treatGeneratedAsUnannotated() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean serializationIsActive() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public FixSerializationConfig getSerializationConfig() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isExcludedClass(String className) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isUnannotatedClass(Symbol.ClassSymbol symbol) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public ImmutableSet<String> getExcludedClassAnnotations() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public ImmutableSet<String> getGeneratedCodeAnnotations() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean exhaustiveOverride() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isKnownInitializerMethod(Symbol.MethodSymbol methodSymbol) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isExternalInitClassAnnotation(String annotationName) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isContractAnnotation(String annotationName) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isExcludedFieldAnnotation(String annotationName) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isInitializerMethodAnnotation(String annotationName) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isCustomNullableAnnotation(String annotationName) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isCustomNonnullAnnotation(String annotationName) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean suggestSuppressions() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean assertsEnabled() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean acknowledgeRestrictiveAnnotations() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean checkOptionalEmptiness() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean checkContracts() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean handleTestAssertionLibraries() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public Set<String> getOptionalClassPaths() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  @Nullable
  public String getCastToNonNullMethod() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public String getAutofixSuppressionComment() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isSkippedLibraryModel(String classDotMethod) {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isJarInferEnabled() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  /** --- JarInfer configs --- */
  @Override
  public boolean isJarInferUseReturnAnnotations() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public String getJarInferRegexStripModelJarName() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public String getJarInferRegexStripCodeJarName() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public String getErrorURL() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean acknowledgeAndroidRecent() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }

  @Override
  public boolean isJSpecifyMode() {
    throw new IllegalStateException(ERROR_MESSAGE);
  }
}
