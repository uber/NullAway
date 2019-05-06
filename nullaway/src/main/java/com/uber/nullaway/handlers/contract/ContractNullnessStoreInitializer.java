package com.uber.nullaway.handlers.contract;

import static com.uber.nullaway.Nullness.NONNULL;
import static com.uber.nullaway.Nullness.NULLABLE;
import static com.uber.nullaway.handlers.contract.ContractUtils.getContractFromAnnotation;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.Config;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.NullnessStore;
import com.uber.nullaway.dataflow.NullnessStoreInitializer;
import com.uber.nullaway.handlers.Handler;
import java.util.List;
import org.checkerframework.dataflow.cfg.UnderlyingAST;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;

/**
 * Nullness Store initializer in case of dataflow for contract check. The nullability of parameters
 * in this case is determined from @Contract annotation.
 */
public class ContractNullnessStoreInitializer extends NullnessStoreInitializer {

  @Override
  public NullnessStore getInitialStore(
      UnderlyingAST underlyingAST,
      List<LocalVariableNode> parameters,
      Handler handler,
      Context context,
      Types types,
      Config config) {
    assert underlyingAST.getKind() == UnderlyingAST.Kind.METHOD;

    final MethodTree methodTree = ((UnderlyingAST.CFGMethod) underlyingAST).getMethod();
    final ClassTree classTree = ((UnderlyingAST.CFGMethod) underlyingAST).getClassTree();
    final Symbol.MethodSymbol callee = ASTHelpers.getSymbol(methodTree);
    final String contractString = getContractFromAnnotation(callee);

    assert contractString != null;

    String[] clauses = contractString.split(";");
    String[] parts = clauses[0].split("->");
    String[] antecedent = parts[0].split(",");

    NullnessStore envStore = getEnvNullnessStoreForClass(classTree, context);
    NullnessStore.Builder result = envStore.toBuilder();

    for (int i = 0; i < antecedent.length; ++i) {
      String valueConstraint = antecedent[i].trim();
      result.setInformation(
          AccessPath.fromLocal(parameters.get(i)),
          valueConstraint.equals("!null") ? NONNULL : NULLABLE);
    }

    return result.build();
  }
}
