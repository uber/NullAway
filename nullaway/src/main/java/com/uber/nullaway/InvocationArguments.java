package com.uber.nullaway;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Utility class to encapsulate details of operating on all method/constructor invocation arguments,
 * including handling of varargs.
 */
public class InvocationArguments {

  // Cached args as array to avoid O(n) indexed access on javac lists
  private final ExpressionTree[] argsArr;
  private final int numArgsPassed;

  // Cached parameter types as array to avoid O(n) indexed access on javac lists
  private final Type[] paramTypesArr;

  // Varargs metadata (fully precomputed)
  private final boolean isVarArgs;
  private final boolean varArgsPassedIndividually;
  private final int varArgsIndex;
  private final Type.@Nullable ArrayType varArgsArrayType; // null when not varargs
  private final @Nullable Type varArgsComponentType; // null when not varargs

  /**
   * Construct an InvocationArguments instance for the given invocation tree and method type.
   *
   * @param invocationTree the invocation tree (method call or constructor call)
   * @param invokedMethodType the method type of the invoked method/constructor
   */
  public InvocationArguments(Tree invocationTree, Type.MethodType invokedMethodType) {
    Symbol.MethodSymbol methodSymbol = (Symbol.MethodSymbol) ASTHelpers.getSymbol(invocationTree);
    checkNotNull(methodSymbol, "Expected method symbol for invocation tree");

    // Cache args as array (fast indexed access)
    List<? extends ExpressionTree> argsList;
    if (invocationTree instanceof MethodInvocationTree) {
      argsList = ((MethodInvocationTree) invocationTree).getArguments();
    } else if (invocationTree instanceof NewClassTree) {
      argsList = ((NewClassTree) invocationTree).getArguments();
    } else {
      throw new IllegalStateException("Unexpected invocation tree type");
    }
    this.argsArr = argsList.toArray(new ExpressionTree[argsList.size()]);
    this.numArgsPassed = argsArr.length;

    // Cache parameter types as array (fast indexed access)
    com.sun.tools.javac.util.List<Type> parameterTypes = invokedMethodType.getParameterTypes();
    this.paramTypesArr = parameterTypes.toArray(new Type[parameterTypes.size()]);

    // Precompute varargs state and related types
    this.isVarArgs = methodSymbol.isVarArgs();
    if (this.isVarArgs) {
      this.varArgsIndex = paramTypesArr.length - 1;
      this.varArgsArrayType = (Type.ArrayType) paramTypesArr[varArgsIndex];
      this.varArgsComponentType = varArgsArrayType.getComponentType();
      this.varArgsPassedIndividually = NullabilityUtil.isVarArgsCall(invocationTree);
    } else {
      this.varArgsIndex = -1;
      this.varArgsArrayType = null;
      this.varArgsComponentType = null;
      this.varArgsPassedIndividually = false;
    }
  }

  /**
   * Apply the given {@link ArgConsumer} to information about each argument
   *
   * @param consumer the consumer to apply
   */
  public void forEach(ArgConsumer consumer) {
    if (!isVarArgs) {
      for (int i = 0; i < numArgsPassed; i++) {
        consumer.accept(argsArr[i], i, paramTypesArr[i], false);
      }
      return;
    }
    if (varArgsPassedIndividually) {
      Type varArgsComponentType = castToNonNull(this.varArgsComponentType);
      for (int i = 0; i < numArgsPassed; i++) {
        if (i < varArgsIndex) {
          consumer.accept(argsArr[i], i, paramTypesArr[i], false);
        } else {
          consumer.accept(argsArr[i], i, varArgsComponentType, false);
        }
      }
    } else {
      Type.ArrayType varArgsArrayType = castToNonNull(this.varArgsArrayType);
      for (int i = 0; i < numArgsPassed; i++) {
        if (i < varArgsIndex) {
          consumer.accept(argsArr[i], i, paramTypesArr[i], false);
        } else {
          consumer.accept(argsArr[i], i, varArgsArrayType, true);
        }
      }
    }
  }

  /** Consumer type for information about each passed argument */
  @FunctionalInterface
  public interface ArgConsumer {

    /**
     * Process information about a passed argument
     *
     * @param argTree the argument expression tree
     * @param argPos the argument position (0-based)
     * @param formalParamType the formal parameter type for this argument
     * @param varArgsPassedAsArray true if this argument corresponds to an array passed in the
     *     varargs position (i.e., the array holds the individual varargs values)
     */
    void accept(
        ExpressionTree argTree, int argPos, Type formalParamType, boolean varArgsPassedAsArray);
  }
}
