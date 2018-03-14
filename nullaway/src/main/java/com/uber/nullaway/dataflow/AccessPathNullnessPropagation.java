/*
 * Copyright 2014 Google Inc. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.uber.nullaway.dataflow;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.uber.nullaway.Nullness.BOTTOM;
import static com.uber.nullaway.Nullness.NONNULL;
import static com.uber.nullaway.Nullness.NULLABLE;
import static javax.lang.model.element.ElementKind.EXCEPTION_PARAMETER;
import static org.checkerframework.javacutil.TreeUtils.elementFromDeclaration;

import com.google.common.base.Preconditions;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import com.uber.nullaway.handlers.Handler.NullnessHint;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.CheckReturnValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import org.checkerframework.dataflow.analysis.ConditionalTransferResult;
import org.checkerframework.dataflow.analysis.RegularTransferResult;
import org.checkerframework.dataflow.analysis.TransferFunction;
import org.checkerframework.dataflow.analysis.TransferInput;
import org.checkerframework.dataflow.analysis.TransferResult;
import org.checkerframework.dataflow.cfg.UnderlyingAST;
import org.checkerframework.dataflow.cfg.node.ArrayAccessNode;
import org.checkerframework.dataflow.cfg.node.ArrayCreationNode;
import org.checkerframework.dataflow.cfg.node.ArrayTypeNode;
import org.checkerframework.dataflow.cfg.node.AssertionErrorNode;
import org.checkerframework.dataflow.cfg.node.AssignmentNode;
import org.checkerframework.dataflow.cfg.node.BitwiseAndNode;
import org.checkerframework.dataflow.cfg.node.BitwiseComplementNode;
import org.checkerframework.dataflow.cfg.node.BitwiseOrNode;
import org.checkerframework.dataflow.cfg.node.BitwiseXorNode;
import org.checkerframework.dataflow.cfg.node.BooleanLiteralNode;
import org.checkerframework.dataflow.cfg.node.CaseNode;
import org.checkerframework.dataflow.cfg.node.CharacterLiteralNode;
import org.checkerframework.dataflow.cfg.node.ClassNameNode;
import org.checkerframework.dataflow.cfg.node.ConditionalAndNode;
import org.checkerframework.dataflow.cfg.node.ConditionalNotNode;
import org.checkerframework.dataflow.cfg.node.ConditionalOrNode;
import org.checkerframework.dataflow.cfg.node.DoubleLiteralNode;
import org.checkerframework.dataflow.cfg.node.EqualToNode;
import org.checkerframework.dataflow.cfg.node.ExplicitThisLiteralNode;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.FloatLiteralNode;
import org.checkerframework.dataflow.cfg.node.FloatingDivisionNode;
import org.checkerframework.dataflow.cfg.node.FloatingRemainderNode;
import org.checkerframework.dataflow.cfg.node.FunctionalInterfaceNode;
import org.checkerframework.dataflow.cfg.node.GreaterThanNode;
import org.checkerframework.dataflow.cfg.node.GreaterThanOrEqualNode;
import org.checkerframework.dataflow.cfg.node.ImplicitThisLiteralNode;
import org.checkerframework.dataflow.cfg.node.InstanceOfNode;
import org.checkerframework.dataflow.cfg.node.IntegerDivisionNode;
import org.checkerframework.dataflow.cfg.node.IntegerLiteralNode;
import org.checkerframework.dataflow.cfg.node.IntegerRemainderNode;
import org.checkerframework.dataflow.cfg.node.LambdaResultExpressionNode;
import org.checkerframework.dataflow.cfg.node.LeftShiftNode;
import org.checkerframework.dataflow.cfg.node.LessThanNode;
import org.checkerframework.dataflow.cfg.node.LessThanOrEqualNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.LongLiteralNode;
import org.checkerframework.dataflow.cfg.node.MarkerNode;
import org.checkerframework.dataflow.cfg.node.MethodAccessNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.NarrowingConversionNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.NotEqualNode;
import org.checkerframework.dataflow.cfg.node.NullChkNode;
import org.checkerframework.dataflow.cfg.node.NullLiteralNode;
import org.checkerframework.dataflow.cfg.node.NumericalAdditionNode;
import org.checkerframework.dataflow.cfg.node.NumericalMinusNode;
import org.checkerframework.dataflow.cfg.node.NumericalMultiplicationNode;
import org.checkerframework.dataflow.cfg.node.NumericalPlusNode;
import org.checkerframework.dataflow.cfg.node.NumericalSubtractionNode;
import org.checkerframework.dataflow.cfg.node.ObjectCreationNode;
import org.checkerframework.dataflow.cfg.node.PackageNameNode;
import org.checkerframework.dataflow.cfg.node.ParameterizedTypeNode;
import org.checkerframework.dataflow.cfg.node.PrimitiveTypeNode;
import org.checkerframework.dataflow.cfg.node.ReturnNode;
import org.checkerframework.dataflow.cfg.node.ShortLiteralNode;
import org.checkerframework.dataflow.cfg.node.SignedRightShiftNode;
import org.checkerframework.dataflow.cfg.node.StringConcatenateAssignmentNode;
import org.checkerframework.dataflow.cfg.node.StringConcatenateNode;
import org.checkerframework.dataflow.cfg.node.StringConversionNode;
import org.checkerframework.dataflow.cfg.node.StringLiteralNode;
import org.checkerframework.dataflow.cfg.node.SuperNode;
import org.checkerframework.dataflow.cfg.node.SynchronizedNode;
import org.checkerframework.dataflow.cfg.node.TernaryExpressionNode;
import org.checkerframework.dataflow.cfg.node.ThisLiteralNode;
import org.checkerframework.dataflow.cfg.node.ThrowNode;
import org.checkerframework.dataflow.cfg.node.TypeCastNode;
import org.checkerframework.dataflow.cfg.node.UnsignedRightShiftNode;
import org.checkerframework.dataflow.cfg.node.VariableDeclarationNode;
import org.checkerframework.dataflow.cfg.node.WideningConversionNode;

/**
 * transfer functions for our access path nullness dataflow analysis
 *
 * <p>Based on code originally from Error Prone (see {@link
 * com.google.errorprone.dataflow.nullnesspropagation.AbstractNullnessPropagationTransfer} and
 * {@link com.google.errorprone.dataflow.nullnesspropagation.NullnessPropagationTransfer})
 */
