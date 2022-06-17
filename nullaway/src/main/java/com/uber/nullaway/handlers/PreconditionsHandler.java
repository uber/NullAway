package com.uber.nullaway.handlers;

import com.google.common.base.Preconditions;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.dataflow.cfg.NullAwayCFGBuilder;
import javax.annotation.Nullable;
import javax.lang.model.element.Name;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

public class PreconditionsHandler extends BaseNoOpHandler {

  private static final String PRECONDITIONS_CLASS_NAME = "com.google.common.base.Preconditions";
  private static final String CHECK_ARGUMENT_METHOD_NAME = "checkArgument";
  private static final String CHECK_STATE_METHOD_NAME = "checkState";

  @Nullable private Name preconditionsClass;
  @Nullable private Name checkArgumentMethod;
  @Nullable private Name checkStateMethod;
  @Nullable TypeMirror preconditionErrorType;

  @Override
  public MethodInvocationNode onCFGBuildPhase1AfterVisitMethodInvocation(
      NullAwayCFGBuilder.NullAwayCFGTranslationPhaseOne phase,
      MethodInvocationTree tree,
      MethodInvocationNode originalNode) {
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(tree);
    if (preconditionsClass == null) {
      preconditionsClass = callee.name.table.fromString(PRECONDITIONS_CLASS_NAME);
      checkArgumentMethod = callee.name.table.fromString(CHECK_ARGUMENT_METHOD_NAME);
      checkStateMethod = callee.name.table.fromString(CHECK_STATE_METHOD_NAME);
      preconditionErrorType = phase.classToErrorType(IllegalArgumentException.class);
    }
    Preconditions.checkNotNull(preconditionErrorType);
    if (callee.enclClass().getQualifiedName().equals(preconditionsClass)
        && (callee.name.equals(checkArgumentMethod) || callee.name.equals(checkStateMethod))
        && callee.getParameters().size() > 0) {
      phase.insertThrowOnFalse(originalNode.getArgument(0), preconditionErrorType);
    }
    return originalNode;
  }
}
