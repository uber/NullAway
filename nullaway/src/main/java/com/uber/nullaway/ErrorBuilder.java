/*
 * Copyright (c) 2019 Uber Technologies, Inc.
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

import static com.uber.nullaway.ErrorMessage.MessageTypes.FIELD_NO_INIT;
import static com.uber.nullaway.ErrorMessage.MessageTypes.GET_ON_EMPTY_OPTIONAL;
import static com.uber.nullaway.ErrorMessage.MessageTypes.METHOD_NO_INIT;
import static com.uber.nullaway.ErrorMessage.MessageTypes.NONNULL_FIELD_READ_BEFORE_INIT;
import static com.uber.nullaway.NullAway.CORE_CHECK_NAME;
import static com.uber.nullaway.NullAway.INITIALIZATION_CHECK_NAME;
import static com.uber.nullaway.NullAway.OPTIONAL_CHECK_NAME;
import static com.uber.nullaway.NullAway.getTreesInstance;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.errorprone.VisitorState;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.util.DiagnosticSource;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.tools.JavaFileObject;

/** A class to construct error message to be displayed after the analysis finds error. */
public class ErrorBuilder {

  private final Config config;

  /** Checker name that can be used to suppress the warnings. */
  private final String suppressionName;

  /** Additional identifiers for this check, to be checked for in @SuppressWarnings annotations. */
  private final Set<String> allNames;

  ErrorBuilder(Config config, String suppressionName, Set<String> allNames) {
    this.config = config;
    this.suppressionName = suppressionName;
    this.allNames = allNames;
  }

  /**
   * create an error description for a nullability warning
   *
   * @param errorMessage the error message object.
   * @param descriptionBuilder the description builder for the error.
   * @param state the visitor state (used for e.g. suppression finding).
   * @return the error description
   */
  Description createErrorDescription(
      ErrorMessage errorMessage, Description.Builder descriptionBuilder, VisitorState state) {
    Tree enclosingSuppressTree = suppressibleNode(state.getPath());
    return createErrorDescription(errorMessage, enclosingSuppressTree, descriptionBuilder, state);
  }

  /**
   * create an error description for a nullability warning
   *
   * @param errorMessage the error message object.
   * @param suggestTree the location at which a fix suggestion should be made
   * @param descriptionBuilder the description builder for the error.
   * @param state the visitor state (used for e.g. suppression finding).
   * @return the error description
   */
  public Description createErrorDescription(
      ErrorMessage errorMessage,
      @Nullable Tree suggestTree,
      Description.Builder descriptionBuilder,
      VisitorState state) {
    Description.Builder builder = descriptionBuilder.setMessage(errorMessage.message);
    String checkName = CORE_CHECK_NAME;
    if (errorMessage.messageType.equals(GET_ON_EMPTY_OPTIONAL)) {
      checkName = OPTIONAL_CHECK_NAME;
    } else if (errorMessage.messageType.equals(FIELD_NO_INIT)
        || errorMessage.messageType.equals(METHOD_NO_INIT)
        || errorMessage.messageType.equals(NONNULL_FIELD_READ_BEFORE_INIT)) {
      checkName = INITIALIZATION_CHECK_NAME;
    }

    // Mildly expensive state.getPath() traversal, occurs only once per potentially
    // reported error.
    if (hasPathSuppression(state.getPath(), checkName)) {
      return Description.NO_MATCH;
    }

    if (config.suggestSuppressions() && suggestTree != null) {
      builder = addSuggestedSuppression(errorMessage, suggestTree, builder);
    }
    // #letbuildersbuild
    return builder.build();
  }

  private static boolean canHaveSuppressWarningsAnnotation(Tree tree) {
    return tree instanceof MethodTree
        || (tree instanceof ClassTree && ((ClassTree) tree).getSimpleName().length() != 0)
        || tree instanceof VariableTree;
  }

