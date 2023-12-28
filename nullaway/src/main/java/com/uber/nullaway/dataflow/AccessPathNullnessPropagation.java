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
import static com.uber.nullaway.ASTHelpersBackports.isStatic;
import static com.uber.nullaway.NullabilityUtil.castToNonNull;
import static com.uber.nullaway.Nullness.BOTTOM;
import static com.uber.nullaway.Nullness.NONNULL;
import static com.uber.nullaway.Nullness.NULLABLE;
import static javax.lang.model.element.ElementKind.EXCEPTION_PARAMETER;
import static org.checkerframework.nullaway.javacutil.TreeUtils.elementFromDeclaration;

import com.google.common.base.Preconditions;
import com.google.common.base.VerifyException;
import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.uber.nullaway.CodeAnnotationInfo;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.generics.GenericsChecks;
import com.uber.nullaway.handlers.Handler;
import com.uber.nullaway.handlers.Handler.NullnessHint;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import org.checkerframework.nullaway.dataflow.analysis.ConditionalTransferResult;
import org.checkerframework.nullaway.dataflow.analysis.ForwardTransferFunction;
import org.checkerframework.nullaway.dataflow.analysis.RegularTransferResult;
import org.checkerframework.nullaway.dataflow.analysis.TransferInput;
import org.checkerframework.nullaway.dataflow.analysis.TransferResult;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.node.ArrayAccessNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ArrayCreationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ArrayTypeNode;
import org.checkerframework.nullaway.dataflow.cfg.node.AssertionErrorNode;
import org.checkerframework.nullaway.dataflow.cfg.node.AssignmentNode;
import org.checkerframework.nullaway.dataflow.cfg.node.BitwiseAndNode;
import org.checkerframework.nullaway.dataflow.cfg.node.BitwiseComplementNode;
import org.checkerframework.nullaway.dataflow.cfg.node.BitwiseOrNode;
import org.checkerframework.nullaway.dataflow.cfg.node.BitwiseXorNode;
import org.checkerframework.nullaway.dataflow.cfg.node.BooleanLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.CaseNode;
import org.checkerframework.nullaway.dataflow.cfg.node.CharacterLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ClassDeclarationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ClassNameNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ConditionalAndNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ConditionalNotNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ConditionalOrNode;
import org.checkerframework.nullaway.dataflow.cfg.node.DeconstructorPatternNode;
import org.checkerframework.nullaway.dataflow.cfg.node.DoubleLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.EqualToNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ExplicitThisNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ExpressionStatementNode;
import org.checkerframework.nullaway.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.nullaway.dataflow.cfg.node.FloatLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.FloatingDivisionNode;
import org.checkerframework.nullaway.dataflow.cfg.node.FloatingRemainderNode;
import org.checkerframework.nullaway.dataflow.cfg.node.FunctionalInterfaceNode;
import org.checkerframework.nullaway.dataflow.cfg.node.GreaterThanNode;
import org.checkerframework.nullaway.dataflow.cfg.node.GreaterThanOrEqualNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ImplicitThisNode;
import org.checkerframework.nullaway.dataflow.cfg.node.InstanceOfNode;
import org.checkerframework.nullaway.dataflow.cfg.node.IntegerDivisionNode;
import org.checkerframework.nullaway.dataflow.cfg.node.IntegerLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.IntegerRemainderNode;
import org.checkerframework.nullaway.dataflow.cfg.node.LambdaResultExpressionNode;
import org.checkerframework.nullaway.dataflow.cfg.node.LeftShiftNode;
import org.checkerframework.nullaway.dataflow.cfg.node.LessThanNode;
import org.checkerframework.nullaway.dataflow.cfg.node.LessThanOrEqualNode;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.nullaway.dataflow.cfg.node.LongLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MarkerNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodAccessNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.NarrowingConversionNode;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;
import org.checkerframework.nullaway.dataflow.cfg.node.NotEqualNode;
import org.checkerframework.nullaway.dataflow.cfg.node.NullChkNode;
import org.checkerframework.nullaway.dataflow.cfg.node.NullLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.NumericalAdditionNode;
import org.checkerframework.nullaway.dataflow.cfg.node.NumericalMinusNode;
import org.checkerframework.nullaway.dataflow.cfg.node.NumericalMultiplicationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.NumericalPlusNode;
import org.checkerframework.nullaway.dataflow.cfg.node.NumericalSubtractionNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ObjectCreationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.PackageNameNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ParameterizedTypeNode;
import org.checkerframework.nullaway.dataflow.cfg.node.PrimitiveTypeNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ReturnNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ShortLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.SignedRightShiftNode;
import org.checkerframework.nullaway.dataflow.cfg.node.StringConcatenateNode;
import org.checkerframework.nullaway.dataflow.cfg.node.StringConversionNode;
import org.checkerframework.nullaway.dataflow.cfg.node.StringLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.SuperNode;
import org.checkerframework.nullaway.dataflow.cfg.node.SwitchExpressionNode;
import org.checkerframework.nullaway.dataflow.cfg.node.SynchronizedNode;
import org.checkerframework.nullaway.dataflow.cfg.node.TernaryExpressionNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ThisNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ThrowNode;
import org.checkerframework.nullaway.dataflow.cfg.node.TypeCastNode;
import org.checkerframework.nullaway.dataflow.cfg.node.UnsignedRightShiftNode;
import org.checkerframework.nullaway.dataflow.cfg.node.VariableDeclarationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.WideningConversionNode;

