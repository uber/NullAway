package com.uber.nullaway.handlers.contract;

import static com.uber.nullaway.Nullness.NONNULL;
import static com.uber.nullaway.Nullness.NULLABLE;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.NullnessStore;
import com.uber.nullaway.dataflow.NullnessStoreInitializer;
import com.uber.nullaway.handlers.Handler;
import java.util.List;
import javax.lang.model.element.Element;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;

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
    final String contractString = ContractUtils.getContractString(callee, config);

    assert contractString != null;

    String[] clauses = contractString.split(";");
    String[] parts = clauses[0].split("->");
    String[] antecedent = parts[0].split(",");

    NullnessStore envStore = getEnvNullnessStoreForClass(classTree, context);
    NullnessStore.Builder result = envStore.toBuilder();

    for (int i = 0; i < antecedent.length; ++i) {
      String valueConstraint = antecedent[i].trim();

      final LocalVariableNode param = parameters.get(i);
      final Element element = param.getElement();

      Nullness assumed = NULLABLE;

      // There are 2 cases when we assume that the parameter is NONNULL
      // 1. if the contract specifies it as (!null)
      // 2. if there is no @nullable annotation to the parameter in the function signature
      if (valueConstraint.equals("!null")
          || !Nullness.hasNullableAnnotation((Symbol) element, config)) {
        assumed = NONNULL;
      }

      result.setInformation(AccessPath.fromLocal(param), assumed);
    }

    return result.build();
  }
}
