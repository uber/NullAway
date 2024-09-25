package com.uber.nullaway.handlers.contract.fieldcontract;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.NullabilityUtil.getAnnotationValueArray;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

public class FieldContractUtils {

  public static void validateOverridingRules(
      String annotName,
      Set<String> overridingFieldNames,
      NullAway analysis,
      VisitorState state,
      MethodTree tree,
      Symbol.MethodSymbol overriddenMethod) {
    Set<String> overriddenFieldNames = getAnnotationValueArray(overriddenMethod, annotName, false);
    if (overriddenFieldNames == null) {
      return;
    }
    if (overridingFieldNames == null) {
      overridingFieldNames = Collections.emptySet();
    }
    if (overridingFieldNames.containsAll(overriddenFieldNames)) {
      return;
    }
    overriddenFieldNames.removeAll(overridingFieldNames);

    StringBuilder errorMessage = new StringBuilder();
    errorMessage
        .append(
            "postcondition inheritance is violated, this method must guarantee that all fields written in the @")
        .append(annotName)
        .append(" annotation of overridden method ")
        .append(castToNonNull(ASTHelpers.enclosingClass(overriddenMethod)).getSimpleName())
        .append(".")
        .append(overriddenMethod.getSimpleName())
        .append(" are @NonNull at exit point as well. Fields [");
    Iterator<String> iterator = overriddenFieldNames.iterator();
    while (iterator.hasNext()) {
      errorMessage.append(iterator.next());
      if (iterator.hasNext()) {
        errorMessage.append(", ");
      }
    }
    errorMessage
        .append("] must explicitly appear as parameters at this method @")
        .append(annotName)
        .append(" annotation");
    state.reportMatch(
        analysis
            .getErrorBuilder()
            .createErrorDescription(
                new ErrorMessage(
                    ErrorMessage.MessageTypes.WRONG_OVERRIDE_POSTCONDITION,
                    errorMessage.toString()),
                tree,
                analysis.buildDescription(tree),
                state,
                null));
  }
}
