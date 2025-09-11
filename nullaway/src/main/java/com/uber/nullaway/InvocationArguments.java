package com.uber.nullaway;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

public class InvocationArguments implements Iterable<InvocationArguments.ArgumentInfo> {

  private final Tree invocationTree;
  private final Type.MethodType invokedMethodType;
  private final boolean isVarArgs;
  private final boolean varArgsPassedIndividually;
  private final int varArgsIndex;

  public InvocationArguments(Tree invocationTree, Type.MethodType invokedMethodType) {
    this.invocationTree = invocationTree;
    Symbol.MethodSymbol methodSymbol = (Symbol.MethodSymbol) ASTHelpers.getSymbol(invocationTree);
    checkNotNull(methodSymbol, "Expected method symbol for invocation tree");
    this.invokedMethodType = invokedMethodType;
    this.isVarArgs = methodSymbol.isVarArgs();
    if (this.isVarArgs) {
      varArgsIndex = invokedMethodType.getParameterTypes().size() - 1;
      varArgsPassedIndividually = NullabilityUtil.isVarArgsCall(invocationTree);
    } else {
      varArgsIndex = -1;
      varArgsPassedIndividually = false;
    }
  }

  @Override
  public Iterator<ArgumentInfo> iterator() {
    return new ArgIterator();
  }

  public static final class ArgumentInfo {

    /** The argument expression tree. */
    private final ExpressionTree argTree;

    private final int argPos;

    /** The formal parameter type corresponding to this argument. */
    private final Type formalParamType;

    /**
     * Whether this argument is an array being passed in the varargs position (i.e., it contains all
     * the varargs arguments).
     */
    private final boolean varArgsPassedAsArray;

    ArgumentInfo(
        ExpressionTree argTree, int argPos, Type formalParamType, boolean varArgsPassedAsArray) {
      this.argTree = argTree;
      this.argPos = argPos;
      this.formalParamType = formalParamType;
      this.varArgsPassedAsArray = varArgsPassedAsArray;
    }

    public ExpressionTree getArgTree() {
      return argTree;
    }

    public int getArgPos() {
      return argPos;
    }

    public Type getFormalParamType() {
      return formalParamType;
    }

    public boolean isVarArgsPassedAsArray() {
      return varArgsPassedAsArray;
    }
  }

  private class ArgIterator implements Iterator<ArgumentInfo> {
    private int currentArgIndex = 0;
    private final int numArgsPassed;
    private List<? extends ExpressionTree> args;

    ArgIterator() {
      if (invocationTree instanceof com.sun.source.tree.MethodInvocationTree) {
        MethodInvocationTree methodInvocationTree = (MethodInvocationTree) invocationTree;
        args = methodInvocationTree.getArguments();
        numArgsPassed = args.size();
      } else if (invocationTree instanceof com.sun.source.tree.NewClassTree) {
        NewClassTree newClassTree = (NewClassTree) invocationTree;
        args = newClassTree.getArguments();
        numArgsPassed = args.size();
      } else {
        throw new IllegalStateException("Unexpected invocation tree type");
      }
    }

    @Override
    public boolean hasNext() {
      return currentArgIndex < numArgsPassed;
    }

    @Override
    public ArgumentInfo next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      ExpressionTree argTree = args.get(currentArgIndex);
      ArgumentInfo argumentInfo;
      if (isVarArgs && currentArgIndex >= varArgsIndex) {
        Type.ArrayType varArgsArrayType =
            (Type.ArrayType) invokedMethodType.getParameterTypes().get(varArgsIndex);
        if (varArgsPassedIndividually) {
          argumentInfo =
              new ArgumentInfo(
                  argTree, currentArgIndex, varArgsArrayType.getComponentType(), false);
        } else {
          argumentInfo = new ArgumentInfo(argTree, currentArgIndex, varArgsArrayType, true);
        }

      } else {
        argumentInfo =
            new ArgumentInfo(
                argTree,
                currentArgIndex,
                invokedMethodType.getParameterTypes().get(currentArgIndex),
                false);
      }
      currentArgIndex++;
      return argumentInfo;
    }
  }
}
