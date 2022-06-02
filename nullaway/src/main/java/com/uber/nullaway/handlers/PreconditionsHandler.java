package com.uber.nullaway.handlers;

import com.google.common.base.Preconditions;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.dataflow.NullnessStore;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.checkerframework.nullaway.dataflow.analysis.TransferInput;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;
import org.checkerframework.nullaway.dataflow.cfg.node.StringLiteralNode;

public class PreconditionsHandler extends BaseNoOpHandler {
  // ToDo: Similar logic could be used to handle more general forms of Assertions in
  // AssertionHandler

  private static final String PRECONDITIONS_CLASS_NAME = "com.google.common.base.Preconditions";
  private static final String CHECK_ARGUMENT_METHOD_NAME = "checkArgument";

  @Nullable private Name preconditionsClass;
  @Nullable private Name checkArgumentMethod;

  private Map<Node, NullnessStore> argumentToInputNullnessStoreMap =
      new HashMap<Node, NullnessStore>();

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
      if (callee.getParameters().size() == 1) {
        // Debug
        System.err.println("Found Preconditions.checkArgument check at node: " + node.toString());
        NullnessStore inputThenStore = input.getThenStore();
        System.err.println("inputThenStore: " + inputThenStore.toString());
        // Update based on NullnessStore for first arg
        for (AccessPath accessPath : inputThenStore.getAccessPathsWithValue(Nullness.NONNULL)) {
          bothUpdates.set(accessPath, Nullness.NONNULL);
        }
      } else if (callee.getParameters().size() == 2) {
        NullnessStore deferredFirstArgStore =
            argumentToInputNullnessStoreMap.get(node.getArgument(1));
        if (deferredFirstArgStore == null) {
          // Missing deferredFirstArgStore, Ignoring!
          return NullnessHint.UNKNOWN;
        }
        System.err.println("Found deferredFirstArgStore: " + deferredFirstArgStore.toString());
        for (AccessPath accessPath :
            deferredFirstArgStore.getAccessPathsWithValue(Nullness.NONNULL)) {
          bothUpdates.set(accessPath, Nullness.NONNULL);
        }
      }
    }
    return NullnessHint.UNKNOWN;
  }

  @Override
  public void ondataflowVisitStringLiteral(
      StringLiteralNode node, TransferInput<Nullness, NullnessStore> input) {
    argumentToInputNullnessStoreMap.put(node, input.getThenStore());
  }

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    // Try to keep this map from growing without bound, by clearing it whenever we start processing
    // a new top level class
    argumentToInputNullnessStoreMap.clear();
  }
}