/**
 * transfer functions for our access path nullness dataflow analysis
 *
 * <p>Based on code originally from Error Prone (see {@link
 * com.google.errorprone.dataflow.nullnesspropagation.AbstractNullnessPropagationTransfer} and
 * {@link com.google.errorprone.dataflow.nullnesspropagation.NullnessPropagationTransfer})
 */
public class AccessPathNullnessPropagation
    implements ForwardTransferFunction<Nullness, NullnessStore> {

  private static final boolean NO_STORE_CHANGE = false;

  private static final Supplier<Type> SET_TYPE_SUPPLIER = Suppliers.typeFromString("java.util.Set");

  private static final Supplier<Type> ITERATOR_TYPE_SUPPLIER =
      Suppliers.typeFromString("java.util.Iterator");

  private final Nullness defaultAssumption;

  private final Predicate<MethodInvocationNode> methodReturnsNonNull;

  private final VisitorState state;

  private final AccessPath.AccessPathContext apContext;

  private final Config config;

  private final Handler handler;

  private final NullnessStoreInitializer nullnessStoreInitializer;

  public AccessPathNullnessPropagation(
      Nullness defaultAssumption,
      Predicate<MethodInvocationNode> methodReturnsNonNull,
      VisitorState state,
      AccessPath.AccessPathContext apContext,
      Config config,
      Handler handler,
      NullnessStoreInitializer nullnessStoreInitializer) {
    this.defaultAssumption = defaultAssumption;
    this.methodReturnsNonNull = methodReturnsNonNull;
    this.state = state;
    this.apContext = apContext;
    this.config = config;
    this.handler = handler;
    this.nullnessStoreInitializer = nullnessStoreInitializer;
  }

  private static SubNodeValues values(final TransferInput<Nullness, NullnessStore> input) {
    return new SubNodeValues() {
      @Override
      public Nullness valueOfSubNode(Node node) {
        return castToNonNull(input.getValueOfSubNode(node));
      }
    };
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
  public NullnessStore initialStore(
      UnderlyingAST underlyingAST, List<LocalVariableNode> parameters) {
    return nullnessStoreInitializer.getInitialStore(
        underlyingAST, parameters, handler, state.context, state.getTypes(), config);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitShortLiteral(
      ShortLiteralNode shortLiteralNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitIntegerLiteral(
      IntegerLiteralNode integerLiteralNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitLongLiteral(
      LongLiteralNode longLiteralNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitFloatLiteral(
      FloatLiteralNode floatLiteralNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitDoubleLiteral(
      DoubleLiteralNode doubleLiteralNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitBooleanLiteral(
      BooleanLiteralNode booleanLiteralNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitCharacterLiteral(
      CharacterLiteralNode characterLiteralNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitStringLiteral(
      StringLiteralNode stringLiteralNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitNullLiteral(
      NullLiteralNode nullLiteralNode, TransferInput<Nullness, NullnessStore> input) {
    // let's be sane here and return null
    return new RegularTransferResult<>(Nullness.NULL, input.getRegularStore());
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitNumericalMinus(
      NumericalMinusNode numericalMinusNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitNumericalPlus(
      NumericalPlusNode numericalPlusNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitBitwiseComplement(
      BitwiseComplementNode bitwiseComplementNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitNullChk(
      NullChkNode nullChkNode, TransferInput<Nullness, NullnessStore> input) {
    SubNodeValues values = values(input);
    Nullness nullness =
        hasPrimitiveType(nullChkNode) ? NONNULL : values.valueOfSubNode(nullChkNode.getOperand());
    return noStoreChanges(nullness, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitStringConcatenate(
      StringConcatenateNode stringConcatenateNode, TransferInput<Nullness, NullnessStore> input) {
    // concatenation always returns non-null
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitNumericalAddition(
      NumericalAdditionNode numericalAdditionNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitNumericalSubtraction(
      NumericalSubtractionNode numericalSubtractionNode,
      TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitNumericalMultiplication(
      NumericalMultiplicationNode numericalMultiplicationNode,
      TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitIntegerDivision(
      IntegerDivisionNode integerDivisionNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitFloatingDivision(
      FloatingDivisionNode floatingDivisionNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitIntegerRemainder(
      IntegerRemainderNode integerRemainderNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitFloatingRemainder(
      FloatingRemainderNode floatingRemainderNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitLeftShift(
      LeftShiftNode leftShiftNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitSignedRightShift(
      SignedRightShiftNode signedRightShiftNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitUnsignedRightShift(
      UnsignedRightShiftNode unsignedRightShiftNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitBitwiseAnd(
      BitwiseAndNode bitwiseAndNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitBitwiseOr(
      BitwiseOrNode bitwiseOrNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitBitwiseXor(
      BitwiseXorNode bitwiseXorNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitLessThan(
      LessThanNode lessThanNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitLessThanOrEqual(
      LessThanOrEqualNode lessThanOrEqualNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitGreaterThan(
      GreaterThanNode greaterThanNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitGreaterThanOrEqual(
      GreaterThanOrEqualNode greaterThanOrEqualNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitEqualTo(
      EqualToNode equalToNode, TransferInput<Nullness, NullnessStore> input) {
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
  public TransferResult<Nullness, NullnessStore> visitNotEqual(
      NotEqualNode notEqualNode, TransferInput<Nullness, NullnessStore> input) {
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

    AccessPath leftAP = AccessPath.getAccessPathForNode(realLeftNode, state, apContext);
    if (leftAP != null) {
      equalBranchUpdates.set(leftAP, equalBranchValue);
      notEqualBranchUpdates.set(
          leftAP, leftVal.greatestLowerBound(rightVal.deducedValueWhenNotEqual()));
    }

    AccessPath rightAP = AccessPath.getAccessPathForNode(realRightNode, state, apContext);
    if (rightAP != null) {
      equalBranchUpdates.set(rightAP, equalBranchValue);
      notEqualBranchUpdates.set(
          rightAP, rightVal.greatestLowerBound(leftVal.deducedValueWhenNotEqual()));
    }
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitConditionalAnd(
      ConditionalAndNode conditionalAndNode, TransferInput<Nullness, NullnessStore> input) {
    return conditionalResult(input.getThenStore(), input.getElseStore(), NO_STORE_CHANGE);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitConditionalOr(
      ConditionalOrNode conditionalOrNode, TransferInput<Nullness, NullnessStore> input) {
    return conditionalResult(input.getThenStore(), input.getElseStore(), NO_STORE_CHANGE);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitConditionalNot(
      ConditionalNotNode conditionalNotNode, TransferInput<Nullness, NullnessStore> input) {
    boolean storeChanged = !input.getThenStore().equals(input.getElseStore());
    return conditionalResult(
        /* thenStore= */ input.getElseStore(), /* elseStore= */ input.getThenStore(), storeChanged);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitTernaryExpression(
      TernaryExpressionNode node, TransferInput<Nullness, NullnessStore> input) {
    // The cfg includes assignments of the value of the "then" and "else" sub-expressions to the
    // synthetic variable for the ternary expression.  So, the dataflow result for the ternary
    // expression is just the result for the synthetic variable
    return visitLocalVariable(node.getTernaryExpressionVar(), input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitSwitchExpressionNode(
      SwitchExpressionNode node, TransferInput<Nullness, NullnessStore> input) {
    // The cfg includes assignments of the value of each case body of the switch expression
    // to the switch expression var (a synthetic local variable).  So, the dataflow result
    // for the switch expression is just the result for the switch expression var
    return visitLocalVariable(node.getSwitchExpressionVar(), input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitAssignment(
      AssignmentNode node, TransferInput<Nullness, NullnessStore> input) {
    ReadableUpdates updates = new ReadableUpdates();
    Node rhs = node.getExpression();
    Nullness value = values(input).valueOfSubNode(rhs);
    Node target = node.getTarget();

    if (target instanceof LocalVariableNode
        && !castToNonNull(ASTHelpers.getType(target.getTree())).isPrimitive()) {
      LocalVariableNode localVariableNode = (LocalVariableNode) target;
      updates.set(localVariableNode, value);
      handleEnhancedForOverKeySet(localVariableNode, rhs, input, updates);
    }

    if (target instanceof ArrayAccessNode) {
      setNonnullIfAnalyzeable(updates, ((ArrayAccessNode) target).getArray());
    }

    if (target instanceof FieldAccessNode) {
      FieldAccessNode fieldAccessNode = (FieldAccessNode) target;
      Node receiver = fieldAccessNode.getReceiver();
      setNonnullIfAnalyzeable(updates, receiver);
      if (fieldAccessNode.getElement().getKind().equals(ElementKind.FIELD)
          && !castToNonNull(ASTHelpers.getType(target.getTree())).isPrimitive()) {
        if (receiver instanceof ThisNode || fieldAccessNode.isStatic()) {
          // Guaranteed to produce a valid access path, we call updates.set
          updates.set(fieldAccessNode, value);
        } else {
          // Might not be a valid access path, e.g. it might ultimately be rooted at (new Foo).f or
          // some other expression that's not a valid AP root.
          updates.tryAndSet(fieldAccessNode, value);
        }
      }
    }

    return updateRegularStore(value, input, updates);
  }

  /**
   * Propagates access paths to track iteration over a map's key set using an enhanced-for loop,
   * i.e., code of the form {@code for (Object k: m.keySet()) ...}. For such code, we track access
   * paths to enable reasoning that within the body of the loop, {@code m.get(k)} is non-null.
   *
   * <p>There are two relevant types of assignments in the Checker Framework CFG for such tracking:
   *
   * <ol>
   *   <li>{@code iter#numX = m.keySet().iterator()}, for getting the iterator over a key set for an
   *       enhanced-for loop. After such assignments, we track an access path indicating that {@code
   *       m.get(contentsOf(iter#numX)} is non-null.
   *   <li>{@code k = iter#numX.next()}, which gets the next key in the key set when {@code
   *       iter#numX} was assigned as in case 1. After such assignments, we track the desired {@code
   *       m.get(k)} access path.
   * </ol>
   */
  private void handleEnhancedForOverKeySet(
      LocalVariableNode lhs,
      Node rhs,
      TransferInput<Nullness, NullnessStore> input,
      ReadableUpdates updates) {
    if (isEnhancedForIteratorVariable(lhs)) {
      // Based on the structure of Checker Framework CFGs, rhs must be a call of the form
      // e.iterator().  We check if e is a call to keySet() on a Map, and if so, propagate
      // NONNULL for an access path for e.get(iteratorContents(lhs))
      MethodInvocationNode rhsInv = (MethodInvocationNode) rhs;
      Node mapNode = getMapNodeForKeySetIteratorCall(rhsInv);
      if (mapNode != null) {
        AccessPath mapWithIteratorContentsKey =
            AccessPath.mapWithIteratorContentsKey(mapNode, lhs, apContext);
        if (mapWithIteratorContentsKey != null) {
          // put sanity check here to minimize perf impact
          if (!isCallToMethod(rhsInv, SET_TYPE_SUPPLIER, "iterator")) {
            throw new VerifyException(
                "expected call to iterator(), instead saw "
                    + state.getSourceForNode(rhsInv.getTree()));
          }
          updates.set(mapWithIteratorContentsKey, NONNULL);
        }
      }
    } else if (rhs instanceof MethodInvocationNode) {
      // Check for an assignment lhs = iter#numX.next().  From the structure of Checker Framework
      // CFGs, we know that if iter#numX is the receiver of a call on the rhs of an assignment, it
      // must be a call to next().
      MethodInvocationNode methodInv = (MethodInvocationNode) rhs;
      Node receiver = methodInv.getTarget().getReceiver();
      if (receiver instanceof LocalVariableNode
          && isEnhancedForIteratorVariable((LocalVariableNode) receiver)) {
        // See if we are tracking an access path e.get(iteratorContents(receiver)).  If so, since
        // lhs is being assigned from the iterator contents, propagate NONNULL for an access path
        // e.get(lhs)
        AccessPath mapGetPath =
            input
                .getRegularStore()
                .getMapGetIteratorContentsAccessPath((LocalVariableNode) receiver);
        if (mapGetPath != null) {
          // put sanity check here to minimize perf impact
          if (!isCallToMethod(methodInv, ITERATOR_TYPE_SUPPLIER, "next")) {
            throw new VerifyException(
                "expected call to next(), instead saw "
                    + state.getSourceForNode(methodInv.getTree()));
          }
          updates.set(AccessPath.replaceMapKey(mapGetPath, AccessPath.fromLocal(lhs)), NONNULL);
        }
      }
    }
  }

  /**
   * {@code invocationNode} must represent a call of the form {@code e.iterator()}. If {@code e} is
   * of the form {@code e'.keySet()}, returns the {@code Node} for {@code e'}. Otherwise, returns
   * {@code null}.
   */
  @Nullable
  private Node getMapNodeForKeySetIteratorCall(MethodInvocationNode invocationNode) {
    Node receiver = invocationNode.getTarget().getReceiver();
    if (receiver instanceof MethodInvocationNode) {
      MethodInvocationNode baseInvocation = (MethodInvocationNode) receiver;
      // Check for a call to java.util.Map.keySet()
      if (NullabilityUtil.isMapMethod(
          ASTHelpers.getSymbol(baseInvocation.getTree()), state, "keySet", 0)) {
        // receiver represents the map
        return baseInvocation.getTarget().getReceiver();
      }
    }
    return null;
  }

  /**
   * Checks if an invocation node represents a call to a method on a given type
   *
   * @param invocationNode the invocation node
   * @param containingTypeSupplier supplier for the type containing the method
   * @param methodName name of the method
   * @return true if the invocation node represents a call to the method on the type
   */
  private boolean isCallToMethod(
      MethodInvocationNode invocationNode,
      Supplier<Type> containingTypeSupplier,
      String methodName) {
    MethodInvocationTree invocationTree = invocationNode.getTree();
    Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(invocationTree);
    if (symbol != null && symbol.getSimpleName().contentEquals(methodName)) {
      // NOTE: previously we checked if symbol.owner.type was a subtype of the containing type.
      // However, symbol.owner.type refers to the static type at the call site, in which the target
      // class/interface might be a supertype of the containing type with some Java compilers.
      // Instead, we now check if the static type of the receiver at the invocation is a subtype of
      // the containing type (as this guarantees a method in the containing type or one of its
      // subtypes will be invoked, assuming such a method exists).  See
      // https://github.com/uber/NullAway/issues/866.
      return ASTHelpers.isSubtype(
          ASTHelpers.getReceiverType(invocationTree), containingTypeSupplier.get(state), state);
    }
    return false;
  }

  /**
   * Is {@code varNode} a temporary variable representing the {@code Iterator} for an enhanced for
   * loop? Matched based on the naming scheme used by Checker dataflow.
   */
  private boolean isEnhancedForIteratorVariable(LocalVariableNode varNode) {
    return varNode.getName().startsWith("iter#num");
  }

  private TransferResult<Nullness, NullnessStore> updateRegularStore(
      Nullness value, TransferInput<Nullness, NullnessStore> input, ReadableUpdates updates) {
    ResultingStore newStore = updateStore(input.getRegularStore(), updates);
    return new RegularTransferResult<>(value, newStore.store, newStore.storeChanged);
  }

  /**
   * If node represents a local, field access, or method call we can track, set it to be non-null in
   * the updates
   */
  private void setNonnullIfAnalyzeable(Updates updates, Node node) {
    AccessPath ap = AccessPath.getAccessPathForNode(node, state, apContext);
    if (ap != null) {
      updates.set(ap, NONNULL);
    }
  }

  private static boolean hasPrimitiveType(Node node) {
    return node.getType().getKind().isPrimitive();
  }

  private static boolean hasNonNullConstantValue(LocalVariableNode node) {
    VariableElement element = node.getElement();
    if (element != null) {
      return (element.getConstantValue() != null);
    }
    return false;
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitLocalVariable(
      LocalVariableNode node, TransferInput<Nullness, NullnessStore> input) {
    NullnessStore values = input.getRegularStore();
    Nullness nullness =
        hasPrimitiveType(node) || hasNonNullConstantValue(node)
            ? NONNULL
            : values.valueOfLocalVariable(node, defaultAssumption);
    return new RegularTransferResult<>(nullness, values);
  }

  private static boolean isCatchVariable(VariableDeclarationNode node) {
    VariableElement variableElement = elementFromDeclaration(node.getTree());
    return variableElement != null && variableElement.getKind() == EXCEPTION_PARAMETER;
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitVariableDeclaration(
      VariableDeclarationNode node, TransferInput<Nullness, NullnessStore> input) {
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
  public TransferResult<Nullness, NullnessStore> visitFieldAccess(
      FieldAccessNode fieldAccessNode, TransferInput<Nullness, NullnessStore> input) {
    ReadableUpdates updates = new ReadableUpdates();
    Symbol symbol = Preconditions.checkNotNull(ASTHelpers.getSymbol(fieldAccessNode.getTree()));
    setReceiverNonnull(updates, fieldAccessNode.getReceiver(), symbol);
    Nullness nullness = NULLABLE;
    boolean fieldMayBeNull;
    switch (handler.onDataflowVisitFieldAccess(
        fieldAccessNode,
        symbol,
        state.getTypes(),
        state.context,
        apContext,
        values(input),
        updates)) {
      case HINT_NULLABLE:
        fieldMayBeNull = true;
        break;
      case FORCE_NONNULL:
        fieldMayBeNull = false;
        break;
      case UNKNOWN:
        fieldMayBeNull =
            NullabilityUtil.mayBeNullFieldFromType(symbol, config, getCodeAnnotationInfo(state));
        break;
      default:
        // Should be unreachable unless NullnessHint changes, cases above are exhaustive!
        throw new RuntimeException("Unexpected NullnessHint from handler!");
    }
    if (!fieldMayBeNull) {
      nullness = NONNULL;
    } else {
      nullness = input.getRegularStore().valueOfField(fieldAccessNode, nullness, apContext);
    }
    return updateRegularStore(nullness, input, updates);
  }

  @Nullable private CodeAnnotationInfo codeAnnotationInfo;

  private CodeAnnotationInfo getCodeAnnotationInfo(VisitorState state) {
    if (codeAnnotationInfo == null) {
      codeAnnotationInfo = CodeAnnotationInfo.instance(state.context);
    }
    return codeAnnotationInfo;
  }

  private void setReceiverNonnull(
      AccessPathNullnessPropagation.ReadableUpdates updates, Node receiver, Symbol symbol) {
    if ((symbol != null) && !isStatic(symbol)) {
      setNonnullIfAnalyzeable(updates, receiver);
    }
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitMethodAccess(
      MethodAccessNode methodAccessNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitArrayAccess(
      ArrayAccessNode node, TransferInput<Nullness, NullnessStore> input) {
    ReadableUpdates updates = new ReadableUpdates();
    setNonnullIfAnalyzeable(updates, node.getArray());
    // this is unsound
    return updateRegularStore(defaultAssumption, input, updates);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitImplicitThis(
      ImplicitThisNode implicitThisNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitExplicitThis(
      ExplicitThisNode explicitThisLiteralNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  private TransferResult<Nullness, NullnessStore> noStoreChanges(
      Nullness value, TransferInput<Nullness, NullnessStore> input) {
    return new RegularTransferResult<>(value, input.getRegularStore());
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitSuper(
      SuperNode superNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitReturn(
      ReturnNode returnNode, TransferInput<Nullness, NullnessStore> input) {
    handler.onDataflowVisitReturn(returnNode.getTree(), input.getThenStore(), input.getElseStore());
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitLambdaResultExpression(
      LambdaResultExpressionNode resultNode, TransferInput<Nullness, NullnessStore> input) {
    handler.onDataflowVisitLambdaResultExpression(
        resultNode.getTree(), input.getThenStore(), input.getElseStore());
    SubNodeValues values = values(input);
    Nullness nullness = values.valueOfSubNode(resultNode.getResult());
    return noStoreChanges(nullness, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitStringConversion(
      StringConversionNode stringConversionNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitNarrowingConversion(
      NarrowingConversionNode narrowingConversionNode,
      TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitWideningConversion(
      WideningConversionNode wideningConversionNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitInstanceOf(
      InstanceOfNode node, TransferInput<Nullness, NullnessStore> input) {
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
  public TransferResult<Nullness, NullnessStore> visitTypeCast(
      TypeCastNode node, TransferInput<Nullness, NullnessStore> input) {
    SubNodeValues values = values(input);
    Nullness nullness = hasPrimitiveType(node) ? NONNULL : values.valueOfSubNode(node.getOperand());
    return noStoreChanges(nullness, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitSynchronized(
      SynchronizedNode synchronizedNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitAssertionError(
      AssertionErrorNode assertionErrorNode, TransferInput<Nullness, NullnessStore> input) {

    Node condition = assertionErrorNode.getCondition();

    if (condition == null
        || !(condition instanceof NotEqualNode)
        || !(((NotEqualNode) condition).getRightOperand() instanceof NullLiteralNode)) {
      return noStoreChanges(NULLABLE, input);
    }

    AccessPath accessPath =
        AccessPath.getAccessPathForNode(
            ((NotEqualNode) condition).getLeftOperand(), state, apContext);

    if (accessPath == null) {
      return noStoreChanges(NULLABLE, input);
    }

    ReadableUpdates updates = new ReadableUpdates();
    updates.set(accessPath, NONNULL);

    return updateRegularStore(NULLABLE, input, updates);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitThrow(
      ThrowNode throwNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitCase(
      CaseNode caseNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitMethodInvocation(
      MethodInvocationNode node, TransferInput<Nullness, NullnessStore> input) {
    ReadableUpdates thenUpdates = new ReadableUpdates();
    ReadableUpdates elseUpdates = new ReadableUpdates();
    ReadableUpdates bothUpdates = new ReadableUpdates();
    Symbol.MethodSymbol callee = ASTHelpers.getSymbol(node.getTree());
    Preconditions.checkNotNull(
        callee); // this could be null before https://github.com/google/error-prone/pull/2902
    setReceiverNonnull(bothUpdates, node.getTarget().getReceiver(), callee);
    setNullnessForMapCalls(
        node, callee, node.getArguments(), values(input), thenUpdates, bothUpdates);
    NullnessHint nullnessHint =
        handler.onDataflowVisitMethodInvocation(
            node, callee, state, apContext, values(input), thenUpdates, elseUpdates, bothUpdates);
    Nullness nullness = returnValueNullness(node, input, nullnessHint);
    if (booleanReturnType(callee)) {
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
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    if (AccessPath.isContainsKey(callee, state)) {
      // make sure argument is a variable, and get its element
      AccessPath getAccessPath = AccessPath.getForMapInvocation(node, state, apContext);
      if (getAccessPath != null) {
        // in the then branch, we want the get() call with the same argument to be non-null
        // we assume that the declared target of the get() method will be in the same class
        // as containsKey()
        thenUpdates.set(getAccessPath, NONNULL);
      }
    } else if (AccessPath.isMapPut(callee, state)) {
      AccessPath getAccessPath = AccessPath.getForMapInvocation(node, state, apContext);
      if (getAccessPath != null) {
        Nullness value = inputs.valueOfSubNode(arguments.get(1));
        bothUpdates.set(getAccessPath, value);
      }
    } else if (AccessPath.isMapComputeIfAbsent(callee, state)) {
      AccessPath getAccessPath = AccessPath.getForMapInvocation(node, state, apContext);
      if (getAccessPath != null) {
        // TODO: For now, Function<K, V> implies a @NonNull V. We need to revisit this once we
        // support generics, but we do include a couple defensive tests below.
        if (arguments.size() < 2) {
          return;
        }
        Node funcNode = arguments.get(1);
        if (!funcNode.getType().getKind().equals(TypeKind.DECLARED)) {
          return;
        }
        Type.ClassType classType = (Type.ClassType) funcNode.getType();
        if (classType.getTypeArguments().size() != 2) {
          return;
        }
        Type functionReturnType = classType.getTypeArguments().get(1);
        // Unfortunately, functionReturnType.tsym seems to elide annotation info, so we can't call
        // the Nullness.* methods that deal with Symbol. We might have better APIs for this kind of
        // check once we have real generics support.
        if (!Nullness.hasNullableAnnotation(
            functionReturnType.getAnnotationMirrors().stream(), config)) {
          bothUpdates.set(getAccessPath, NONNULL);
        }
      }
    }
  }

  private static boolean booleanReturnType(Symbol.MethodSymbol methodSymbol) {
    return methodSymbol.getReturnType().getTag() == TypeTag.BOOLEAN;
  }

  Nullness returnValueNullness(
      MethodInvocationNode node,
      TransferInput<Nullness, NullnessStore> input,
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
      nullness = input.getRegularStore().valueOfMethodCall(node, state, NULLABLE, apContext);
    } else if (node == null
        || methodReturnsNonNull.test(node)
        || (!Nullness.hasNullableAnnotation((Symbol) node.getTarget().getMethod(), config)
            && !genericReturnIsNullable(node))) {
      // definite non-null return
      nullness = NONNULL;
    } else {
      // rely on dataflow, assuming nullable if no fact
      nullness = input.getRegularStore().valueOfMethodCall(node, state, NULLABLE, apContext);
    }
    return nullness;
  }

  /**
   * Computes the nullability of a generic return type in the context of some receiver type at an
   * invocation.
   *
   * @param node the invocation node
   * @return nullability of the return type in the context of the type of the receiver argument at
   *     {@code node}
   */
  private boolean genericReturnIsNullable(MethodInvocationNode node) {
    if (node != null && config.isJSpecifyMode()) {
      MethodInvocationTree tree = node.getTree();
      if (tree != null) {
        Nullness nullness =
            GenericsChecks.getGenericReturnNullnessAtInvocation(
                ASTHelpers.getSymbol(tree), tree, state, config);
        return nullness.equals(NULLABLE);
      }
    }
    return false;
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitObjectCreation(
      ObjectCreationNode objectCreationNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitMemberReference(
      FunctionalInterfaceNode functionalInterfaceNode,
      TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitArrayCreation(
      ArrayCreationNode arrayCreationNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NONNULL, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitArrayType(
      ArrayTypeNode arrayTypeNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitPrimitiveType(
      PrimitiveTypeNode primitiveTypeNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitClassName(
      ClassNameNode classNameNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitClassDeclaration(
      ClassDeclarationNode classDeclarationNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitExpressionStatement(
      ExpressionStatementNode expressionStatementNode,
      TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitDeconstructorPattern(
      DeconstructorPatternNode deconstructorPatternNode,
      TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitPackageName(
      PackageNameNode packageNameNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitParameterizedType(
      ParameterizedTypeNode parameterizedTypeNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @Override
  public TransferResult<Nullness, NullnessStore> visitMarker(
      MarkerNode markerNode, TransferInput<Nullness, NullnessStore> input) {
    return noStoreChanges(NULLABLE, input);
  }

  @CheckReturnValue
  private static ResultingStore updateStore(NullnessStore oldStore, ReadableUpdates... updates) {
    NullnessStore.Builder builder = oldStore.toBuilder();
    for (ReadableUpdates update : updates) {
      for (Map.Entry<AccessPath, Nullness> entry : update.values.entrySet()) {
        AccessPath key = entry.getKey();
        builder.setInformation(key, entry.getValue());
      }
    }
    NullnessStore newStore = builder.build();
    return new ResultingStore(newStore, !newStore.equals(oldStore));
  }

  private static TransferResult<Nullness, NullnessStore> conditionalResult(
      NullnessStore thenStore, NullnessStore elseStore, boolean storeChanged) {
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
    final NullnessStore store;
    final boolean storeChanged;

    ResultingStore(NullnessStore store, boolean storeChanged) {
      this.store = store;
      this.storeChanged = storeChanged;
    }
  }

  /** Represents a set of updates to be applied to the NullnessStore. */
  public interface Updates {

    void set(LocalVariableNode node, Nullness value);

    void set(VariableDeclarationNode node, Nullness value);

    void set(FieldAccessNode node, Nullness value);

    /** Like set, but ignore if node does not produce a valid access path */
    void tryAndSet(FieldAccessNode node, Nullness value);

    void set(MethodInvocationNode node, Nullness value);

    void set(AccessPath ap, Nullness value);
  }

  private final class ReadableUpdates implements Updates {
    final Map<AccessPath, Nullness> values = new HashMap<>();

    @Override
    public void set(LocalVariableNode node, Nullness value) {
      values.put(AccessPath.fromLocal(node), value);
    }

    @Override
    public void set(VariableDeclarationNode node, Nullness value) {
      values.put(AccessPath.fromVarDecl(node), value);
    }

    @Override
    public void set(FieldAccessNode node, Nullness value) {
      AccessPath accessPath = AccessPath.fromFieldAccess(node, apContext);
      values.put(checkNotNull(accessPath), value);
    }

    @Override
    public void tryAndSet(FieldAccessNode node, Nullness value) {
      AccessPath accessPath = AccessPath.fromFieldAccess(node, apContext);
      if (accessPath == null) {
        return;
      }
      values.put(accessPath, value);
    }

    @Override
    public void set(MethodInvocationNode node, Nullness value) {
      AccessPath path = AccessPath.fromMethodCall(node, state, apContext);
      values.put(checkNotNull(path), value);
    }

    @Override
    public void set(AccessPath ap, Nullness value) {
      values.put(checkNotNull(ap), value);
    }
  }
}
