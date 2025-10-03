package com.uber.nullaway.handlers.contract;

import com.google.errorprone.VisitorState;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import org.checkerframework.nullaway.javacutil.AnnotationUtils;
import org.jspecify.annotations.Nullable;

/** A utility class for {@link ContractHandler} and {@link ContractCheckHandler}. */
public class ContractUtils {

  private static final String[] EMPTY_STRING_ARRAY = new String[0];

  /**
   * Returns a set of field names excluding their receivers (e.g. "this.a" will be "a")
   *
   * @param fieldNames A set of raw class field names.
   * @return A set of trimmed field names.
   */
  public static Set<String> trimReceivers(Set<String> fieldNames) {
    return fieldNames.stream()
        .map(input -> input.substring(input.lastIndexOf(".") + 1))
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
      if (shouldReportInvalidContract(tree)) {
        String message =
            "Invalid @Contract annotation detected for method "
                + callee
                + ". It contains the following unparseable clause: "
                + clause
                + " (see https://www.jetbrains.com/help/idea/contract-annotations.html).";
        state.reportMatch(
            analysis
                .getErrorBuilder()
                .createErrorDescription(
                    new ErrorMessage(ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, message),
                    tree,
                    analysis.buildDescription(tree),
                    state,
                    null));
      }
      return "";
    }
    return parts[1].trim();
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

    String[] antecedent = parts[0].trim().isEmpty() ? new String[0] : parts[0].split(",");

    if (antecedent.length != numOfArguments && shouldReportInvalidContract(tree)) {
      String message =
          "Invalid @Contract annotation detected for method "
              + callee
              + ". It contains the following unparseable clause: "
              + clause
              + " (incorrect number of arguments in the clause's antecedent ["
              + antecedent.length
              + "], should be the same as the number of "
              + "arguments for the method ["
              + numOfArguments
              + "]).";
      state.reportMatch(
          analysis
              .getErrorBuilder()
              .createErrorDescription(
                  new ErrorMessage(ErrorMessage.MessageTypes.ANNOTATION_VALUE_INVALID, message),
                  tree,
                  analysis.buildDescription(tree),
                  state,
                  null));
    }
    return antecedent;
  }

  /**
   * Returns the value of a Contract annotation if present on the method.
   *
   * @param methodSymbol the method to check for a Contract annotation
   * @param config the NullAway config
   * @return the value of a Contract annotation if present, or {@code null} if not present.
   */
  static @Nullable String getContractString(Symbol.MethodSymbol methodSymbol, Config config) {
    for (AnnotationMirror annotation : methodSymbol.getAnnotationMirrors()) {
      String name = AnnotationUtils.annotationName(annotation);
      if (hasSimpleNameContract(name) || config.isContractAnnotation(name)) {
        return NullabilityUtil.getAnnotationValue(methodSymbol, name);
      }
    }
    return null;
  }

  /**
   * Checks if the input string, which is a qualified name, has simple name "Contract".
   *
   * @param input the qualified name to check
   * @return true if the simple name is "Contract", false otherwise
   */
  private static boolean hasSimpleNameContract(String input) {
    int lastDot = input.lastIndexOf('.');
    String simpleName;
    if (lastDot == -1) {
      simpleName = input;
    } else {
      simpleName = input.substring(lastDot + 1);
    }
    return simpleName.equals("Contract");
  }

  static String[] getContractClauses(Symbol.MethodSymbol callee, Config config) {
    // Check to see if this method has a @Contract annotation
    String contractString = getContractString(callee, config);
    if (contractString != null) {
      String trimmedContractString = contractString.trim();
      if (!trimmedContractString.isEmpty()) {
        return trimmedContractString.split(";");
      }
    }
    return EMPTY_STRING_ARRAY;
  }

  /**
   * Determines whether we should report an invalid contract for the tree. Right now, we only report
   * invalid contracts for method declarations ({@link MethodTree}s).
   *
   * @param tree the AST node to check
   * @return true if we should report an invalid contract for this tree, false otherwise
   */
  private static boolean shouldReportInvalidContract(Tree tree) {
    return tree instanceof MethodTree;
  }
}
