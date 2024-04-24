package com.uber.nullaway.handlers;

import static com.uber.nullaway.handlers.CompositeHandler.FALSE_AP_PREDICATE;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.LibraryModels;
import com.uber.nullaway.dataflow.AccessPath;
import java.util.function.Predicate;

public class SynchronousCallbackHandler extends BaseNoOpHandler {

  // TODO this should work on subtypes of the methods as well, like java.util.HashMap.  Use a
  // Matcher?
  private static final ImmutableMap<String, ImmutableMap<LibraryModels.MethodRef, Integer>>
      METHOD_NAME_TO_SIG_AND_PARAM_INDEX =
          ImmutableMap.of(
              "forEach",
              ImmutableMap.of(
                  LibraryModels.MethodRef.methodRef(
                      "java.util.Map",
                      "forEach(java.util.function.BiConsumer<? super K,? super V>)"),
                  0));

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
      Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(methodInvocationTree);
      if (symbol == null) {
        return FALSE_AP_PREDICATE;
      }
      String invokedMethodName = symbol.getSimpleName().toString();
      if (METHOD_NAME_TO_SIG_AND_PARAM_INDEX.containsKey(invokedMethodName)) {
        ImmutableMap<LibraryModels.MethodRef, Integer> entriesForMethodName =
            METHOD_NAME_TO_SIG_AND_PARAM_INDEX.get(invokedMethodName);
        for (LibraryModels.MethodRef methodRef : entriesForMethodName.keySet()) {
          if (symbol.toString().equals(methodRef.fullMethodSig)
              && ASTHelpers.isSubtype(
                  symbol.owner.type, state.getTypeFromString(methodRef.enclosingClass), state)) {
            int parameterIndex = -1;
            for (int i = 0; i < methodInvocationTree.getArguments().size(); i++) {
              if (methodInvocationTree.getArguments().get(i) == leafNode) {
                parameterIndex = i;
                break;
              }
            }
            if (parameterIndex == entriesForMethodName.get(methodRef)) {
              return CompositeHandler.TRUE_AP_PREDICATE;
            }
          }
        }
      }
    }
    return FALSE_AP_PREDICATE;
  }
}
