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
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.jspecify.annotations.Nullable;

public class InvocationArguments implements Iterable<InvocationArguments.ArgumentInfo> {

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
    this.argsArr = toArgArray(argsList);
    this.numArgsPassed = argsArr.length;

    // Cache parameter types as array (fast indexed access)
    this.paramTypesArr = toParamArray(invokedMethodType.getParameterTypes());

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

  // Fast, zero-allocation traversal (no ArgumentInfo objects)
  public void forEachFast(ArgConsumer consumer) {
    if (!isVarArgs) {
      for (int i = 0; i < numArgsPassed; i++) {
        consumer.accept(argsArr[i], i, paramTypesArr[i], false);
      }
      return;
    }
    if (varArgsPassedIndividually) {
      for (int i = 0; i < numArgsPassed; i++) {
        if (i < varArgsIndex) {
          consumer.accept(argsArr[i], i, paramTypesArr[i], false);
        } else {
          consumer.accept(argsArr[i], i, castToNonNull(this.varArgsComponentType), false);
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

  public int size() {
    return numArgsPassed;
  }

  @Override
  public Iterator<ArgumentInfo> iterator() {
    if (!isVarArgs) {
      return new RegularArgIterator(argsArr, paramTypesArr);
    }
    return new VarArgsIterator(
        argsArr,
        paramTypesArr,
        varArgsIndex,
        varArgsPassedIndividually,
        castToNonNull(varArgsArrayType),
        castToNonNull(varArgsComponentType));
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

  @FunctionalInterface
  public interface ArgConsumer {
    void accept(
        ExpressionTree argTree, int argPos, Type formalParamType, boolean varArgsPassedAsArray);
  }

  // Regular (non-varargs) iterator: minimal branching, fast indexed access
  private static final class RegularArgIterator implements Iterator<ArgumentInfo> {
    private final ExpressionTree[] args;
    private final Type[] paramTypes;
    private int idx = 0;

    RegularArgIterator(ExpressionTree[] args, Type[] paramTypes) {
      this.args = args;
      this.paramTypes = paramTypes;
    }

    @Override
    public boolean hasNext() {
      return idx < args.length;
    }

    @Override
    public ArgumentInfo next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      int i = idx++;
      return new ArgumentInfo(args[i], i, paramTypes[i], false);
    }
  }

  // Varargs iterator: uses precomputed array/component types, avoids per-next lookups/casts
  private static final class VarArgsIterator implements Iterator<ArgumentInfo> {
    private final ExpressionTree[] args;
    private final Type[] paramTypes;
    private final int varArgsIndex;
    private final boolean passedIndividually;
    private final Type.ArrayType varArgsArrayType;
    private final Type varArgsComponentType;
    private int idx = 0;

    VarArgsIterator(
        ExpressionTree[] args,
        Type[] paramTypes,
        int varArgsIndex,
        boolean passedIndividually,
        Type.ArrayType varArgsArrayType,
        Type varArgsComponentType) {
      this.args = args;
      this.paramTypes = paramTypes;
      this.varArgsIndex = varArgsIndex;
      this.passedIndividually = passedIndividually;
      this.varArgsArrayType = varArgsArrayType;
      this.varArgsComponentType = varArgsComponentType;
    }

    @Override
    public boolean hasNext() {
      return idx < args.length;
    }

    @Override
    public ArgumentInfo next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      ExpressionTree argTree = args[idx];
      ArgumentInfo info;
      if (idx < varArgsIndex) {
        info = new ArgumentInfo(argTree, idx, paramTypes[idx], false);
      } else if (passedIndividually) {
        info = new ArgumentInfo(argTree, idx, varArgsComponentType, false);
      } else {
        info = new ArgumentInfo(argTree, idx, varArgsArrayType, true);
      }
      idx++;
      return info;
    }
  }

  private static ExpressionTree[] toArgArray(List<? extends ExpressionTree> list) {
    ExpressionTree[] arr = new ExpressionTree[list.size()];
    int i = 0;
    for (ExpressionTree e : list) {
      arr[i++] = e;
    }
    return arr;
  }

  private static Type[] toParamArray(com.sun.tools.javac.util.List<Type> list) {
    Type[] arr = new Type[list.size()];
    int i = 0;
    for (Type t : list) {
      arr[i++] = t;
    }
    return arr;
  }
}
