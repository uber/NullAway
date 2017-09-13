/*
 * Copyright (c) 2017 Uber Technologies, Inc.
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

package com.uber.nullaway;

import com.google.auto.service.AutoService;
import com.google.auto.value.AutoValue;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.dataflow.nullnesspropagation.Nullness;
import com.google.errorprone.dataflow.nullnesspropagation.TrustingNullnessAnalysis;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.handlers.Handler;
import com.uber.nullaway.handlers.Handlers;

import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.javacutil.AnnotationUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;

import static com.google.errorprone.BugPattern.Category.JDK;
import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;
import static com.sun.source.tree.Tree.Kind.EXPRESSION_STATEMENT;
import static com.sun.source.tree.Tree.Kind.IDENTIFIER;
import static com.sun.source.tree.Tree.Kind.PARENTHESIZED;
import static com.sun.source.tree.Tree.Kind.TYPE_CAST;

/**
 * Checker for nullability errors.  It assumes that any field,
 * method parameter, or return type that may be null is annotated with
 * {@link Nullable},
 * and then checks the following rules:
 * <ul>
 * <li>no assignment of a nullable expression into a non-null field</li>
 * <li>no passing a nullable expression into a non-null parameter</li>
 * <li>no returning a nullable expression from a method with non-null return type</li>
 * <li>no field access or method invocation on an expression that is nullable</li>
 * </ul>
 * <p>
 * This checker also detects errors related to field initialization.  For any @NonNull instance field <code>f</code>,
 * this checker ensures that at least one of the following cases holds:
 * <ol>
 * <li><code>f</code> is directly initialized at its declaration</li>
 * <li><code>f</code> is always initialized in all constructors</li>
 * <li><code>f</code> is always initialized in some method annotated with @Initializer</li>
 * </ol>
 * <p>
 * For any @NonNull static field <code>f</code>, this checker ensures that at least one of the following cases holds:
 * <ol>
 * <li><code>f</code> is directly initialized at its declaration</li>
 * <li><code>f</code> is always initialized in some static initializer block</li>
 * </ol>
 */
@AutoService(BugChecker.class)
@BugPattern(
        name = "NullAway",
        altNames = { "CheckNullabilityTypes" },
        summary = "Nullability type error.",
        category = JDK,
        severity = WARNING)
