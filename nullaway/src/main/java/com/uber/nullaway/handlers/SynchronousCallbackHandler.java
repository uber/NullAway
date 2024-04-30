package com.uber.nullaway.handlers;

import static com.uber.nullaway.handlers.AccessPathPredicates.FALSE_AP_PREDICATE;
import static com.uber.nullaway.handlers.AccessPathPredicates.TRUE_AP_PREDICATE;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.LibraryModels.MethodRef;
import com.uber.nullaway.dataflow.AccessPath;
import java.util.function.Predicate;

public class SynchronousCallbackHandler extends BaseNoOpHandler {

  /**
   * Maps method name to full information about the corresponding methods and what parameter is the
   * relevant callback. We key on method name to quickly eliminate most cases when doing a lookup.
   */
  private static final ImmutableMap<String, ImmutableMap<MethodRef, Integer>>
      METHOD_NAME_TO_SIG_AND_PARAM_INDEX =
          ImmutableMap.of(
              "forEach",
              ImmutableMap.of(
                  MethodRef.methodRef(
                      "java.util.Map",
                      "forEach(java.util.function.BiConsumer<? super K,? super V>)"),
                  0,
                  MethodRef.methodRef(
                      "java.lang.Iterable", "forEach(java.util.function.Consumer<? super T>)"),
                  0),
              "removeIf",
              ImmutableMap.of(
                  MethodRef.methodRef(
                      "java.util.Collection", "removeIf(java.util.function.Predicate<? super E>)"),
                  0));

  private static final Supplier<Type> STREAM_TYPE_SUPPLIER =
      Suppliers.typeFromString("java.util.stream.Stream");

  @Override
  public Predicate<AccessPath> getAccessPathPredicateForNestedMethod(
      TreePath path, VisitorState state) {
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
      Type ownerType = symbol.owner.type;
      if (ASTHelpers.isSameType(ownerType, STREAM_TYPE_SUPPLIER.get(state), state)) {
        // preserve access paths for all callbacks passed to stream methods
        return TRUE_AP_PREDICATE;
      }
      String invokedMethodName = symbol.getSimpleName().toString();
      if (METHOD_NAME_TO_SIG_AND_PARAM_INDEX.containsKey(invokedMethodName)) {
        ImmutableMap<MethodRef, Integer> entriesForMethodName =
            METHOD_NAME_TO_SIG_AND_PARAM_INDEX.get(invokedMethodName);
        for (MethodRef methodRef : entriesForMethodName.keySet()) {
          if (symbol.toString().equals(methodRef.fullMethodSig)
              && ASTHelpers.isSubtype(
                  ownerType, state.getTypeFromString(methodRef.enclosingClass), state)) {
            int parameterIndex = -1;
            for (int i = 0; i < methodInvocationTree.getArguments().size(); i++) {
              if (methodInvocationTree.getArguments().get(i) == leafNode) {
                parameterIndex = i;
                break;
              }
            }
            if (parameterIndex == entriesForMethodName.get(methodRef)) {
              return TRUE_AP_PREDICATE;
            }
          }
        }
      }
    }
    return FALSE_AP_PREDICATE;
  }
}
