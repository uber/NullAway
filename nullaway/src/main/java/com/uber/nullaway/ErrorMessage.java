package com.uber.nullaway;

import static com.uber.nullaway.NullAway.INITIALIZATION_CHECK_NAME;

import com.google.common.base.Joiner;
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
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;

/** Contains error message string to be displayed and the message type from {@link MessageTypes}. */
public class ErrorMessage {

  static Config config;
  static NullAway nullAway;
  MessageTypes messageType;
  String message;

  public ErrorMessage(MessageTypes messageType, String message) {
    this.messageType = messageType;
    this.message = message;
  }

  public void updateErrorMessage(MessageTypes messageType, String message) {
    this.messageType = messageType;
    this.message = message;
  }

  /**
   * create an error description for a nullability warning
   *
   * @param errorMessage the error message object.
   * @param errorLocTree the location of the error
   * @param path the TreePath to the error location. Used to compute a suggested fix at the
   *     enclosing method for the error location
   * @return the error description
   */
  static Description createErrorDescription(
      ErrorMessage errorMessage, Tree errorLocTree, TreePath path) {
    Tree enclosingSuppressTree = suppressibleNode(path);
    return createErrorDescription(errorMessage, errorLocTree, enclosingSuppressTree);
  }

  /**
   * create an error description for a nullability warning
   *
   * @param errorMessage the error message object.
   * @param errorLocTree the location of the error
   * @param suggestTree the location at which a fix suggestion should be made
   * @return the error description
   */
  public static Description createErrorDescription(
      ErrorMessage errorMessage, Tree errorLocTree, @Nullable Tree suggestTree) {
    Description.Builder builder =
        nullAway.buildDescription(errorLocTree).setMessage(errorMessage.message);
    if (config.suggestSuppressions() && suggestTree != null) {
      switch (errorMessage.messageType) {
        case DEREFERENCE_NULLABLE:
        case RETURN_NULLABLE:
        case PASS_NULLABLE:
        case ASSIGN_FIELD_NULLABLE:
          if (config.getCastToNonNullMethod() != null) {
            builder = addCastToNonNullFix(suggestTree, builder);
          } else {
            builder = addSuppressWarningsFix(suggestTree, builder, nullAway.canonicalName());
          }
          break;
        case CAST_TO_NONNULL_ARG_NONNULL:
          builder = removeCastToNonNullFix(suggestTree, builder);
          break;
        case WRONG_OVERRIDE_RETURN:
          builder = addSuppressWarningsFix(suggestTree, builder, nullAway.canonicalName());
          break;
        case WRONG_OVERRIDE_PARAM:
          builder = addSuppressWarningsFix(suggestTree, builder, nullAway.canonicalName());
          break;
        case METHOD_NO_INIT:
        case FIELD_NO_INIT:
          builder = addSuppressWarningsFix(suggestTree, builder, INITIALIZATION_CHECK_NAME);
          break;
        case ANNOTATION_VALUE_INVALID:
          break;
        default:
          builder = addSuppressWarningsFix(suggestTree, builder, nullAway.canonicalName());
      }
    }
    // #letbuildersbuild
    return builder.build();
  }

  /**
   * create an error description for a generalized @Nullable value to @NonNull location assignment.
   *
   * <p>This includes: field assignments, method arguments and method returns
   *
   * @param errorMessage the error message object.
   * @param errorLocTree the location of the error
   * @param suggestTreeIfCastToNonNull the location at which a fix suggestion should be made if a
   *     castToNonNull method is available (usually the expression to cast)
   * @param suggestTreePathIfSuppression the location at which a fix suggestion should be made if a
   *     castToNonNull method is not available (usually the enclosing method, or any place
   *     where @SuppressWarnings can be added).
   * @return the error description.
   */
  static Description createErrorDescriptionForNullAssignment(
      ErrorMessage errorMessage,
      Tree errorLocTree,
      @Nullable Tree suggestTreeIfCastToNonNull,
      @Nullable TreePath suggestTreePathIfSuppression) {
    final Tree enclosingSuppressTree = suppressibleNode(suggestTreePathIfSuppression);
    if (config.getCastToNonNullMethod() != null) {
      return createErrorDescription(errorMessage, errorLocTree, suggestTreeIfCastToNonNull);
    } else {
      return createErrorDescription(errorMessage, errorLocTree, enclosingSuppressTree);
    }
  }

