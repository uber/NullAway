package com.uber.nullaway.handlers;

import com.google.common.base.Preconditions;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.dataflow.NullnessStore;
import javax.annotation.Nullable;
import org.checkerframework.nullaway.dataflow.analysis.TransferInput;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

public class PreconditionsHandler extends BaseNoOpHandler {
  // ToDo: Similar logic could be used to handle more general forms of Assertions in
  // AssertionHandler

  private static final String PRECONDITIONS_CLASS_NAME = "com.google.common.base.Preconditions";
  private static final String CHECK_ARGUMENT_METHOD_NAME = "checkArgument";

  @Nullable private Name preconditionsClass;
  @Nullable private Name checkArgumentMethod;

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      Context context,
      AccessPath.AccessPathContext apContext,
      TransferInput<Nullness, NullnessStore> input,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(node.getTree());
    if (preconditionsClass == null) {
      preconditionsClass = callee.name.table.fromString(PRECONDITIONS_CLASS_NAME);
      checkArgumentMethod = callee.name.table.fromString(CHECK_ARGUMENT_METHOD_NAME);
    }
    Preconditions.checkNotNull(checkArgumentMethod);
    if (callee.enclClass().getQualifiedName().equals(preconditionsClass)
        && callee.name.equals(checkArgumentMethod)) {
      // Debug
      System.err.println("Found Preconditions.checkArgument check at node: " + node.toString());
      Nullness nullness = input.getValueOfSubNode(node.getArgument(0));
      System.err.println(
          "Arg input is: " + (nullness == null ? "<unavailable>" : nullness.toString()));
      NullnessStore inputThenStore = input.getThenStore();
      System.err.println("inputThenStore: " + inputThenStore.toString());
      NullnessStore inputElseStore = input.getElseStore();
      System.err.println("inputElseStore: " + inputElseStore.toString());
      NullnessStore inputRegularStore = input.getRegularStore();
      System.err.println("inputRegularStore: " + inputRegularStore.toString());
      // Update based on NullnessStore for first arg
      for (AccessPath accessPath : inputThenStore.getAccessPathsWithValue(Nullness.NONNULL)) {
        bothUpdates.set(accessPath, Nullness.NONNULL);
      }
    }
    return NullnessHint.UNKNOWN;
  }
}