  /**
   * Find out if a particular subchecker (e.g. NullAway.Optional) is being suppressed in a given
   * path.
   *
   * <p>This requires a tree path traversal, which is expensive, but we only do this when we would
   * otherwise report an error, which means this won't happen for most nodes/files.
   *
   * @param treePath The path with the error location as the leaf.
   * @param subcheckerName The string to check for inside @SuppressWarnings
   * @return Whether the subchecker is being suppressed at treePath.
   */
  private boolean hasPathSuppression(TreePath treePath, String subcheckerName) {
    return StreamSupport.stream(treePath.spliterator(), false)
        .filter(ErrorBuilder::canHaveSuppressWarningsAnnotation)
        .map(tree -> ASTHelpers.getSymbol(tree))
        .filter(symbol -> symbol != null)
        .anyMatch(
            symbol ->
                symbolHasSuppressWarningsAnnotation(symbol, subcheckerName)
                    || symbolIsExcludedClassSymbol(symbol));
  }

  private Description.Builder addSuggestedSuppression(
      ErrorMessage errorMessage, Tree suggestTree, Description.Builder builder) {
    switch (errorMessage.messageType) {
      case DEREFERENCE_NULLABLE:
      case RETURN_NULLABLE:
      case PASS_NULLABLE:
      case ASSIGN_FIELD_NULLABLE:
      case SWITCH_EXPRESSION_NULLABLE:
        if (config.getCastToNonNullMethod() != null) {
          builder = addCastToNonNullFix(suggestTree, builder);
        } else {
          builder = addSuppressWarningsFix(suggestTree, builder, suppressionName);
        }
        break;
      case CAST_TO_NONNULL_ARG_NONNULL:
        builder = removeCastToNonNullFix(suggestTree, builder);
        break;
      case WRONG_OVERRIDE_RETURN:
        builder = addSuppressWarningsFix(suggestTree, builder, suppressionName);
        break;
      case WRONG_OVERRIDE_PARAM:
        builder = addSuppressWarningsFix(suggestTree, builder, suppressionName);
        break;
      case METHOD_NO_INIT:
      case FIELD_NO_INIT:
        builder = addSuppressWarningsFix(suggestTree, builder, INITIALIZATION_CHECK_NAME);
        break;
      case ANNOTATION_VALUE_INVALID:
        break;
      default:
        builder = addSuppressWarningsFix(suggestTree, builder, suppressionName);
    }
    return builder;
  }

  /**
   * create an error description for a generalized @Nullable value to @NonNull location assignment.
   *
   * <p>This includes: field assignments, method arguments and method returns
   *
   * @param errorMessage the error message object.
   * @param suggestTreeIfCastToNonNull the location at which a fix suggestion should be made if a
   *     castToNonNull method is available (usually the expression to cast)
   * @param descriptionBuilder the description builder for the error.
   * @param state the visitor state for the location which triggered the error (i.e. for suppression
   *     finding)
   * @return the error description.
   */
  Description createErrorDescriptionForNullAssignment(
      ErrorMessage errorMessage,
      @Nullable Tree suggestTreeIfCastToNonNull,
      Description.Builder descriptionBuilder,
      VisitorState state) {
    if (config.getCastToNonNullMethod() != null) {
      return createErrorDescription(
          errorMessage, suggestTreeIfCastToNonNull, descriptionBuilder, state);
    } else {
      return createErrorDescription(
          errorMessage, suppressibleNode(state.getPath()), descriptionBuilder, state);
    }
  }