  static Description.Builder addSuppressWarningsFix(
      Tree suggestTree, Description.Builder builder, String checkerName) {
    SuppressWarnings extantSuppressWarnings =
        ASTHelpers.getAnnotation(suggestTree, SuppressWarnings.class);
    SuggestedFix fix;
    if (extantSuppressWarnings == null) {
      fix =
          SuggestedFix.prefixWith(
              suggestTree,
              "@SuppressWarnings(\""
                  + checkerName
                  + "\") "
                  + config.getAutofixSuppressionComment());
    } else {
      // need to update the existing list of warnings
      final List<String> suppressions = Lists.newArrayList(extantSuppressWarnings.value());
      suppressions.add(checkerName);
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
  private static Tree suppressibleNode(@Nullable TreePath path) {
    if (path == null) {
      return null;
    }
    return StreamSupport.stream(path.spliterator(), false)
        .filter(
            tree ->
                tree instanceof MethodTree
                    || (tree instanceof ClassTree
                        && ((ClassTree) tree).getSimpleName().length() != 0)
                    || tree instanceof VariableTree)
        .findFirst()
        .orElse(null);
  }

  private static Description.Builder addCastToNonNullFix(
      Tree suggestTree, Description.Builder builder) {
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

  private static Description.Builder removeCastToNonNullFix(
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

  static void reportInitializerError(
      Symbol.MethodSymbol methodSymbol, String message, VisitorState state) {
    if (symbolHasSuppressInitializationWarningsAnnotation(methodSymbol)) {
      return;
    }
    Tree methodTree = nullAway.getTreesInstance(state).getTree(methodSymbol);
    state.reportMatch(
        createErrorDescription(
            new ErrorMessage(MessageTypes.METHOD_NO_INIT, message), methodTree, methodTree));
  }

  static boolean symbolHasSuppressInitializationWarningsAnnotation(Symbol symbol) {
    SuppressWarnings annotation = symbol.getAnnotation(SuppressWarnings.class);
    if (annotation != null) {
      for (String s : annotation.value()) {
        // we need to check for standard suppressions here also since we may report initialization
        // errors outside the normal ErrorProne match* methods
        if (s.equals(INITIALIZATION_CHECK_NAME)
            || nullAway.allNames().stream().anyMatch(s::equals)) {
          return true;
        }
      }
    }
    return false;
  }

  static String errMsgForInitializer(Set<Element> uninitFields) {
    String message = "initializer method does not guarantee @NonNull ";
    if (uninitFields.size() == 1) {
      message += "field " + uninitFields.iterator().next().toString() + " is initialized";
    } else {
      message += "fields " + Joiner.on(", ").join(uninitFields) + " are initialized";
    }
    message += " along all control-flow paths (remember to check for exceptions or early returns).";
    return message;
  }

  static void reportInitErrorOnField(Symbol symbol, VisitorState state) {
    if (symbolHasSuppressInitializationWarningsAnnotation(symbol)) {
      return;
    }
    Tree tree = nullAway.getTreesInstance(state).getTree(symbol);
    if (symbol.isStatic()) {
      state.reportMatch(
          createErrorDescription(
              new ErrorMessage(
                  MessageTypes.FIELD_NO_INIT,
                  "@NonNull static field " + symbol + " not initialized"),
              tree,
              tree));
    } else {
      state.reportMatch(
          createErrorDescription(
              new ErrorMessage(
                  MessageTypes.FIELD_NO_INIT, "@NonNull field " + symbol + " not initialized"),
              tree,
              tree));
    }
  }

  public enum MessageTypes {
    DEREFERENCE_NULLABLE,
    RETURN_NULLABLE,
    PASS_NULLABLE,
    ASSIGN_FIELD_NULLABLE,
    WRONG_OVERRIDE_RETURN,
    WRONG_OVERRIDE_PARAM,
    METHOD_NO_INIT,
    FIELD_NO_INIT,
    UNBOX_NULLABLE,
    NONNULL_FIELD_READ_BEFORE_INIT,
    ANNOTATION_VALUE_INVALID,
    CAST_TO_NONNULL_ARG_NONNULL,
    GET_ON_EMPTY_OPTIONAL;
  }
}
