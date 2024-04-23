package com.uber.nullaway.handlers;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.uber.nullaway.LibraryModels;
import com.uber.nullaway.dataflow.AccessPath;
import java.util.function.Predicate;

public class SynchronousCallbackHandler extends BaseNoOpHandler {

  // TODO this should work on subtypes of the methods as well, like java.util.HashMap.  Use a
  // Matcher?
  private static final ImmutableMap<LibraryModels.MethodRef, Integer> methodToParameterIndex =
      ImmutableMap.of(
          LibraryModels.MethodRef.methodRef(
              "java.util.Map", "forEach(java.util.function.BiConsumer<? super K,? super V>)"),
          0);

  @Override
  public Predicate<AccessPath> getAccessPathPredForSavedContext(TreePath path, VisitorState state) {
    Tree leafNode = path.getLeaf();
    Preconditions.checkArgument(
        leafNode instanceof ClassTree || leafNode instanceof LambdaExpressionTree,
        "Unexpected leaf type: %s",
        leafNode.getClass());
    Tree parentNode = path.getParentPath().getLeaf();
    if (parentNode instanceof MethodInvocationTree) {
      MethodInvocationTree methodInvocationTree = (MethodInvocationTree) parentNode;
      LibraryModels.MethodRef methodRef =
          LibraryModels.MethodRef.fromSymbol(ASTHelpers.getSymbol(methodInvocationTree));
      if (methodToParameterIndex.containsKey(methodRef)) {
        int parameterIndex = -1;
        for (int i = 0; i < methodInvocationTree.getArguments().size(); i++) {
          if (methodInvocationTree.getArguments().get(i) == leafNode) {
            parameterIndex = i;
            break;
          }
        }
        if (parameterIndex == methodToParameterIndex.get(methodRef)) {
          return Handler.TRUE_AP_PREDICATE;
        }
      }
    }
    return Handler.FALSE_AP_PREDICATE;
  }
}
