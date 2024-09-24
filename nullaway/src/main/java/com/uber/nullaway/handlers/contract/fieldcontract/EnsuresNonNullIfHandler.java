/*
 * Copyright (c) 2017-2020 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.handlers.contract.fieldcontract;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.NullabilityUtil.getAnnotationValueArray;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.handlers.AbstractFieldContractHandler;
import com.uber.nullaway.handlers.MethodAnalysisContext;
import com.uber.nullaway.handlers.contract.ContractUtils;
import java.util.Set;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

/**
 * This Handler parses {@code @EnsuresNonNullIf} annotation and when the annotated method is
 * invoked, it annotates the fields as not null. The following tasks are performed when the
 * {@code @EnsuresNonNullIf} annotation is observed:
 *
 * <ul>
 *   <li>It validates the syntax of the annotation.
 *   <li>It validates whether all fields specified in the annotation are part in a return expression
 *       comparing its value to null
 * </ul>
 */
public class EnsuresNonNullIfHandler extends AbstractFieldContractHandler {

  public EnsuresNonNullIfHandler() {
    super("EnsuresNonNullIf");
  }

  /**
   * Validates whether all parameters mentioned in the @EnsuresNonNullIf annotation are guaranteed
   * to be {@code @NonNull} at exit point of this method.
   */
  @Override
  protected boolean validateAnnotationSemantics(
      MethodTree tree, MethodAnalysisContext methodAnalysisContext) {
    Symbol.MethodSymbol methodSymbol = methodAnalysisContext.methodSymbol();
    VisitorState state = methodAnalysisContext.state();
    NullAway analysis = methodAnalysisContext.analysis();

    Set<String> fieldNames = getAnnotationValueArray(methodSymbol, annotName, false);
    if (fieldNames == null) {
      return false;
    }

    // Validate that the method returns boolean
    if (validateBooleanReturnType(tree, state, analysis)) {
      return false;
    }
    // TODO: check if the fields actually exist

    // Check whether the method follows the expected pattern
    BlockTree body = tree.getBody();
    boolean result = body != null && body.accept(new ReturnExpressionVisitor(), fieldNames);

    if (!result) {
      String message =
          String.format(
              "Method is annotated with @EnsuresNonNullIf but does not implement 'return %s != null'",
              fieldNames);
      state.reportMatch(
          analysis
              .getErrorBuilder()
              .createErrorDescription(
                  new ErrorMessage(ErrorMessage.MessageTypes.POSTCONDITION_NOT_SATISFIED, message),
                  tree,
                  analysis.buildDescription(tree),
                  state,
                  null));
    }

    return true;
  }

  private static boolean validateBooleanReturnType(
      MethodTree tree, VisitorState state, NullAway analysis) {
    Tree returnType = tree.getReturnType();
    if (!(returnType instanceof PrimitiveTypeTree)
        || ((PrimitiveTypeTree) returnType).getPrimitiveTypeKind() != TypeKind.BOOLEAN) {
      state.reportMatch(
          analysis
              .getErrorBuilder()
              .createErrorDescription(
                  new ErrorMessage(
                      ErrorMessage.MessageTypes.PRECONDITION_NOT_SATISFIED,
                      "@EnsuresNonNullIf methods should return true"),
                  tree,
                  analysis.buildDescription(tree),
                  state,
                  null));
      return true;
    }
    return false;
  }

  // TODO: support multiple fields instead of just one
  private static final class ReturnExpressionVisitor extends TreeScanner<Boolean, Set<String>> {

    @Override
    public Boolean visitReturn(ReturnTree node, Set<String> fieldNames) {
      var expression = node.getExpression();

      // Has to be a binary expression, e.g., a != b;
      if (!(expression instanceof BinaryTree)) {
        return false;
      }

      var binaryTree = (BinaryTree) expression;

      // Left op could be an identifier (e.g., "fieldName") or a field access (this.fieldName)
      // The identifier has to be on the list of fields
      boolean isAnIdentifier = binaryTree.getLeftOperand() instanceof IdentifierTree;
      boolean isAFieldAccess = binaryTree.getLeftOperand() instanceof MemberSelectTree;
      if (isAnIdentifier) {
        var leftOp = (IdentifierTree) binaryTree.getLeftOperand();
        String identifier = leftOp.getName().toString();
        if (!fieldNames.contains(identifier)) {
          return false;
        }
      } else if (isAFieldAccess) {
        var leftOp = (MemberSelectTree) binaryTree.getLeftOperand();
        String identifier = leftOp.getIdentifier().toString();
        if (!fieldNames.contains(identifier)) {
          return false;
        }
      } else {
        // If not any, then, it's incorrect!
        return false;
      }

      // right op has to be "null"!
      var rightOp = (LiteralTree) binaryTree.getRightOperand();
      if (!rightOp.toString().equals("null")) {
        return false;
      }

      // comparison has to be !=
      if (binaryTree.getKind() != Tree.Kind.NOT_EQUAL_TO) {
        return false;
      }

      return true;
    }
  }

  /** TODO */
  @Override
  protected void validateOverridingRules(
      Set<String> overridingFieldNames,
      NullAway analysis,
      VisitorState state,
      MethodTree tree,
      Symbol.MethodSymbol overriddenMethod) {}

  /**
   * On every method annotated with {@link EnsuresNonNullIf}, this method injects the {@code
   * Nonnull} value for the class fields given in the {@code @EnsuresNonNullIf} parameter to the
   * dataflow analysis.
   */
  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Symbol.MethodSymbol methodSymbol,
      VisitorState state,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    if (node.getTree() == null) {
      return super.onDataflowVisitMethodInvocation(
          node, methodSymbol, state, apContext, inputs, thenUpdates, elseUpdates, bothUpdates);
    }

    Set<String> fieldNames = getAnnotationValueArray(methodSymbol, annotName, false);
    if (fieldNames != null) {
      fieldNames = ContractUtils.trimReceivers(fieldNames);
      for (String fieldName : fieldNames) {
        VariableElement field =
            getInstanceFieldOfClass(
                castToNonNull(ASTHelpers.enclosingClass(methodSymbol)), fieldName);
        if (field == null) {
          // Invalid annotation, will result in an error during validation.
          continue;
        }
        AccessPath accessPath =
            AccessPath.fromBaseAndElement(node.getTarget().getReceiver(), field, apContext);
        if (accessPath == null) {
          // Also likely to be an invalid annotation, will result in an error during validation.
          continue;
        }

        // The call to the EnsuresNonNullIf method ensures that the field is then not null at this
        // point
        bothUpdates.set(accessPath, Nullness.NONNULL);
      }
    }

    return super.onDataflowVisitMethodInvocation(
        node, methodSymbol, state, apContext, inputs, thenUpdates, elseUpdates, bothUpdates);
  }
}