  Description.Builder addSuppressWarningsFix(
      Tree suggestTree, Description.Builder builder, String suppressionName) {
    SuppressWarnings extantSuppressWarnings = null;
    Symbol treeSymbol = ASTHelpers.getSymbol(suggestTree);
    if (treeSymbol != null) {
      extantSuppressWarnings = treeSymbol.getAnnotation(SuppressWarnings.class);
    }
    SuggestedFix fix;
    if (extantSuppressWarnings == null) {
      fix =
          SuggestedFix.prefixWith(
              suggestTree,
              "@SuppressWarnings(\""
                  + suppressionName
                  + "\") "
                  + config.getAutofixSuppressionComment());
    } else {
      // need to update the existing list of warnings
      final List<String> suppressions = Lists.newArrayList(extantSuppressWarnings.value());
      suppressions.add(suppressionName);
      // find the existing annotation, so we can replace it
      final ModifiersTree modifiers =
          (suggestTree instanceof MethodTree)
              ? ((MethodTree) suggestTree).getModifiers()
              : ((VariableTree) suggestTree).getModifiers();
      final List<? extends AnnotationTree> annotations = modifiers.getAnnotations();
      // noinspection ConstantConditions
      com.google.common.base.Optional<? extends AnnotationTree> suppressWarningsAnnot =
          Iterables.tryFind(
              annotations,
              annot -> annot.getAnnotationType().toString().endsWith("SuppressWarnings"));
      if (!suppressWarningsAnnot.isPresent()) {
        throw new AssertionError("something went horribly wrong");
      }
      final String replacement =
          "@SuppressWarnings({"
              + Joiner.on(',').join(Iterables.transform(suppressions, s -> '"' + s + '"'))
              + "}) "
              + config.getAutofixSuppressionComment();
      fix = SuggestedFix.replace(suppressWarningsAnnot.get(), replacement);
    }
    return builder.addFix(fix);
  }

  /**
   * Adapted from {@link com.google.errorprone.fixes.SuggestedFixes}.
   *
   * <p>TODO: actually use {@link
   * com.google.errorprone.fixes.SuggestedFixes#addSuppressWarnings(VisitorState, String)} instead
   */
  @Nullable
  private Tree suppressibleNode(@Nullable TreePath path) {
    if (path == null) {
      return null;
    }
    return StreamSupport.stream(path.spliterator(), false)
        .filter(ErrorBuilder::canHaveSuppressWarningsAnnotation)
        .findFirst()
        .orElse(null);
  }

  private Description.Builder addCastToNonNullFix(Tree suggestTree, Description.Builder builder) {
    final String fullMethodName = config.getCastToNonNullMethod();
    assert fullMethodName != null;
    // Add a call to castToNonNull around suggestTree:
    final String[] parts = fullMethodName.split("\\.");
    final String shortMethodName = parts[parts.length - 1];
    final String replacement = shortMethodName + "(" + suggestTree.toString() + ")";
    final SuggestedFix fix =
        SuggestedFix.builder()
            .replace(suggestTree, replacement)
            .addStaticImport(fullMethodName) // ensure castToNonNull static import
            .build();
    return builder.addFix(fix);
  }

  private Description.Builder removeCastToNonNullFix(
      Tree suggestTree, Description.Builder builder) {
    assert suggestTree.getKind() == Tree.Kind.METHOD_INVOCATION;
    final MethodInvocationTree invTree = (MethodInvocationTree) suggestTree;
    final Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(invTree);
    final String qualifiedName =
        ASTHelpers.enclosingClass(methodSymbol) + "." + methodSymbol.getSimpleName().toString();
    if (!qualifiedName.equals(config.getCastToNonNullMethod())) {
      throw new RuntimeException("suggestTree should point to the castToNonNull invocation.");
    }
    // Remove the call to castToNonNull:
    final SuggestedFix fix =
        SuggestedFix.builder()
            .replace(suggestTree, invTree.getArguments().get(0).toString())
            .build();
    return builder.addFix(fix);
  }

  void reportInitializerError(
      Symbol.MethodSymbol methodSymbol,
      String message,
      VisitorState state,
      Description.Builder descriptionBuilder) {
    // Check needed here, despite check in hasPathSuppression because initialization
    // checking happens at the class-level (meaning state.getPath() might not include the
    // method itself).
    if (symbolHasSuppressWarningsAnnotation(methodSymbol, INITIALIZATION_CHECK_NAME)) {
      return;
    }
    Tree methodTree = getTreesInstance(state).getTree(methodSymbol);
    state.reportMatch(
        createErrorDescription(
            new ErrorMessage(METHOD_NO_INIT, message), methodTree, descriptionBuilder, state));
  }

