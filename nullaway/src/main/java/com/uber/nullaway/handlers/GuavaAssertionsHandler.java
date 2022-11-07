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

/**
 * Handler to expose semantics of Guava routines like {@code checkState}, {@code checkArgument}, and
 * {@code verify} that check a boolean condition and fail with an exception if it is false.
 */
public class GuavaAssertionsHandler extends BaseNoOpHandler {

  private static final String PRECONDITIONS_CLASS_NAME = "com.google.common.base.Preconditions";
  private static final String CHECK_ARGUMENT_METHOD_NAME = "checkArgument";
  private static final String CHECK_STATE_METHOD_NAME = "checkState";
  private static final String VERIFY_CLASS_NAME = "com.google.common.base.Verify";
  private static final String VERIFY_METHOD_NAME = "verify";

  @Nullable private Name preconditionsClass;
  @Nullable private Name verifyClass;
  @Nullable private Name checkArgumentMethod;
  @Nullable private Name checkStateMethod;
  @Nullable private Name verifyMethod;
  @Nullable TypeMirror preconditionCheckArgumentErrorType;
  @Nullable TypeMirror preconditionCheckStateErrorType;
  @Nullable TypeMirror verifyErrorType;

  @Override
  public MethodInvocationNode onCFGBuildPhase1AfterVisitMethodInvocation(
      NullAwayCFGBuilder.NullAwayCFGTranslationPhaseOne phase,
      MethodInvocationTree tree,
      MethodInvocationNode originalNode) {
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(tree);
    if (preconditionsClass == null) {
      preconditionsClass = callee.name.table.fromString(PRECONDITIONS_CLASS_NAME);
      verifyClass = callee.name.table.fromString(VERIFY_CLASS_NAME);
      checkArgumentMethod = callee.name.table.fromString(CHECK_ARGUMENT_METHOD_NAME);
      checkStateMethod = callee.name.table.fromString(CHECK_STATE_METHOD_NAME);
      verifyMethod = callee.name.table.fromString(VERIFY_METHOD_NAME);
      preconditionCheckArgumentErrorType = phase.classToErrorType(IllegalArgumentException.class);
      preconditionCheckStateErrorType = phase.classToErrorType(IllegalStateException.class);
      // We treat the Verify.* APIs as throwing a RuntimeException to avoid any issues with
      // the VerifyException that they actually throw not being in the classpath (this will not
      // affect the analysis result)
      verifyErrorType = phase.classToErrorType(RuntimeException.class);
    }
    Preconditions.checkNotNull(preconditionCheckArgumentErrorType);
    Preconditions.checkNotNull(preconditionCheckStateErrorType);
    Preconditions.checkNotNull(verifyErrorType);
    if (callee.enclClass().getQualifiedName().equals(preconditionsClass)
        && !callee.getParameters().isEmpty()) {
      // Attempt to match Precondition check methods to the expected exception type, providing as
      // much context as possible for static analysis.
      // In practice this may not be strictly necessary because the conditional throw is inserted
      // after the method invocation, thus analysis must assume that the preconditions call is
      // capable of throwing any unchecked throwable.
      if (callee.name.equals(checkArgumentMethod)) {
        phase.insertThrowOnFalse(originalNode.getArgument(0), preconditionCheckArgumentErrorType);
      } else if (callee.name.equals(checkStateMethod)) {
        phase.insertThrowOnFalse(originalNode.getArgument(0), preconditionCheckStateErrorType);
      }
    } else if (callee.enclClass().getQualifiedName().equals(verifyClass)
        && !callee.getParameters().isEmpty()
        && callee.name.equals(verifyMethod)) {
      phase.insertThrowOnFalse(originalNode.getArgument(0), verifyErrorType);
    }
    return originalNode;
  }
}
