package com.uber.nullaway.dataflow;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.Config;
import com.uber.nullaway.handlers.Handler;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.lang.model.element.NestingKind;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;

/**
 * An abstract class that allows overriding initialization of nullness store in dataflow. Currently,
 * {@link CoreNullnessStoreInitializer} is the default dataflow initializer and {@link
 * com.uber.nullaway.handlers.contract.ContractNullnessStoreInitializer} is the initializer in case
 * of Contract checking dataflow.
 */
public abstract class NullnessStoreInitializer {

  /**
   * An abstract method which returns the initial nullness store for dataflow analysis.
   *
   * @param underlyingAST The AST node being matched.
   * @param parameters list of local variable nodes.
   * @param handler reference to the handler invoked.
   * @param context context.
   * @param types types.
   * @param config config for analysis.
   * @return Initial Nullness store.
   */
  public abstract NullnessStore getInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      Handler handler,
      Context context,
      Types types,
      Config config);

  /**
   * Returns the nullness info of locals in the enclosing environment for the closest enclosing
   * local or anonymous class. if no such class, returns an empty {@link NullnessStore}
   */
  protected static NullnessStore getEnvNullnessStoreForClass(ClassTree classTree, Context context) {
    NullnessStore envStore = NullnessStore.empty();
    ClassTree enclosingLocalOrAnonymous = findEnclosingLocalOrAnonymousClass(classTree, context);
    if (enclosingLocalOrAnonymous != null) {
      EnclosingEnvironmentNullness environmentNullness =
          EnclosingEnvironmentNullness.instance(context);
      envStore =
          Objects.requireNonNull(
              environmentNullness.getEnvironmentMapping(enclosingLocalOrAnonymous));
    }
    return envStore;
  }

  @Nullable
  private static ClassTree findEnclosingLocalOrAnonymousClass(
      ClassTree classTree, Context context) {
    Symbol.ClassSymbol symbol = ASTHelpers.getSymbol(classTree);
    // we need this while loop since we can have a NestingKind.NESTED class (i.e., a nested
    // class declared at the top-level within its enclosing class) nested (possibly deeply)
    // within a NestingKind.ANONYMOUS or NestingKind.LOCAL class
    while (symbol.getNestingKind().isNested()) {
      if (symbol.getNestingKind().equals(NestingKind.ANONYMOUS)
          || symbol.getNestingKind().equals(NestingKind.LOCAL)) {
        return Trees.instance(JavacProcessingEnvironment.instance(context)).getTree(symbol);
      } else {
        // symbol.owner is the enclosing element, which could be a class or a method.
        // if it's a class, the enclClass() method will (surprisingly) return the class itself,
        // so this works
        symbol = symbol.owner.enclClass();
      }
    }
    return null;
  }
}
