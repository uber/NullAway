package com.uber.nullaway.handlers.contract;

import static com.google.errorprone.BugCheckerInfo.buildDescriptionFromChecker;

import com.google.errorprone.VisitorState;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import org.checkerframework.javacutil.AnnotationUtils;

/** An utility class for {@link ContractHandler} and {@link ContractCheckHandler}. */
class ContractUtils {

  /**
   * Retrieve the string value inside an annotation without statically depending on the type.
   *
   * @param annotName Annotation name to retrieve it's value.
   * @param methodSymbol A method which has an @Contract annotation.
   * @return The string value spec inside the annotation.
   */
  public static @Nullable String getAnnotationValue(
      Symbol.MethodSymbol methodSymbol, String annotName) {
    AnnotationMirror annot =
        AnnotationUtils.getAnnotationByName(methodSymbol.getAnnotationMirrors(), annotName);
    if (annot == null) {
      return null;
    }
    return AnnotationUtils.getElementValue(annot, "value", String.class, true);
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
      String message =
          "Invalid @Contract annotation detected for method "
              + callee
              + ". It contains the following uparseable clause: "
              + clause
              + "(see https://www.jetbrains.com/help/idea/contract-annotations.html).";
      state.reportMatch(
          analysis
              .getErrorBuilder()
              .createErrorDescription(
                  new ErrorMessage(ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, message),
                  tree,
                  buildDescriptionFromChecker(tree, analysis),
                  state));
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
      String message =
          "Invalid @Contract annotation detected for method "
              + callee
              + ". It contains the following uparseable clause: "
              + clause
              + " (incorrect number of arguments in the clause's antecedent ["
              + antecedent.length
              + "], should be the same as the number of "
              + "arguments in for the method ["
              + numOfArguments
              + "]).";
      state.reportMatch(
          analysis
              .getErrorBuilder()
              .createErrorDescription(
                  new ErrorMessage(ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, message),
                  tree,
                  buildDescriptionFromChecker(tree, analysis),
                  state));
    }
    return antecedent;
  }
}