public class NullAway extends BugChecker implements
        BugChecker.MethodInvocationTreeMatcher, BugChecker.AssignmentTreeMatcher,
        BugChecker.MemberSelectTreeMatcher, BugChecker.ArrayAccessTreeMatcher, BugChecker.ReturnTreeMatcher,
        BugChecker.ClassTreeMatcher, BugChecker.MethodTreeMatcher, BugChecker.VariableTreeMatcher,
        BugChecker.NewClassTreeMatcher, BugChecker.BinaryTreeMatcher, BugChecker.UnaryTreeMatcher,
        BugChecker.ConditionalExpressionTreeMatcher, BugChecker.IfTreeMatcher, BugChecker.WhileLoopTreeMatcher,
        BugChecker.ForLoopTreeMatcher, BugChecker.LambdaExpressionTreeMatcher {

    private static final Matcher<ExpressionTree> THIS_MATCHER = new Matcher<ExpressionTree>() {
        @Override
        public boolean matches(ExpressionTree expressionTree, VisitorState state) {
            return isThisIdentifier(expressionTree);
        }
    };

    private final Predicate<MethodInvocationNode> nonAnnotatedMethod = new Predicate<MethodInvocationNode>() {
        @Override
        public boolean apply(@Nullable MethodInvocationNode invocationNode) {
            return invocationNode == null || fromUnannotatedPackage(ASTHelpers.getSymbol(invocationNode.getTree()));
        }

    };

    /**
     * should we match within the current class?
     */
    private boolean matchWithinClass = true;

    private Config config;

    /**
     * Fields for which we should report an initialization error when we match them.
     * We report an error on a field when there are no constructors in the class.
     */
    private final Set<Element> fieldsWithInitializationErrors = new LinkedHashSet<>();

    /**
     * Map from initializer elements (including constructors) to the error message we should when that constructor
     * is matched.
     */
    private final Map<Element, String> initializerErrors = new LinkedHashMap<>();

    /**
     * The handler passed to our analysis (usually a {@code CompositeHandler} including handlers for various APIs.
     */
    private Handler handler = Handlers.buildDefault();

    /**
     * Error Prone requires us to have an empty constructor for each Plugin, in addition to the constructor taking
     * an ErrorProneFlags object. This constructor should not be used anywhere else. Checker objects
     * constructed with this constructor will fail with IllegalStateException if ever used for analysis.
     */
    public NullAway() {
        config = new DummyOptionsConfig();
    }

    public NullAway(ErrorProneFlags flags) {
        config = new ErrorProneCLIFlagsConfig(flags);
        // workaround for Checker Framework static state bug;
        // See https://github.com/typetools/checker-framework/issues/1482
        AnnotationUtils.clear();
    }

    @Override
    public String linkUrl() {
        return "http://t.uber.com/nullaway ";
    }

    /**
     * We are trying to see if (1) we are in a method guaranteed to return
     * something non-null, and (2) this return statement can return something
     * null.
     */
    @Override
    public Description matchReturn(ReturnTree tree, VisitorState state) {
        if (!matchWithinClass) {
            return Description.NO_MATCH;
        }
        handler.onMatchReturn(this, tree, state);
        ExpressionTree retExpr = tree.getExpression();
        // let's do quick checks on returned expression first
        if (retExpr == null) {
            return Description.NO_MATCH;
        }
        // now let's check the enclosing method
        TreePath enclosingMethodOrLambda = NullabilityUtil.findEnclosingMethodOrLambda(state.getPath());
        if (enclosingMethodOrLambda == null) {
            throw new RuntimeException("no enclosing method!");
        }
        Tree leaf = enclosingMethodOrLambda.getLeaf();
        Symbol.MethodSymbol methodSymbol;
        if (leaf instanceof MethodTree) {
            MethodTree enclosingMethod = (MethodTree) leaf;
            methodSymbol = ASTHelpers.getSymbol(enclosingMethod);
        } else {
            // we have a lambda
            methodSymbol = NullabilityUtil.getFunctionalInterfaceMethod((LambdaExpressionTree) leaf);
        }
        return checkReturnExpression(tree, retExpr, methodSymbol, state);
    }

    @Override
    public Description matchMethodInvocation(
            MethodInvocationTree tree, VisitorState state) {
        if (!matchWithinClass) {
            return Description.NO_MATCH;
        }
        final Symbol.MethodSymbol methodSymbol =
                ASTHelpers.getSymbol(tree);
        if (methodSymbol == null) {
            throw new RuntimeException("not expecting unresolved method here");
        }
        handler.onMatchMethodInvocation(this, tree, state, methodSymbol);
        // assuming this list does not include the receiver
        List<? extends ExpressionTree> actualParams = tree.getArguments();
        return handleInvocation(state, methodSymbol, actualParams);
    }

    @Override
    public Description matchNewClass(
            NewClassTree tree, VisitorState state) {
        if (!matchWithinClass) {
            return Description.NO_MATCH;
        }
        final Symbol.MethodSymbol methodSymbol =
                ASTHelpers.getSymbol(tree);
        if (methodSymbol == null) {
            throw new RuntimeException("not expecting unresolved method here");
        }
        List<? extends ExpressionTree> actualParams = tree.getArguments();
        return handleInvocation(state, methodSymbol, actualParams);
    }

    @Override
    public Description matchAssignment(AssignmentTree tree, VisitorState state) {
        if (!matchWithinClass) {
            return Description.NO_MATCH;
        }
        Type lhsType = ASTHelpers.getType(tree.getVariable());
        if (lhsType != null && lhsType.isPrimitive()) {
            return doUnboxingCheck(state, tree.getExpression());
        }
        Symbol assigned = ASTHelpers.getSymbol(tree.getVariable());
        if (assigned == null
                || assigned.getKind() != ElementKind.FIELD) {
            // not a field of nullable type
            return Description.NO_MATCH;
        }

        if (TrustingNullnessAnalysis.hasNullableAnnotation(assigned)) {
            // field already annotated
            return Description.NO_MATCH;
        }
        ExpressionTree expression = tree.getExpression();
        if (mayBeNullExpr(state, expression)) {
            return createErrorDescription(
                    tree,
                    "assigning @Nullable expression to @NonNull field",
                    state.getPath()
            );
        }
        return Description.NO_MATCH;
    }

    @Override
    public Description matchArrayAccess(ArrayAccessTree tree, VisitorState state) {
        return matchDereference(tree.getExpression(), tree, state);
    }

    @Override
    public Description matchMemberSelect(
            MemberSelectTree tree, VisitorState state) {
        Symbol symbol = ASTHelpers.getSymbol(tree);
        // some checks for cases where we know it is not
        // a null dereference
        if (symbol == null || symbol.getSimpleName().toString().equals("class")
                || symbol.isEnum()) {
            return Description.NO_MATCH;
        }

        return matchDereference(tree.getExpression(), tree, state);
    }

    @Override
    public Description matchMethod(MethodTree tree, VisitorState state) {
        if (!matchWithinClass) {
            return Description.NO_MATCH;
        }
        // if the method is overriding some other method,
        // check that nullability annotations are consistent with
        // overridden method (if overridden method is in an annotated
        // package)
        Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(tree);
        if (initializerErrors.containsKey(methodSymbol)) {
            return reportInitializerError(methodSymbol, tree);
        }
        handler.onMatchMethod(this, tree, state, methodSymbol);
        boolean isOverriding = ASTHelpers.hasAnnotation(methodSymbol, Override.class, state);
        boolean exhaustiveOverride = config.exhaustiveOverride();
        if (isOverriding || !exhaustiveOverride) {
            Symbol.MethodSymbol closestOverriddenMethod = getClosestOverriddenMethod(methodSymbol, state.getTypes());
            if (closestOverriddenMethod == null) {
                return Description.NO_MATCH;
            }
            if (fromUnannotatedPackage(closestOverriddenMethod)) {
                return Description.NO_MATCH;
            }
            // if the super method returns nonnull,
            // overriding method better not return nullable
            if (!TrustingNullnessAnalysis.hasNullableAnnotation(closestOverriddenMethod)) {
                if (TrustingNullnessAnalysis.hasNullableAnnotation(methodSymbol)) {
                    String message = "method returns @Nullable, but superclass method "
                            + ASTHelpers.enclosingClass(closestOverriddenMethod) + "."
                            + closestOverriddenMethod.toString()
                            + " returns @NonNull";
                    return createErrorDescription(tree, message, tree);
                }
            }
            // if any parameter in the super method is annotated @Nullable,
            // overriding method cannot assume @Nonnull
            return checkParamOverriding(tree, tree.getParameters(), closestOverriddenMethod, false);

        }
        return Description.NO_MATCH;
    }

    private Description checkParamOverriding(
            Tree tree,
            List<? extends VariableTree> methodParams,
            Symbol.MethodSymbol closestOverriddenMethod,
            boolean isLambda) {
        com.sun.tools.javac.util.List<VarSymbol> superParamSymbols = closestOverriddenMethod.getParameters();
        for (int i = 0; i < superParamSymbols.size(); i++) {
            VarSymbol superParam = superParamSymbols.get(i);
            if (TrustingNullnessAnalysis.hasNullableAnnotation(superParam)) {
                VariableTree param = methodParams.get(i);
                VarSymbol paramSymbol = ASTHelpers.getSymbol(param);
                // in the case where we have a parameter of a lambda expression, we do
                // *not* force the parameter to be annotated with @Nullable; instead we "inherit"
                // nullability from the corresponding functional interface method
                if (!TrustingNullnessAnalysis.hasNullableAnnotation(paramSymbol)
                        && !(isLambda && !NullabilityUtil.lambdaParamIsExplicitlyTyped(param))) {
                    String message = "parameter " + param.getName()
                            + " is @NonNull, but parameter in "
                            + (isLambda ? "functional interface " : "superclass ")
                            + "method " + ASTHelpers.enclosingClass(closestOverriddenMethod) + "."
                            + closestOverriddenMethod.toString()
                            + " is @Nullable";
                    return createErrorDescription(param, message, tree);
                }
            }
        }
        return Description.NO_MATCH;
    }

    private Description checkReturnExpression(
            Tree tree,
            ExpressionTree retExpr,
            Symbol.MethodSymbol methodSymbol,
            VisitorState state) {
        Type returnType = methodSymbol.getReturnType();
        if (returnType.isPrimitive()) {
            // check for unboxing
            return doUnboxingCheck(state, retExpr);
        }
        if (returnType.toString().equals("java.lang.Void")) {
            return Description.NO_MATCH;
        }
        if (fromUnannotatedPackage(methodSymbol)
                || TrustingNullnessAnalysis.hasNullableAnnotation(methodSymbol)) {
            return Description.NO_MATCH;
        }
        if (mayBeNullExpr(state, retExpr)) {
            return createErrorDescription(
                    tree,
                    "returning @Nullable expression from method with @NonNull return type" + methodSymbol,
                    state.getPath());
        }
        return Description.NO_MATCH;
    }

    @Override
    public Description matchLambdaExpression(LambdaExpressionTree tree, VisitorState state) {
        Symbol.MethodSymbol methodSymbol = NullabilityUtil.getFunctionalInterfaceMethod(tree);
        Description description = checkParamOverriding(tree, tree.getParameters(), methodSymbol, true);
        if (description != Description.NO_MATCH) {
            return description;
        }
        if (tree.getBodyKind() == LambdaExpressionTree.BodyKind.EXPRESSION) {
            ExpressionTree resExpr = (ExpressionTree) tree.getBody();
            return checkReturnExpression(tree, resExpr, methodSymbol, state);
        }
        return Description.NO_MATCH;
    }

    @Override
    public Description matchVariable(VariableTree tree, VisitorState state) {
        if (!matchWithinClass) {
            return Description.NO_MATCH;
        }
        VarSymbol symbol = ASTHelpers.getSymbol(tree);
        if (symbol.type.isPrimitive() && tree.getInitializer() != null) {
            return doUnboxingCheck(state, tree.getInitializer());
        }
        if (!symbol.getKind().equals(ElementKind.FIELD)) {
            return Description.NO_MATCH;
        }
        if (fieldsWithInitializationErrors.contains(symbol)) {
            // be careful to delete entry to avoid leaks
            fieldsWithInitializationErrors.remove(symbol);
            return createErrorDescription(tree, "@NonNull field " + symbol + " not initialized", tree);
        }
        ExpressionTree initializer = tree.getInitializer();
        if (initializer != null) {
            if (!symbol.type.isPrimitive() && !skipDueToFieldAnnotation(symbol)) {
                if (mayBeNullExpr(state, initializer)) {
                    return createErrorDescription(
                            tree, "assigning @Nullable expression to @NonNull field", tree);
                }
            }
        }
        return Description.NO_MATCH;
    }

    @Override
    public Description matchClass(ClassTree tree, VisitorState state) {
        // check if the class is excluded according to the filter
        // if so, set the flag to match within the class to false
        // NOTE: for this mechanism to work, we rely on the enclosing ClassTree
        // always being visited before code within that class.  We also
        // assume that a single checker object is not being
        // used from multiple threads
        Symbol.ClassSymbol classSymbol = ASTHelpers.getSymbol(tree);
        // we don't want to update the flag for nested classes.
        // ideally we would keep a stack of flags to handle nested types,
        // but this is not easy within the Error Prone APIs
        if (!classSymbol.getNestingKind().isNested()) {
            matchWithinClass = !isExcludedClass(classSymbol, state);
            // since we are processing a new top-level class, invalidate any cached
            // results for previous classes
            handler.onMatchTopLevelClass(this, tree, state, classSymbol);
            getNullnessAnalysis(state).invalidateCaches();
        }
        if (matchWithinClass) {
            checkFieldInitialization(tree, state);
        }
        return Description.NO_MATCH;
    }

    // UNBOXING CHECKS

    @Override
    public Description matchBinary(BinaryTree tree, VisitorState state) {
        ExpressionTree leftOperand = tree.getLeftOperand();
        ExpressionTree rightOperand = tree.getRightOperand();
        Type leftType = ASTHelpers.getType(leftOperand);
        Type rightType = ASTHelpers.getType(rightOperand);
        if (leftType == null || rightType == null) {
            throw new RuntimeException();
        }
        if (leftType.isPrimitive() && !rightType.isPrimitive()) {
            return doUnboxingCheck(state, rightOperand);
        }
        if (rightType.isPrimitive() && !leftType.isPrimitive()) {
            return doUnboxingCheck(state, leftOperand);
        }
        return Description.NO_MATCH;
    }

    @Override
    public Description matchUnary(UnaryTree tree, VisitorState state) {
        return doUnboxingCheck(state, tree.getExpression());
    }

    @Override
    public Description matchConditionalExpression(
            ConditionalExpressionTree tree, VisitorState state) {
        return doUnboxingCheck(state, tree.getCondition());
    }

    @Override
    public Description matchIf(IfTree tree, VisitorState state) {
        return doUnboxingCheck(state, tree.getCondition());
    }

    @Override
    public Description matchWhileLoop(WhileLoopTree tree, VisitorState state) {
        return doUnboxingCheck(state, tree.getCondition());
    }

    @Override
    public Description matchForLoop(ForLoopTree tree, VisitorState state) {
        if (tree.getCondition() != null) {
            return doUnboxingCheck(state, tree.getCondition());
        }
        return Description.NO_MATCH;
    }

    /**
     * if any expression has non-primitive type, we should check that it can't
     * be null as it is getting unboxed
     * @param expressions expressions to check
     * @return error Description if an error is found, otherwise NO_MATCH
     */
    private Description doUnboxingCheck(VisitorState state, ExpressionTree... expressions) {
        for (ExpressionTree tree: expressions) {
            Type type = ASTHelpers.getType(tree);
            if (type == null) {
                throw new RuntimeException("was not expecting null type");
            }
            if (!type.isPrimitive()) {
                if (mayBeNullExpr(state, tree)) {
                    return createErrorDescription(tree, "unboxing of a @Nullable value", state.getPath());
                }
            }
        }
        return Description.NO_MATCH;
    }

    /**
     * handle either a method invocation or a 'new' invocation
     *
     * @param state        visitor state
     * @param methodSymbol symbol for invoked method
     * @param actualParams parameters passed at call
     * @return description of error or NO_MATCH if no error
     */
    private Description handleInvocation(
            VisitorState state,
            Symbol.MethodSymbol methodSymbol,
            List<? extends ExpressionTree> actualParams) {
        ImmutableSet<Integer> nonNullPositions = null;
        if (fromUnannotatedPackage(methodSymbol)) {
            nonNullPositions = handler.onUnannotatedInvocationGetNonNullPositions(this, state,
                    methodSymbol, actualParams, ImmutableSet.<Integer>of());
        }
        if (nonNullPositions == null) {
            ImmutableSet.Builder<Integer> builder = ImmutableSet.builder();
            // compute which arguments are @NonNull
            List<VarSymbol> formalParams = methodSymbol.getParameters();
            for (int i = 0; i < formalParams.size(); i++) {
                if (i == formalParams.size() - 1 && methodSymbol.isVarArgs()) {
                    // eventually, handle this case properly.  I *think* a null
                    // array could be passed in incorrectly.  For now, punt
                    continue;
                }
                VarSymbol param = formalParams.get(i);
                if (param.type.isPrimitive()) {
                    Description unboxingCheck = doUnboxingCheck(state, actualParams.get(i));
                    if (unboxingCheck != Description.NO_MATCH) {
                        return unboxingCheck;
                    } else {
                        continue;
                    }
                }
                boolean nullable = TrustingNullnessAnalysis.hasNullableAnnotation(param);
                if (!nullable) {
                    builder.add(i);
                }
            }
            nonNullPositions = builder.build();
        }
        if (nonNullPositions.isEmpty()) {
            return Description.NO_MATCH;
        }
        // now actually check the arguments
        for (int argPos : nonNullPositions) {
            // make sure we are passing a non-null value
            ExpressionTree actual = actualParams.get(argPos);
            if (mayBeNullExpr(state, actual)) {
                return createErrorDescription(
                        actual,
                        "passing @Nullable parameter '"
                                + actual.toString() + "' where @NonNull is required",
                        state.getPath());
            }
        }
        // NOTE: the case of an invocation on a possibly-null reference
        // is handled by matchMemberSelect()
        return Description.NO_MATCH;
    }

    /**
     * check that all @NonNull fields of the class are properly initialized
     *
     * @param tree  the class
     * @param state visitor state
     */
    private void checkFieldInitialization(ClassTree tree, VisitorState state) {
        FieldInitEntities entities = collectEntities(tree, state);
        Set<Symbol> notInitializedInConstructors;
        SetMultimap<MethodTree, Symbol> constructorInitInfo;
        if (entities.constructors().isEmpty()) {
            constructorInitInfo = null;
            notInitializedInConstructors = entities.nonnullInstanceFields();
        } else {
            constructorInitInfo = checkConstructorInitialization(entities,
                    state);
            notInitializedInConstructors = ImmutableSet.copyOf(constructorInitInfo.values());
        }
        Set<Symbol> notInitializedAtAll = assignedInAnyInitializer(entities, notInitializedInConstructors, state);
        SetMultimap<Element, Element> errorFieldsForInitializer = LinkedHashMultimap.create();
        // non-null if we have a single initializer method
        Symbol.MethodSymbol singleInitializerMethod = null;
        if (entities.initializerMethods().size() == 1) {
            singleInitializerMethod = ASTHelpers.getSymbol(entities.initializerMethods().iterator().next());
        }
        for (Symbol uninitField : notInitializedAtAll) {
            if (singleInitializerMethod != null) {
                // report it on the initializer
                errorFieldsForInitializer.put(singleInitializerMethod, uninitField);
            } else if (constructorInitInfo == null) {
                // report it on the field
                fieldsWithInitializationErrors.add(uninitField);
            } else {
                // report it on each constructor that does not initialize it
                for (MethodTree methodTree : constructorInitInfo.keySet()) {
                    Set<Symbol> uninitFieldsForConstructor = constructorInitInfo.get(methodTree);
                    if (uninitFieldsForConstructor.contains(uninitField)) {
                        errorFieldsForInitializer.put(
                                ASTHelpers.getSymbol(methodTree),
                                uninitField);
                    }
                }
            }
        }
        for (Element constructorElement : errorFieldsForInitializer.keySet()) {
            initializerErrors.put(
                    constructorElement,
                    errMsgForInitializer(errorFieldsForInitializer.get(constructorElement))
            );
        }
    }

    /**
     * @param entities                     relevant entities from class
     * @param notInitializedInConstructors those fields not initialized in some constructor
     * @param state
     * @return those fields from notInitializedInConstructors that are not initialized in
     * any initializer method
     */
    private Set<Symbol> assignedInAnyInitializer(
            FieldInitEntities entities,
            Set<Symbol> notInitializedInConstructors,
            VisitorState state) {
        Trees trees = Trees.instance(JavacProcessingEnvironment.instance(state.context));
        Set<Element> initInSomeInitializer = new LinkedHashSet<>();
        for (MethodTree initMethodTree : entities.initializerMethods()) {
            if (initMethodTree.getBody() == null) {
                continue;
            }
            AccessPathNullnessAnalysis nullnessAnalysis = getNullnessAnalysis(state);
            Set<Element> nonnullAtExit = nullnessAnalysis.getNonnullFieldsOfReceiverAtExit(
                    new TreePath(state.getPath(), initMethodTree), state.context);
            initInSomeInitializer.addAll(nonnullAtExit);
            Set<Element> safeInitMethods = getSafeInitMethods(initMethodTree, state);
            addGuaranteedNonNullFromInvokes(state, trees, safeInitMethods, nullnessAnalysis, initInSomeInitializer);
        }
        Set<Symbol> result = new LinkedHashSet<>();
        for (Symbol fieldSymbol : notInitializedInConstructors) {
            if (!initInSomeInitializer.contains(fieldSymbol)) {
                result.add(fieldSymbol);
            }
        }
        return result;
    }

    private SetMultimap<MethodTree, Symbol> checkConstructorInitialization(
            FieldInitEntities entities, VisitorState state) {
        SetMultimap<MethodTree, Symbol> result = LinkedHashMultimap.create();
        Set<Symbol> nonnullInstanceFields = entities.nonnullInstanceFields();
        Trees trees = Trees.instance(JavacProcessingEnvironment.instance(state.context));
        for (MethodTree constructor : entities.constructors()) {
            // if the constructor starts with a call to 'this', ignore it, as we'll
            // check the other constructor
            BlockTree body = constructor.getBody();
            List<? extends StatementTree> statements = body.getStatements();
            if (statements.size() > 0) {
                StatementTree statementTree = statements.get(0);
                if (isThisCall(statementTree, state)) {
                    continue;
                }
            }
            Set<Element> safeInitMethods = getSafeInitMethods(constructor, state);
            AccessPathNullnessAnalysis nullnessAnalysis = getNullnessAnalysis(state);
            Set<Element> guaranteedNonNull = new LinkedHashSet<>();
            guaranteedNonNull.addAll(nullnessAnalysis.getNonnullFieldsOfReceiverAtExit(
                    new TreePath(state.getPath(), constructor), state.context));
            addGuaranteedNonNullFromInvokes(state, trees, safeInitMethods, nullnessAnalysis, guaranteedNonNull);
            for (Symbol fieldSymbol : nonnullInstanceFields) {
                if (!guaranteedNonNull.contains(fieldSymbol)) {
                    result.put(constructor, fieldSymbol);
                }
            }

        }
        return result;
    }

    private void addGuaranteedNonNullFromInvokes(
            VisitorState state,
            Trees trees,
            Set<Element> safeInitMethods,
            AccessPathNullnessAnalysis nullnessAnalysis, Set<Element> guaranteedNonNull) {
        for (Element invoked : safeInitMethods) {
            Tree invokedTree = trees.getTree(invoked);
            guaranteedNonNull.addAll(nullnessAnalysis.getNonnullFieldsOfReceiverAtExit(
                    new TreePath(state.getPath(), invokedTree), state.context));
        }
    }

    /**
     * @param methodTree the method
     * @param state      visitor state
     * @return Elements of safe init methods that are invoked as top-level statements in the method
     */
    private Set<Element> getSafeInitMethods(MethodTree methodTree, VisitorState state) {
        Set<Element> result = new LinkedHashSet<>();
        List<? extends StatementTree> statements = methodTree.getBody().getStatements();
        for (StatementTree stmt : statements) {
            Element privMethodElem = getInvokeOfSafeInitMethod(stmt, ASTHelpers.getSymbol(methodTree), state);
            if (privMethodElem != null) {
                result.add(privMethodElem);
            }
        }
        return result;

    }

    /**
     * A safe init method is an instance method that is either private or final (so no
     * overriding is possible)
     *
     * @param stmt                  the statement
     * @param enclosingMethodSymbol symbol for enclosing constructor / initializer
     * @param state                 visitor state
     * @return element of safe init function if stmt invokes that function; null otherwise
     */
    @Nullable
    private Element getInvokeOfSafeInitMethod(
            StatementTree stmt, final Symbol.MethodSymbol enclosingMethodSymbol, VisitorState state) {
        Matcher<ExpressionTree> invokeMatcher = new Matcher<ExpressionTree>() {
            @Override
            public boolean matches(ExpressionTree expressionTree, VisitorState state) {
                if (!(expressionTree instanceof MethodInvocationTree)) {
                    return false;
                }
                MethodInvocationTree methodInvocationTree = (MethodInvocationTree) expressionTree;
                Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(methodInvocationTree);
                Set<Modifier> modifiers = symbol.getModifiers();
                if ((symbol.isPrivate() || modifiers.contains(Modifier.FINAL)) && !symbol.isStatic()) {
                    // check it's the same class (could be an issue with inner classes)
                    if (ASTHelpers.enclosingClass(symbol).equals(ASTHelpers.enclosingClass(enclosingMethodSymbol))) {
                        // make sure the receiver is 'this'
                        ExpressionTree receiver = ASTHelpers.getReceiver(expressionTree);
                        return receiver == null || isThisIdentifier(receiver);
                    }
                }
                return false;
            }
        };
        if (stmt.getKind().equals(EXPRESSION_STATEMENT)) {
            ExpressionTree expression = ((ExpressionStatementTree) stmt).getExpression();
            if (invokeMatcher.matches(expression, state)) {
                return ASTHelpers.getSymbol(expression);
            }
        }
        return null;

    }

    private boolean isThisCall(StatementTree statementTree, VisitorState state) {
        if (statementTree.getKind().equals(EXPRESSION_STATEMENT)) {
            ExpressionTree expression = ((ExpressionStatementTree) statementTree).getExpression();
            return Matchers.methodInvocation(THIS_MATCHER).matches(expression, state);
        }
        return false;
    }

    private FieldInitEntities collectEntities(ClassTree tree, VisitorState state) {
        Set<Symbol> nonnullInstanceFields = new LinkedHashSet<>();
        Set<Symbol> nonnullStaticFields = new LinkedHashSet<>();
        Set<BlockTree> instanceInitializerBlocks = new LinkedHashSet<>();
        Set<BlockTree> staticInitializerBlocks = new LinkedHashSet<>();
        Set<MethodTree> constructors = new LinkedHashSet<>();
        Set<MethodTree> initializerMethods = new LinkedHashSet<>();

        for (Tree memberTree : tree.getMembers()) {
            switch (memberTree.getKind()) {
                case METHOD:
                    // check if it is a constructor or an @Initializer method
                    MethodTree methodTree = (MethodTree) memberTree;
                    Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(methodTree);
                    if (symbol.isConstructor()) {
                        if (!ASTHelpers.isGeneratedConstructor(methodTree)) {
                            constructors.add(methodTree);
                        }
                    } else if (isInitializerMethod(state, symbol)) {
                        initializerMethods.add(methodTree);
                    }
                    break;
                case VARIABLE:
                    // field declaration
                    VariableTree varTree = (VariableTree) memberTree;
                    Symbol fieldSymbol = ASTHelpers.getSymbol(varTree);
                    if (fieldSymbol.type.isPrimitive()
                            || skipDueToFieldAnnotation(fieldSymbol)) {
                        continue;
                    }
                    if (varTree.getInitializer() != null) {
                        // note that we check that the initializer does the right thing in
                        // matchVariable()
                        continue;
                    }
                    if (fieldSymbol.isStatic()) {
                        nonnullStaticFields.add(fieldSymbol);
                    } else {
                        nonnullInstanceFields.add(fieldSymbol);
                    }
                    break;
                case BLOCK:
                    // initializer block
                    BlockTree blockTree = (BlockTree) memberTree;
                    if (blockTree.isStatic()) {
                        staticInitializerBlocks.add(blockTree);
                    } else {
                        instanceInitializerBlocks.add(blockTree);
                    }
                    break;
                case ENUM:
                case CLASS:
                case INTERFACE:
                case ANNOTATION_TYPE:
                    // do nothing
                    break;
                default:
                    throw new RuntimeException(memberTree.getKind().toString() + " " + memberTree);
            }
        }

        return FieldInitEntities.create(
                ImmutableSet.copyOf(nonnullInstanceFields),
                ImmutableSet.copyOf(nonnullStaticFields),
                ImmutableSet.copyOf(instanceInitializerBlocks),
                ImmutableSet.copyOf(staticInitializerBlocks),
                ImmutableSet.copyOf(constructors),
                ImmutableSet.copyOf(initializerMethods)
        );
    }

    private boolean isInitializerMethod(VisitorState state, Symbol.MethodSymbol symbol) {
        if (ASTHelpers.hasDirectAnnotationWithSimpleName(symbol, "Initializer")
                || config.isKnownInitializerMethod(symbol)) {
            return true;
        }
        Symbol.MethodSymbol closestOverriddenMethod = getClosestOverriddenMethod(symbol, state.getTypes());
        if (closestOverriddenMethod == null) {
            return false;
        }
        return isInitializerMethod(state, closestOverriddenMethod);
    }

    private boolean skipDueToFieldAnnotation(Symbol fieldSymbol) {
        for (AnnotationMirror anno : fieldSymbol.getAnnotationMirrors()) {
            // Check for Nullable like ReturnValueIsNonNull
            String annoTypeStr = anno.getAnnotationType().toString();
            if (config.isExcludedFieldAnnotation(annoTypeStr)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcludedClass(Symbol.ClassSymbol classSymbol, VisitorState state) {
        if (config.isExcludedClass(classSymbol.getQualifiedName().toString())) {
            return true;
        }
        // check annotations
        for (AnnotationMirror anno : classSymbol.getAnnotationMirrors()) {
            if (config.isExcludedClassAnnotation(anno.getAnnotationType().toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean fromUnannotatedPackage(Symbol symbol) {
        Symbol.ClassSymbol outermostClassSymbol = NullabilityUtil.getOutermostClassSymbol(symbol);
        return !config.fromAnnotatedPackage(outermostClassSymbol.toString());
    }

    private boolean mayBeNullExpr(VisitorState state, ExpressionTree expr) {
        expr = stripParensAndCasts(expr);
        if (ASTHelpers.constValue(expr) != null) {
            // This should include literals such as "true" or a string
            // obviously not null
            return false;
        }
        // the logic here is to avoid doing dataflow analysis whenever possible
        Symbol exprSymbol = ASTHelpers.getSymbol(expr);
        boolean exprMayBeNull = false;
        switch (expr.getKind()) {
            case NULL_LITERAL:
                // obviously null
                exprMayBeNull = true;
                break;
            case ARRAY_ACCESS:
                // unsound!  we cannot check for nullness of array contents yet
                exprMayBeNull = false;
                break;
            case NEW_CLASS:
            case NEW_ARRAY:
                // for string concatenation, auto-boxing
            case LAMBDA_EXPRESSION:
                // Lambdas may return null, but the lambda literal itself should not be null
            case MEMBER_REFERENCE:
                // These cannot be null; the compiler would catch it
            case PLUS:
                // rest are for auto-boxing
            case MINUS:
            case MULTIPLY:
            case DIVIDE:
            case REMAINDER:
            case CONDITIONAL_AND:
            case CONDITIONAL_OR:
            case LOGICAL_COMPLEMENT:
            case INSTANCE_OF:
            case POSTFIX_DECREMENT:
            case POSTFIX_INCREMENT:
            case EQUAL_TO:
            case NOT_EQUAL_TO:
            case GREATER_THAN:
            case GREATER_THAN_EQUAL:
            case LESS_THAN:
            case LESS_THAN_EQUAL:
                // clearly not null
                exprMayBeNull = false;
                break;
            case MEMBER_SELECT:
                exprMayBeNull = mayBeNullFieldAccess(state, expr, exprSymbol);
                break;
            case IDENTIFIER:
                if (exprSymbol != null && exprSymbol.getKind().equals(ElementKind.FIELD)) {
                    exprMayBeNull = mayBeNullFieldAccess(state, expr, exprSymbol);
                    break;
                } else {
                    exprMayBeNull = nullnessFromDataflow(state, expr);
                    break;
                }
            case METHOD_INVOCATION:
                exprMayBeNull = mayBeNullMethodCall(state, expr, (Symbol.MethodSymbol) exprSymbol);
                break;
            case CONDITIONAL_EXPRESSION:
            case ASSIGNMENT:
                exprMayBeNull = nullnessFromDataflow(state, expr);
                break;
            default:
                throw new RuntimeException("whoops, better handle " + expr.getKind() + " " + expr);
        }
        exprMayBeNull = handler.onOverrideMayBeNullExpr(this, expr, state, exprMayBeNull);
        return exprMayBeNull;
    }

    private boolean mayBeNullMethodCall(VisitorState state, ExpressionTree expr, Symbol.MethodSymbol exprSymbol) {
        if (fromUnannotatedPackage(exprSymbol)) {
            return false;
        }
        if (!TrustingNullnessAnalysis.hasNullableAnnotation(exprSymbol)) {
            return false;
        }
        return nullnessFromDataflow(state, expr);
    }

    public boolean nullnessFromDataflow(VisitorState state, ExpressionTree expr) {
        Nullness nullness =
                getNullnessAnalysis(state)
                        .getNullness(new TreePath(state.getPath(), expr), state.context);
        if (nullness == null) {
            // this may be unsound, like for field initializers
            // figure out if we care
            return false;
        }
        return nullnessToBool(nullness);
    }

    public AccessPathNullnessAnalysis getNullnessAnalysis(VisitorState state) {
        return AccessPathNullnessAnalysis.instance(state.context,
                nonAnnotatedMethod, state.getTypes(), config, this.handler);
    }

    private boolean mayBeNullFieldAccess(VisitorState state, ExpressionTree expr, Symbol exprSymbol) {
        if (exprSymbol != null && !TrustingNullnessAnalysis.hasNullableAnnotation(exprSymbol)) {
            return false;
        }
        return nullnessFromDataflow(state, expr);
    }

    /**
     * @param kind
     * @return <code>true</code> if a deference of the kind might dereference null,
     * <code>false</code> otherwise
     */
    private boolean kindMayDeferenceNull(ElementKind kind) {
        switch (kind) {
            case CLASS:
            case PACKAGE:
            case ENUM:
            case INTERFACE:
            case ANNOTATION_TYPE:
                return false;
            default:
                return true;
        }
    }

    private Description matchDereference(
            ExpressionTree baseExpression,
            ExpressionTree derefExpression, VisitorState state) {
        if (!matchWithinClass) {
            return Description.NO_MATCH;
        }
        Symbol dereferenced = ASTHelpers.getSymbol(baseExpression);
        if (dereferenced == null
                || dereferenced.type.isPrimitive()
                || !kindMayDeferenceNull(dereferenced.getKind())) {
            // we know we don't have a null dereference here
            return Description.NO_MATCH;
        }
        if (mayBeNullExpr(state, baseExpression)) {
            String message = "dereferenced expression " + baseExpression.toString() + " is @Nullable";
            return createErrorDescription(derefExpression, message, state.getPath());
        }
        return Description.NO_MATCH;
    }

    /**
     * create an error description for a nullability warning
     *
     * @param errorLocTree the location of the error
     * @param message      the error message
     * @param path         the TreePath to the error location.  Used to compute a suggested fix at the enclosing
     *                     method for the error location
     * @return the error description
     */
    private Description createErrorDescription(Tree errorLocTree, String message, TreePath path) {
        MethodTree enclosingMethod = ASTHelpers.findEnclosingNode(path, MethodTree.class);
        return createErrorDescription(errorLocTree, message, enclosingMethod);
    }

    /**
     * create an error description for a nullability warning
     *
     * @param errorLocTree the location of the error
     * @param message      the error message
     * @param suggestTree  the location at which a fix suggestion should be made
     * @return the error description
     */
    private Description createErrorDescription(Tree errorLocTree, String message, Tree suggestTree) {
        Description.Builder builder = buildDescription(errorLocTree)
                .setMessage(message);
        if (config.suggestSuppressions()) {
            builder = addSuppressWarningsFix(suggestTree, builder);
        }
        // #letbuildersbuild
        return builder.build();
    }

    private Description.Builder addSuppressWarningsFix(Tree suggestTree, Description.Builder builder) {
        String checkerName = canonicalName();
        SuppressWarnings extantSuppressWarnings = ASTHelpers.getAnnotation(suggestTree, SuppressWarnings.class);
        SuggestedFix fix;
        if (extantSuppressWarnings == null) {
            fix = SuggestedFix.prefixWith(
                    suggestTree, "@SuppressWarnings(\"" + checkerName + "\") ");
        } else {
            // need to update the existing list of warnings
            List<String> suppressions = Lists.newArrayList(extantSuppressWarnings.value());
            suppressions.add(checkerName);
            // find the existing annotation, so we can replace it
            ModifiersTree modifiers = (suggestTree instanceof MethodTree)
                    ? ((MethodTree) suggestTree).getModifiers()
                    : ((VariableTree) suggestTree).getModifiers();
            List<? extends AnnotationTree> annotations = modifiers.getAnnotations();
            Optional<? extends AnnotationTree> suppressWarningsAnnot = Iterables.tryFind(annotations,
                    new Predicate<AnnotationTree>() {
                        @Override
                        public boolean apply(AnnotationTree input) {
                            return input.getAnnotationType().toString().endsWith("SuppressWarnings");
                        }

                    });
            if (!suppressWarningsAnnot.isPresent()) {
                throw new AssertionError("something went horribly wrong");
            }
            String replacement = "@SuppressWarnings({"
                    + Joiner.on(',').join(Iterables.transform(suppressions, new Function<String, String>() {
                            @Override
                            public String apply(String s) {
                                return new StringBuilder(s.length() + 2)
                                        .append('"').append(s).append('"').toString();
                            }
                        }))
                    + "}) ";
            fix = SuggestedFix.replace(
                    suppressWarningsAnnot.get(),
                    replacement
            );
        }
        return builder.addFix(fix);
    }

    @SuppressWarnings("unused")
    private int depth(ExpressionTree expression) {
        switch (expression.getKind()) {
            case MEMBER_SELECT:
                MemberSelectTree selectTree = (MemberSelectTree) expression;
                return 1 + depth(selectTree.getExpression());
            case METHOD_INVOCATION:
                MethodInvocationTree invTree = (MethodInvocationTree) expression;
                return depth(invTree.getMethodSelect());
            case IDENTIFIER:
                IdentifierTree varTree = (IdentifierTree) expression;
                Symbol symbol = ASTHelpers.getSymbol(varTree);
                return (symbol.getKind().equals(ElementKind.FIELD)) ? 2 : 1;
            default:
                return 0;
        }
    }

    private static boolean isThisIdentifier(ExpressionTree expressionTree) {
        return expressionTree.getKind().equals(IDENTIFIER)
                && ((IdentifierTree) expressionTree).getName().toString().equals("this");
    }

    /**
     * strip out enclosing parentheses and type casts.
     *
     * @param expr
     * @return
     */
    private static ExpressionTree stripParensAndCasts(ExpressionTree expr) {
        boolean someChange = true;
        while (someChange) {
            someChange = false;
            if (expr.getKind().equals(PARENTHESIZED)) {
                expr = ((ParenthesizedTree) expr).getExpression();
                someChange = true;
            }
            if (expr.getKind().equals(TYPE_CAST)) {
                expr = ((TypeCastTree) expr).getExpression();
                someChange = true;
            }
        }
        return expr;
    }

    private static boolean nullnessToBool(Nullness nullness) {
        switch (nullness) {
            case BOTTOM:
            case NONNULL:
                return false;
            case NULL:
            case NULLABLE:
                return true;
            default:
                throw new AssertionError("Impossible: " + nullness);
        }
    }

    /**
     * find the closest ancestor method in a superclass or superinterface that method overrides
     *
     * @param method the subclass method
     * @param types  the types data structure from javac
     * @return closest overridden ancestor method, or <code>null</code> if method does not override anything
     */
    @Nullable
    private Symbol.MethodSymbol getClosestOverriddenMethod(Symbol.MethodSymbol method, Types types) {
        // taken from Error Prone MethodOverrides check
        Symbol.ClassSymbol owner = method.enclClass();
        for (Type s : types.closure(owner.type)) {
            if (s.equals(owner.type)) {
                continue;
            }
            for (Symbol m : s.tsym.members().getSymbolsByName(method.name)) {
                if (!(m instanceof Symbol.MethodSymbol)) {
                    continue;
                }
                Symbol.MethodSymbol msym = (Symbol.MethodSymbol) m;
                if (msym.isStatic()) {
                    continue;
                }
                if (method.overrides(msym, owner, types, /*checkReturn*/ false)) {
                    return msym;
                }
            }
        }
        return null;
    }

    private Description reportInitializerError(
            Symbol.MethodSymbol methodSymbol,
            MethodTree methodTree) {
        String message = initializerErrors.get(methodSymbol);
        // be careful to delete the entry in the map to avoid a leak
        initializerErrors.remove(methodSymbol);
        return createErrorDescription(methodTree, message, methodTree);
    }

    private String errMsgForInitializer(Set<Element> uninitFields) {
        String message = "initializer method does not guarantee @NonNull ";
        if (uninitFields.size() == 1) {
            message += "field " + uninitFields.iterator().next().toString() + " is initialized";
        } else {
            message += "fields " + Joiner.on(", ").join(uninitFields) + " are initialized";
        }
        return message;
    }

    @AutoValue
    abstract static class FieldInitEntities {

        static FieldInitEntities create(
                Set<Symbol> nonnullInstanceFields,
                Set<Symbol> nonnullStaticFields,
                Set<BlockTree> instanceInitializerBlocks,
                Set<BlockTree> staticInitializerBlocks,
                Set<MethodTree> constructors,
                Set<MethodTree> initializerMethods) {
            return new AutoValue_NullAway_FieldInitEntities(
                    nonnullInstanceFields, nonnullStaticFields, instanceInitializerBlocks,
                    staticInitializerBlocks, constructors, initializerMethods);
        }

        /**
         * @return @NonNull instance fields that are not directly initialized at declaration
         */
        abstract Set<Symbol> nonnullInstanceFields();

        /**
         * @return @NonNull static fields that are not directly initialized at declaration
         */
        abstract Set<Symbol> nonnullStaticFields();

        abstract Set<BlockTree> instanceInitializerBlocks();

        abstract Set<BlockTree> staticInitializerBlocks();

        abstract Set<MethodTree> constructors();

        abstract Set<MethodTree> initializerMethods();
    }
}
