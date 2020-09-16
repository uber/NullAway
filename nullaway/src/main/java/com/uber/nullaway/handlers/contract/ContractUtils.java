package com.uber.nullaway.handlers.contract;

import static com.google.errorprone.BugCheckerInfo.buildDescriptionFromChecker;

import com.google.errorprone.VisitorState;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import java.util.Map;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;

/** An utility class for {@link ContractHandler} and {@link ContractCheckHandler}. */
class ContractUtils {

  /**
   * Retrieve the string value inside an @Contract annotation without statically depending on the
   * type.
   *
   * @param sym A method which has an @Contract annotation.
   * @return The string value spec inside the annotation.
   */
  static @Nullable String getContractFromAnnotation(Symbol.MethodSymbol sym) {
    for (AnnotationMirror annotation : sym.getAnnotationMirrors()) {
      Element element = annotation.getAnnotationType().asElement();
      assert element.getKind().equals(ElementKind.ANNOTATION_TYPE);
      if (((TypeElement) element)
          .getQualifiedName()
          .contentEquals("org.jetbrains.annotations.Contract")) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> e :
            annotation.getElementValues().entrySet()) {
          if (e.getKey().getSimpleName().contentEquals("value")) {
            String value = e.getValue().toString();
            if (value.startsWith("\"") && value.endsWith("\"")) {
              value = value.substring(1, value.length() - 1);
            }
            return value;
          }
        }
      }
    }
    return null;
  }

  static void reportMatch(
      Tree errorLocTree,
      String message,
      @Nullable NullAway analysis,
      @Nullable VisitorState state) {
    assert analysis != null && state != null;
    state.reportMatch(
        analysis
            .getErrorBuilder()
            .createErrorDescription(
                new ErrorMessage(ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, message),
                errorLocTree,
                buildDescriptionFromChecker(errorLocTree, analysis),
                state));
  }

  static String getConsequent(
      String clause,
      Tree tree,
      @Nullable NullAway analysis,
      @Nullable VisitorState state,
      Symbol callee) {

    assert analysis != null && state != null;

    String[] parts = clause.split("->");
    if (parts.length != 2) {
      reportMatch(
          tree,
          "Invalid @Contract annotation detected for method "
              + callee
              + ". It contains the following uparseable clause: "
              + clause
              + "(see https://www.jetbrains.com/help/idea/contract-annotations.html).",
          analysis,
          state);
    }

    String consequent = parts[1].trim();
    return consequent;
  }

  static String[] getAntecedent(
      String clause,
      Tree tree,
      @Nullable NullAway analysis,
      @Nullable VisitorState state,
      Symbol callee,
      int numOfArguments) {

    assert analysis != null && state != null;

    String[] parts = clause.split("->");

    String[] antecedent = parts[0].split(",");

    if (antecedent.length != numOfArguments) {
      reportMatch(
          tree,
          "Invalid @Contract annotation detected for method "
              + callee
              + ". It contains the following uparseable clause: "
              + clause
              + " (incorrect number of arguments in the clause's antecedent ["
              + antecedent.length
              + "], should be the same as the number of "
              + "arguments in for the method ["
              + numOfArguments
              + "]).",
          analysis,
          state);
    }
    return antecedent;
  }
}