public class AccessPathNullnessPropagation
    implements TransferFunction<Nullness, NullnessStore<Nullness>> {

  private static final boolean NO_STORE_CHANGE = false;

  private final Nullness defaultAssumption;

  private final Predicate<MethodInvocationNode> methodReturnsNonNull;

  private final Types types;

  private final Config config;

  private final Handler handler;

  AccessPathNullnessPropagation(
      Nullness defaultAssumption,
      Predicate<MethodInvocationNode> methodReturnsNonNull,
      Types types,
      Config config,
      Handler handler) {
    this.defaultAssumption = defaultAssumption;
    this.methodReturnsNonNull = methodReturnsNonNull;
    this.types = types;
    this.config = config;
    this.handler = handler;
  }

  private static SubNodeValues values(
      final TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return input::getValueOfSubNode;
  }

  /**
   * @param node CFG node
   * @return if node is an {@link AssignmentNode} unwraps it to its LHS. otherwise returns node
   */
  private static Node unwrapAssignExpr(Node node) {
    if (node instanceof AssignmentNode) {
      // in principle, we could separately handle the LHS and RHS and add new facts
      // about both.  For now, just handle the LHS as that seems like the more common
      // case (see https://github.com/uber/NullAway/issues/97)
      return ((AssignmentNode) node).getTarget();
    } else {
      return node;
    }
  }

  @Override
  public NullnessStore<Nullness> initialStore(
      UnderlyingAST underlyingAST, List<LocalVariableNode> parameters) {
    if (parameters == null) {
      // Documentation of this method states, "parameters is only set if the underlying AST is a
      // method"
      return NullnessStore.empty();
    }
    boolean isLambda = underlyingAST.getKind().equals(UnderlyingAST.Kind.LAMBDA);
    if (isLambda) {
      return lambdaInitialStore((UnderlyingAST.CFGLambda) underlyingAST, parameters);
    } else {
      return methodInitialStore(underlyingAST, parameters);
    }
  }

  private NullnessStore<Nullness> lambdaInitialStore(
      UnderlyingAST.CFGLambda underlyingAST, List<LocalVariableNode> parameters) {
    NullnessStore.Builder<Nullness> result = NullnessStore.<Nullness>empty().toBuilder();
    LambdaExpressionTree code = underlyingAST.getLambdaTree();
    // need to check annotation for i'th parameter of functional interface declaration
    Symbol.MethodSymbol fiMethodSymbol = NullabilityUtil.getFunctionalInterfaceMethod(code, types);
    com.sun.tools.javac.util.List<Symbol.VarSymbol> fiMethodParameters =
        fiMethodSymbol.getParameters();
    for (int i = 0; i < parameters.size(); i++) {
      LocalVariableNode param = parameters.get(i);
      VariableTree variableTree = code.getParameters().get(i);
      Element element = param.getElement();
      Nullness assumed;
      // we treat lambda parameters differently; they "inherit" the nullability of the
      // corresponding functional interface parameter, unless they are explicitly annotated
      if (Nullness.hasNullableAnnotation(element)) {
        assumed = NULLABLE;
      } else if (NullabilityUtil.lambdaParamIsExplicitlyTyped(variableTree)) {
        // the parameter has a declared type with no @Nullable annotation
        // treat as non-null
        assumed = NONNULL;
      } else {
        if (NullabilityUtil.fromUnannotatedPackage(fiMethodSymbol, config)) {
          // optimistically assume parameter is non-null
          assumed = NONNULL;
        } else {
          assumed = Nullness.hasNullableAnnotation(fiMethodParameters.get(i)) ? NULLABLE : NONNULL;
        }
      }
      result.setInformation(AccessPath.fromLocal(param), assumed);
    }
    result = handler.onDataflowInitialStore(underlyingAST, parameters, result);
    return result.build();
  }

  private NullnessStore<Nullness> methodInitialStore(
      UnderlyingAST underlyingAST, List<LocalVariableNode> parameters) {
    NullnessStore.Builder<Nullness> result = NullnessStore.<Nullness>empty().toBuilder();
    for (LocalVariableNode param : parameters) {
      Element element = param.getElement();
      Nullness assumed = Nullness.hasNullableAnnotation(element) ? NULLABLE : NONNULL;
      result.setInformation(AccessPath.fromLocal(param), assumed);
    }
    result = handler.onDataflowInitialStore(underlyingAST, parameters, result);
    return result.build();
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitShortLiteral(
      ShortLiteralNode shortLiteralNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitIntegerLiteral(
      IntegerLiteralNode integerLiteralNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitLongLiteral(
      LongLiteralNode longLiteralNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitFloatLiteral(
      FloatLiteralNode floatLiteralNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitDoubleLiteral(
      DoubleLiteralNode doubleLiteralNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitBooleanLiteral(
      BooleanLiteralNode booleanLiteralNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitCharacterLiteral(
      CharacterLiteralNode characterLiteralNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitStringLiteral(
      StringLiteralNode stringLiteralNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitNullLiteral(
      NullLiteralNode nullLiteralNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    // let's be sane here and return null
    return new RegularTransferResult<>(Nullness.NULL, input.getRegularStore());
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitNumericalMinus(
      NumericalMinusNode numericalMinusNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitNumericalPlus(
      NumericalPlusNode numericalPlusNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitBitwiseComplement(
      BitwiseComplementNode bitwiseComplementNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitNullChk(
      NullChkNode nullChkNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    throw new RuntimeException("we should never see this");
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitStringConcatenate(
      StringConcatenateNode stringConcatenateNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    // concatenation always returns non-null
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitNumericalAddition(
      NumericalAdditionNode numericalAdditionNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitNumericalSubtraction(
      NumericalSubtractionNode numericalSubtractionNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitNumericalMultiplication(
      NumericalMultiplicationNode numericalMultiplicationNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitIntegerDivision(
      IntegerDivisionNode integerDivisionNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitFloatingDivision(
      FloatingDivisionNode floatingDivisionNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitIntegerRemainder(
      IntegerRemainderNode integerRemainderNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitFloatingRemainder(
      FloatingRemainderNode floatingRemainderNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitLeftShift(
      LeftShiftNode leftShiftNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitSignedRightShift(
      SignedRightShiftNode signedRightShiftNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitUnsignedRightShift(
      UnsignedRightShiftNode unsignedRightShiftNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitBitwiseAnd(
      BitwiseAndNode bitwiseAndNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitBitwiseOr(
      BitwiseOrNode bitwiseOrNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitBitwiseXor(
      BitwiseXorNode bitwiseXorNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitStringConcatenateAssignment(
      StringConcatenateAssignmentNode stringConcatenateAssignmentNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitLessThan(
      LessThanNode lessThanNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitLessThanOrEqual(
      LessThanOrEqualNode lessThanOrEqualNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitGreaterThan(
      GreaterThanNode greaterThanNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitGreaterThanOrEqual(
      GreaterThanOrEqualNode greaterThanOrEqualNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitEqualTo(
      EqualToNode equalToNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    ReadableUpdates thenUpdates = new ReadableUpdates();
    ReadableUpdates elseUpdates = new ReadableUpdates();
    handleEqualityComparison(
        true,
        equalToNode.getLeftOperand(),
        equalToNode.getRightOperand(),
        values(input),
        thenUpdates,
        elseUpdates);
    ResultingStore thenStore = updateStore(input.getThenStore(), thenUpdates);
    ResultingStore elseStore = updateStore(input.getElseStore(), elseUpdates);
    return conditionalResult(
        thenStore.store, elseStore.store, thenStore.storeChanged || elseStore.storeChanged);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitNotEqual(
      NotEqualNode notEqualNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    ReadableUpdates thenUpdates = new ReadableUpdates();
    ReadableUpdates elseUpdates = new ReadableUpdates();
    handleEqualityComparison(
        false,
        notEqualNode.getLeftOperand(),
        notEqualNode.getRightOperand(),
        values(input),
        thenUpdates,
        elseUpdates);
    ResultingStore thenStore = updateStore(input.getThenStore(), thenUpdates);
    ResultingStore elseStore = updateStore(input.getElseStore(), elseUpdates);
    return conditionalResult(
        thenStore.store, elseStore.store, thenStore.storeChanged || elseStore.storeChanged);
  }

  private void handleEqualityComparison(
      boolean equalTo,
      Node leftNode,
      Node rightNode,
      SubNodeValues inputs,
      Updates thenUpdates,
      Updates elseUpdates) {
    Nullness leftVal = inputs.valueOfSubNode(leftNode);
    Nullness rightVal = inputs.valueOfSubNode(rightNode);
    Nullness equalBranchValue = leftVal.greatestLowerBound(rightVal);
    Updates equalBranchUpdates = equalTo ? thenUpdates : elseUpdates;
    Updates notEqualBranchUpdates = equalTo ? elseUpdates : thenUpdates;

    Node realLeftNode = unwrapAssignExpr(leftNode);
    Node realRightNode = unwrapAssignExpr(rightNode);

    AccessPath leftAP = AccessPath.getAccessPathForNodeWithMapGet(realLeftNode, types);
    if (leftAP != null) {
      equalBranchUpdates.set(leftAP, equalBranchValue);
      notEqualBranchUpdates.set(
          leftAP, leftVal.greatestLowerBound(rightVal.deducedValueWhenNotEqual()));
    }

    AccessPath rightAP = AccessPath.getAccessPathForNodeWithMapGet(realRightNode, types);
    if (rightAP != null) {
      equalBranchUpdates.set(rightAP, equalBranchValue);
      notEqualBranchUpdates.set(
          rightAP, rightVal.greatestLowerBound(leftVal.deducedValueWhenNotEqual()));
    }
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitConditionalAnd(
      ConditionalAndNode conditionalAndNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return conditionalResult(input.getThenStore(), input.getElseStore(), NO_STORE_CHANGE);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitConditionalOr(
      ConditionalOrNode conditionalOrNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return conditionalResult(input.getThenStore(), input.getElseStore(), NO_STORE_CHANGE);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitConditionalNot(
      ConditionalNotNode conditionalNotNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    boolean storeChanged = !input.getThenStore().equals(input.getElseStore());
    return conditionalResult(
        /* thenStore= */ input.getElseStore(), /* elseStore= */ input.getThenStore(), storeChanged);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitTernaryExpression(
      TernaryExpressionNode node, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    SubNodeValues inputs = values(input);
    Nullness result =
        inputs
            .valueOfSubNode(node.getThenOperand())
            .leastUpperBound(inputs.valueOfSubNode(node.getElseOperand()));
    return new RegularTransferResult<>(result, input.getRegularStore());
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitAssignment(
      AssignmentNode node, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    ReadableUpdates updates = new ReadableUpdates();
    Nullness value = values(input).valueOfSubNode(node.getExpression());
    Node target = node.getTarget();

    if (target instanceof LocalVariableNode) {
      updates.set((LocalVariableNode) target, value);
    }

    if (target instanceof ArrayAccessNode) {
      setNonnullIfAnalyzeable(updates, ((ArrayAccessNode) target).getArray());
    }

    if (target instanceof FieldAccessNode) {
      // we don't allow arbitrary access paths to be tracked from assignments
      // here we still require an access of a field of this, or a static field
      FieldAccessNode fieldAccessNode = (FieldAccessNode) target;
      Node receiver = fieldAccessNode.getReceiver();
      if ((receiver instanceof ThisLiteralNode || fieldAccessNode.isStatic())
          && fieldAccessNode.getElement().getKind().equals(ElementKind.FIELD)) {
        updates.set(fieldAccessNode, value);
      }
    }

    return updateRegularStore(value, input, updates);
  }

  private TransferResult<Nullness, NullnessStore<Nullness>> updateRegularStore(
      Nullness value,
      TransferInput<Nullness, NullnessStore<Nullness>> input,
      ReadableUpdates updates) {
    ResultingStore newStore = updateStore(input.getRegularStore(), updates);
    return new RegularTransferResult<>(value, newStore.store, newStore.storeChanged);
  }

  /**
   * If node represents a local, field access, or method call we can track, set it to be non-null in
   * the updates
   */
  private void setNonnullIfAnalyzeable(Updates updates, Node node) {
    AccessPath ap = AccessPath.getAccessPathForNodeWithMapGet(node, types);
    if (ap != null) {
      updates.set(ap, NONNULL);
    }
  }

  private static boolean hasPrimitiveType(Node node) {
    return node.getType().getKind().isPrimitive();
  }

  private static boolean hasNonNullConstantValue(LocalVariableNode node) {
    if (node.getElement() instanceof VariableElement) {
      VariableElement element = (VariableElement) node.getElement();
      return (element.getConstantValue() != null);
    }
    return false;
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitLocalVariable(
      LocalVariableNode node, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    NullnessStore<Nullness> values = input.getRegularStore();
    Nullness nullness =
        hasPrimitiveType(node) || hasNonNullConstantValue(node)
            ? NONNULL
            : values.valueOfLocalVariable(node, defaultAssumption);
    return new RegularTransferResult<>(nullness, values);
  }

  private static boolean isCatchVariable(VariableDeclarationNode node) {
    return elementFromDeclaration(node.getTree()).getKind() == EXCEPTION_PARAMETER;
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitVariableDeclaration(
      VariableDeclarationNode node, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    ReadableUpdates updates = new ReadableUpdates();
    if (isCatchVariable(node)) {
      updates.set(node, NONNULL);
    }
    /*
     * We can return whatever we want here because a variable declaration is not an expression and
     * thus no one can use its value directly. Any updates to the nullness of the variable are
     * performed in the store so that they are available to future reads.
     */
    return updateRegularStore(BOTTOM, input, updates);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitFieldAccess(
      FieldAccessNode fieldAccessNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    ReadableUpdates updates = new ReadableUpdates();
    Symbol symbol = ASTHelpers.getSymbol(fieldAccessNode.getTree());
    setReceiverNonnull(updates, fieldAccessNode.getReceiver(), symbol);
    VariableElement element = fieldAccessNode.getElement();
    Nullness nullness = Nullness.NULLABLE;
    if (!NullabilityUtil.mayBeNullFieldFromType(symbol, config)) {
      nullness = NONNULL;
    } else {
      nullness = input.getRegularStore().valueOfField(fieldAccessNode, nullness);
    }
    return updateRegularStore(nullness, input, updates);
  }

  private void setReceiverNonnull(
      AccessPathNullnessPropagation.ReadableUpdates updates, Node receiver, Symbol symbol) {
    if (symbol != null && !symbol.isStatic()) {
      setNonnullIfAnalyzeable(updates, receiver);
    }
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitMethodAccess(
      MethodAccessNode methodAccessNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitArrayAccess(
      ArrayAccessNode node, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    ReadableUpdates updates = new ReadableUpdates();
    setNonnullIfAnalyzeable(updates, node.getArray());
    // this is unsound
    return updateRegularStore(defaultAssumption, input, updates);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitImplicitThisLiteral(
      ImplicitThisLiteralNode implicitThisLiteralNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitExplicitThisLiteral(
      ExplicitThisLiteralNode explicitThisLiteralNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  private TransferResult<Nullness, NullnessStore<Nullness>> noStoreChanges(
      Nullness value, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return new RegularTransferResult<>(value, input.getRegularStore());
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitSuper(
      SuperNode superNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitReturn(
      ReturnNode returnNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    handler.onDataflowVisitReturn(returnNode.getTree(), input.getThenStore(), input.getElseStore());
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitLambdaResultExpression(
      LambdaResultExpressionNode resultNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    handler.onDataflowVisitLambdaResultExpression(
        resultNode.getTree(), input.getThenStore(), input.getElseStore());
    SubNodeValues values = values(input);
    Nullness nullness = values.valueOfSubNode(resultNode.getResult());
    return noStoreChanges(nullness, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitStringConversion(
      StringConversionNode stringConversionNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitNarrowingConversion(
      NarrowingConversionNode narrowingConversionNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitWideningConversion(
      WideningConversionNode wideningConversionNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitInstanceOf(
      InstanceOfNode node, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    ReadableUpdates thenUpdates = new ReadableUpdates();
    ReadableUpdates elseUpdates = new ReadableUpdates();
    setNonnullIfAnalyzeable(thenUpdates, node.getOperand());
    ResultingStore thenStore = updateStore(input.getThenStore(), thenUpdates);
    ResultingStore elseStore = updateStore(input.getElseStore(), elseUpdates);
    return new ConditionalTransferResult<>(
        NONNULL,
        thenStore.store,
        elseStore.store,
        thenStore.storeChanged || elseStore.storeChanged);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitTypeCast(
      TypeCastNode node, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    SubNodeValues values = values(input);
    Nullness nullness = hasPrimitiveType(node) ? NONNULL : values.valueOfSubNode(node.getOperand());
    return noStoreChanges(nullness, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitSynchronized(
      SynchronizedNode synchronizedNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitAssertionError(
      AssertionErrorNode assertionErrorNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitThrow(
      ThrowNode throwNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitCase(
      CaseNode caseNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitMethodInvocation(
      MethodInvocationNode node, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    ReadableUpdates thenUpdates = new ReadableUpdates();
    ReadableUpdates elseUpdates = new ReadableUpdates();
    ReadableUpdates bothUpdates = new ReadableUpdates();
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(node.getTree());
    Preconditions.checkNotNull(callee);
    setReceiverNonnull(bothUpdates, node.getTarget().getReceiver(), callee);
    setNullnessForMapCalls(
        node, callee, node.getArguments(), types, values(input), thenUpdates, bothUpdates);
    NullnessHint nullnessHint =
        handler.onDataflowVisitMethodInvocation(
            node, types, values(input), thenUpdates, elseUpdates, bothUpdates);
    Nullness nullness = returnValueNullness(node, input, nullnessHint);
    if (booleanReturnType(node)) {
      ResultingStore thenStore = updateStore(input.getThenStore(), thenUpdates, bothUpdates);
      ResultingStore elseStore = updateStore(input.getElseStore(), elseUpdates, bothUpdates);
      return conditionalResult(
          thenStore.store, elseStore.store, thenStore.storeChanged || elseStore.storeChanged);
    }
    return updateRegularStore(nullness, input, bothUpdates);
  }

  private void setNullnessForMapCalls(
      MethodInvocationNode node,
      Symbol.MethodSymbol callee,
      List<Node> arguments,
      Types types,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    if (AccessPath.isContainsKey(callee, types)) {
      // make sure argument is a variable, and get its element
      AccessPath getAccessPath = AccessPath.getForMapInvocation(node);
      if (getAccessPath != null) {
        // in the then branch, we want the get() call with the same argument to be non-null
        // we assume that the declared target of the get() method will be in the same class
        // as containsKey()
        thenUpdates.set(getAccessPath, NONNULL);
      }
    } else if (AccessPath.isMapPut(callee, types)) {
      AccessPath getAccessPath = AccessPath.getForMapInvocation(node);
      if (getAccessPath != null) {
        Nullness value = inputs.valueOfSubNode(arguments.get(1));
        bothUpdates.set(getAccessPath, value);
      }
    }
  }

  private boolean booleanReturnType(MethodInvocationNode node) {
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(node.getTree());
    return methodSymbol != null && methodSymbol.getReturnType().getTag() == TypeTag.BOOLEAN;
  }

  Nullness returnValueNullness(
      MethodInvocationNode node,
      TransferInput<Nullness, NullnessStore<Nullness>> input,
      NullnessHint returnValueNullnessHint) {
    // NULLABLE is our default
    Nullness nullness;
    if (node != null && returnValueNullnessHint == NullnessHint.FORCE_NONNULL) {
      // A handler says this is definitely non-null; trust it. Note that FORCE_NONNULL is quite
      // dangerous, since it
      // ignores our analysis' own best judgement, so both this value and the annotations that cause
      // it (e.g.
      // @Contract ) should be used with care.
      nullness = NONNULL;
    } else if (node != null && returnValueNullnessHint == NullnessHint.HINT_NULLABLE) {
      // we have a model saying return value is nullable.
      // still, rely on dataflow fact if there is one available
      nullness = input.getRegularStore().valueOfMethodCall(node, types, NULLABLE);
    } else if (node == null
        || methodReturnsNonNull.test(node)
        || !Nullness.hasNullableAnnotation(node.getTarget().getMethod())) {
      // definite non-null return
      nullness = NONNULL;
    } else {
      // rely on dataflow, assuming nullable if no fact
      nullness = input.getRegularStore().valueOfMethodCall(node, types, NULLABLE);
    }
    return nullness;
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitObjectCreation(
      ObjectCreationNode objectCreationNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitMemberReference(
      FunctionalInterfaceNode functionalInterfaceNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitArrayCreation(
      ArrayCreationNode arrayCreationNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitArrayType(
      ArrayTypeNode arrayTypeNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitPrimitiveType(
      PrimitiveTypeNode primitiveTypeNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitClassName(
      ClassNameNode classNameNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitPackageName(
      PackageNameNode packageNameNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitParameterizedType(
      ParameterizedTypeNode parameterizedTypeNode,
      TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore<Nullness>> visitMarker(
      MarkerNode markerNode, TransferInput<Nullness, NullnessStore<Nullness>> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @CheckReturnValue
  private static ResultingStore updateStore(
      NullnessStore<Nullness> oldStore, ReadableUpdates... updates) {
    NullnessStore.Builder<Nullness> builder = oldStore.toBuilder();
    for (ReadableUpdates update : updates) {
      for (Map.Entry<AccessPath, Nullness> entry : update.values.entrySet()) {
        AccessPath key = entry.getKey();
        builder.setInformation(key, entry.getValue());
      }
    }
    NullnessStore<Nullness> newStore = builder.build();
    return new ResultingStore(newStore, !newStore.equals(oldStore));
  }

  private static TransferResult<Nullness, NullnessStore<Nullness>> conditionalResult(
      NullnessStore<Nullness> thenStore, NullnessStore<Nullness> elseStore, boolean storeChanged) {
    return new ConditionalTransferResult<>(NONNULL, thenStore, elseStore, storeChanged);
  }

  /**
   * Provides the previously computed nullness values of descendant nodes. All descendant nodes have
   * already been assigned a value, if only the default of {@code NULLABLE}.
   */
  public interface SubNodeValues {
    public Nullness valueOfSubNode(Node node);
  }

  private static final class ResultingStore {
    final NullnessStore<Nullness> store;
    final boolean storeChanged;

    ResultingStore(NullnessStore<Nullness> store, boolean storeChanged) {
      this.store = store;
      this.storeChanged = storeChanged;
    }
  }

  /** Represents a set of updates to be applied to the NullnessStore. */
  public interface Updates {

    void set(LocalVariableNode node, Nullness value);

    void set(VariableDeclarationNode node, Nullness value);

    void set(FieldAccessNode node, Nullness value);

    void set(MethodInvocationNode node, Nullness value);

    void set(AccessPath ap, Nullness value);
  }

  private final class ReadableUpdates implements Updates {
    final Map<AccessPath, Nullness> values = new HashMap<>();

    @Override
    public void set(LocalVariableNode node, Nullness value) {
      values.put(AccessPath.fromLocal(node), checkNotNull(value));
    }

    @Override
    public void set(VariableDeclarationNode node, Nullness value) {
      values.put(AccessPath.fromVarDecl(node), checkNotNull(value));
    }

    @Override
    public void set(FieldAccessNode node, Nullness value) {
      AccessPath accessPath = AccessPath.fromFieldAccess(node);
      values.put(Preconditions.checkNotNull(accessPath), checkNotNull(value));
    }

    @Override
    public void set(MethodInvocationNode node, Nullness value) {
      AccessPath path = AccessPath.fromMethodCall(node, types);
      values.put(checkNotNull(path), value);
    }

    @Override
    public void set(AccessPath ap, Nullness value) {
      values.put(checkNotNull(ap), value);
    }
  }
}
