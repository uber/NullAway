package com.uber.nullaway.handlers.contract;

import static com.google.errorprone.BugCheckerInfo.buildDescriptionFromChecker;

import com.google.common.base.Function;
import com.google.errorprone.VisitorState;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import org.checkerframework.javacutil.AnnotationUtils;

/** An utility class for {@link ContractHandler} and {@link ContractCheckHandler}. */
public class ContractUtils {

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

  /**
   * Reports contract issue with appropriate message and error location in the AST information.
   *
   * @param errorLocTree The AST node for the error location.
   * @param message The error message.
   * @param analysis A reference to the running NullAway analysis.
   * @param state The current visitor state.
   */
  static void reportMatchForContractIssue(
      Tree errorLocTree, String message, NullAway analysis, VisitorState state) {

    state.reportMatch(
        analysis
            .getErrorBuilder()
            .createErrorDescription(
                new ErrorMessage(ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, message),
                errorLocTree,
                buildDescriptionFromChecker(errorLocTree, analysis),
                state));
  }

  /**
   * Retrieve the string value inside the corresponding (e.g. @EnsuresNonNull/@RequiresNonNull
   * depending on the value of {@code ANNOT_NAME}) annotation without statically depending on the
   * type.
   *
   * @param methodSymbol A method.
   * @return The set of all values inside the annotation.
   */
  public static @Nullable Set<String> getFieldNamesFromAnnotation(
      Symbol.MethodSymbol methodSymbol, String annotName) {
    AnnotationMirror annot = null;
    for (AnnotationMirror annotationMirror : methodSymbol.getAnnotationMirrors()) {
      if (AnnotationUtils.annotationName(annotationMirror).endsWith(annotName)) {
        annot = annotationMirror;
        break;
      }
    }
    if (annot == null) {
      return null;
    }
    return new HashSet<>(AnnotationUtils.getElementValueArray(annot, "value", String.class, true));
  }

  /**
   * Returns a set of field names excluding their receivers (e.g. "this.a" will be "a")
   *
   * @param fieldNames A set of raw class field names.
   * @return A set of trimmed field names.
   */
  public static Set<String> trimReceivers(Set<String> fieldNames) {
    return fieldNames
        .stream()
        .map((Function<String, String>) input -> input.substring(input.lastIndexOf(".") + 1))
        .collect(Collectors.toSet());
  }

  /**
   * Parses the contract clause and returns the consequent in the contract.
   *
   * @param clause The contract clause.
   * @param tree The AST Node for contract.
   * @param analysis A reference to the running NullAway analysis.
   * @param state The current visitor state.
   * @param callee Symbol for callee.
   * @return consequent in the contract.
   */
  static String getConsequent(
      String clause, Tree tree, NullAway analysis, VisitorState state, Symbol callee) {

    String[] parts = clause.split("->");
    if (parts.length != 2) {
      reportMatchForContractIssue(
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

  /**
   * Parses the contract clause and returns the antecedents in the contract.
   *
   * @param clause The contract clause.
   * @param tree The AST Node for contract.
   * @param analysis A reference to the running NullAway analysis.
   * @param state The current visitor state.
   * @param callee Symbol for callee.
   * @param numOfArguments Number of arguments in the method associated with the contract.
   * @return antecedents in the contract.
   */
  static String[] getAntecedent(
      String clause,
      Tree tree,
      NullAway analysis,
      VisitorState state,
      Symbol callee,
      int numOfArguments) {

    String[] parts = clause.split("->");

    String[] antecedent = parts[0].split(",");

    if (antecedent.length != numOfArguments) {
      reportMatchForContractIssue(
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