  boolean symbolHasSuppressWarningsAnnotation(Symbol symbol, String suppression) {
    SuppressWarnings annotation = symbol.getAnnotation(SuppressWarnings.class);
    if (annotation != null) {
      for (String s : annotation.value()) {
        // we need to check for standard suppression here also since we may report initialization
        // errors outside the normal ErrorProne match* methods
        if (s.equals(suppression) || allNames.stream().anyMatch(s::equals)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean symbolIsExcludedClassSymbol(Symbol symbol) {
    if (symbol instanceof Symbol.ClassSymbol) {
      ImmutableSet<String> excludedClassAnnotations = config.getExcludedClassAnnotations();
      return ((Symbol.ClassSymbol) symbol)
          .getAnnotationMirrors()
          .stream()
          .map(anno -> anno.getAnnotationType().toString())
          .anyMatch(excludedClassAnnotations::contains);
    }
    return false;
  }

  static int getLineNumForElement(Element uninitField, VisitorState state) {
    Tree tree = getTreesInstance(state).getTree(uninitField);
    if (tree == null) {
      throw new RuntimeException(
          "When getting the line number for uninitialized field, can't get the tree from the element.");
    }
    DiagnosticPosition position =
        (DiagnosticPosition) tree; // Expect Tree to be JCTree and thus implement DiagnosticPosition
    TreePath path = state.getPath();
    JCCompilationUnit compilation = (JCCompilationUnit) path.getCompilationUnit();
    JavaFileObject file = compilation.getSourceFile();
    DiagnosticSource source = new DiagnosticSource(file, null);
    return source.getLineNumber(position.getStartPosition());
  }

  /**
   * Generate the message for uninitialized fields, including the line number for fields.
   *
   * @param uninitFields the set of uninitialized fields as the type of Element.
   * @param state the VisitorState object.
   * @return the error message for uninitialized fields with line numbers.
   */
  static String errMsgForInitializer(Set<Element> uninitFields, VisitorState state) {
    StringBuilder message = new StringBuilder("initializer method does not guarantee @NonNull ");
    Element uninitField;
    if (uninitFields.size() == 1) {
      uninitField = uninitFields.iterator().next();
      message.append("field ");
      message.append(uninitField.toString());
      message.append(" (line ");
      message.append(getLineNumForElement(uninitField, state));
      message.append(") is initialized");
    } else {
      message.append("fields ");
      Iterator<Element> it = uninitFields.iterator();
      while (it.hasNext()) {
        uninitField = it.next();
        message.append(
            uninitField.toString() + " (line " + getLineNumForElement(uninitField, state) + ")");
        if (it.hasNext()) {
          message.append(", ");
        } else {
          message.append(" are initialized");
        }
      }
    }
    message.append(
        " along all control-flow paths (remember to check for exceptions or early returns).");
    return message.toString();
  }

  void reportInitErrorOnField(Symbol symbol, VisitorState state, Description.Builder builder) {
    // Check needed here, despite check in hasPathSuppression because initialization
    // checking happens at the class-level (meaning state.getPath() might not include the
    // field itself).
    if (symbolHasSuppressWarningsAnnotation(symbol, INITIALIZATION_CHECK_NAME)) {
      return;
    }
    Tree tree = getTreesInstance(state).getTree(symbol);

    String fieldName = symbol.toString();

    if (symbol.enclClass().getNestingKind().isNested()) {
      String flatName = symbol.enclClass().flatName().toString();
      int index = flatName.lastIndexOf(".") + 1;
      fieldName = flatName.substring(index) + "." + fieldName;
    }

    if (symbol.isStatic()) {
      state.reportMatch(
          createErrorDescription(
              new ErrorMessage(
                  FIELD_NO_INIT, "@NonNull static field " + fieldName + " not initialized"),
              tree,
              builder,
              state));
    } else {
      state.reportMatch(
          createErrorDescription(
              new ErrorMessage(FIELD_NO_INIT, "@NonNull field " + fieldName + " not initialized"),
              tree,
              builder,
              state));
    }
  }
}
