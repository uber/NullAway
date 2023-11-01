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

import static com.google.errorprone.BugPattern.SeverityLevel.WARNING;
import static com.sun.source.tree.Tree.Kind.EXPRESSION_STATEMENT;
import static com.sun.source.tree.Tree.Kind.IDENTIFIER;
import static com.sun.source.tree.Tree.Kind.OTHER;
import static com.sun.source.tree.Tree.Kind.PARENTHESIZED;
import static com.sun.source.tree.Tree.Kind.TYPE_CAST;
import static com.uber.nullaway.ASTHelpersBackports.isStatic;
import static com.uber.nullaway.ErrorBuilder.errMsgForInitializer;
import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.auto.service.AutoService;
import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.errorprone.BugPattern;
import com.google.errorprone.ErrorProneFlags;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.sun.tools.javac.tree.JCTree;
import com.uber.nullaway.ErrorMessage.MessageTypes;
import com.uber.nullaway.dataflow.AccessPathNullnessAnalysis;
import com.uber.nullaway.dataflow.EnclosingEnvironmentNullness;
import com.uber.nullaway.handlers.Handler;
import com.uber.nullaway.handlers.Handlers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.type.TypeKind;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.javacutil.ElementUtils;
import org.checkerframework.nullaway.javacutil.TreeUtils;

/**
 * Checker for nullability errors. It assumes that any field, method parameter, or return type that
 * may be null is annotated with {@link Nullable}, and then checks the following rules:
 *
 * <ul>
 *   <li>no assignment of a nullable expression into a non-null field
 *   <li>no passing a nullable expression into a non-null parameter
 *   <li>no returning a nullable expression from a method with non-null return type
 *   <li>no field access or method invocation on an expression that is nullable
 * </ul>
 *
 * <p>This checker also detects errors related to field initialization. For any @NonNull instance
 * field <code>f</code>, this checker ensures that at least one of the following cases holds:
 *
 * <ol>
 *   <li><code>f</code> is directly initialized at its declaration
 *   <li><code>f</code> is always initialized in all constructors
 *   <li><code>f</code> is always initialized in some method annotated with @Initializer
 * </ol>
 *
 * <p>For any @NonNull static field <code>f</code>, this checker ensures that at least one of the
 * following cases holds:
 *
 * <ol>
 *   <li><code>f</code> is directly initialized at its declaration
 *   <li><code>f</code> is always initialized in some static initializer block
 * </ol>
 */
@AutoService(BugChecker.class)
@BugPattern(
    name = "NullAway",
    altNames = {"CheckNullabilityTypes"},
    summary = "Nullability type error.",
    tags = BugPattern.StandardTags.LIKELY_ERROR,
    severity = WARNING)
@SuppressWarnings("BugPatternNaming") // remove once we require EP 2.11+
public class NullAway extends BugChecker
    implements BugChecker.MethodInvocationTreeMatcher,
        BugChecker.AssignmentTreeMatcher,
        BugChecker.MemberSelectTreeMatcher,
        BugChecker.ArrayAccessTreeMatcher,
        BugChecker.ReturnTreeMatcher,
        BugChecker.ClassTreeMatcher,
        BugChecker.MethodTreeMatcher,
        BugChecker.VariableTreeMatcher,
        BugChecker.NewClassTreeMatcher,
        BugChecker.BinaryTreeMatcher,
        BugChecker.UnaryTreeMatcher,
        BugChecker.ConditionalExpressionTreeMatcher,
        BugChecker.IfTreeMatcher,
        BugChecker.WhileLoopTreeMatcher,
        BugChecker.ForLoopTreeMatcher,
        BugChecker.EnhancedForLoopTreeMatcher,
        BugChecker.LambdaExpressionTreeMatcher,
        BugChecker.IdentifierTreeMatcher,
        BugChecker.MemberReferenceTreeMatcher,
        BugChecker.CompoundAssignmentTreeMatcher,
        BugChecker.SwitchTreeMatcher,
        BugChecker.TypeCastTreeMatcher,
        BugChecker.ParameterizedTypeTreeMatcher {

  static final String INITIALIZATION_CHECK_NAME = "NullAway.Init";
  static final String OPTIONAL_CHECK_NAME = "NullAway.Optional";
  // Unmatched, used for when we only want full checker suppressions to work
  static final String CORE_CHECK_NAME = "NullAway.<core>";

  private static final Matcher<ExpressionTree> THIS_MATCHER = NullAway::isThisIdentifierMatcher;

  private final Predicate<MethodInvocationNode> nonAnnotatedMethod;

  /**
   * Possible levels of null-marking / annotatedness for a class. This may be set to FULLY_MARKED or
   * FULLY_UNMARKED optimistically but then adjusted to PARTIALLY_MARKED later based on annotations
   * within the class; see {@link #matchClass(ClassTree, VisitorState)}
   */
  private enum NullMarking {
    /** full class is annotated for nullness checking */
    FULLY_MARKED,
    /** full class is unannotated */
    FULLY_UNMARKED,
    /**
     * class has a mix of annotatedness, depending on presence of {@link
     * org.jspecify.annotations.NullMarked} annotations
     */
    PARTIALLY_MARKED
  }

  /**
   * Null-marking level for the current top-level class. The initial value of this field doesn't
   * matter, as it will be set appropriately in {@link #matchClass(ClassTree, VisitorState)}
   */
  private NullMarking nullMarkingForTopLevelClass = NullMarking.FULLY_MARKED;

  /**
   * We store the CodeAnnotationInfo object in a field for convenience; it is initialized in {@link
   * #matchClass(ClassTree, VisitorState)}
   */
  // suppress initialization warning rather than casting everywhere; we know matchClass() will
  // always be called before the field gets dereferenced
  @SuppressWarnings("NullAway.Init")
  private CodeAnnotationInfo codeAnnotationInfo;

  private final Config config;

  /** Returns the configuration being used for this analysis. */
  public Config getConfig() {
    return config;
  }

  private final ErrorBuilder errorBuilder;

  /**
   * The handler passed to our analysis (usually a {@code CompositeHandler} including handlers for
   * various APIs.
   */
  private final Handler handler;

  /**
   * entities relevant to field initialization per class. cached for performance. nulled out in
   * {@link #matchClass(ClassTree, VisitorState)}
   */
  private final Map<Symbol.ClassSymbol, FieldInitEntities> class2Entities = new LinkedHashMap<>();

  /**
   * fields not initialized by constructors, per class. cached for performance. nulled out in {@link
   * #matchClass(ClassTree, VisitorState)}
   */
  private final SetMultimap<Symbol.ClassSymbol, Symbol> class2ConstructorUninit =
      LinkedHashMultimap.create();

  /**
   * maps each top-level initialization member (constructor, init block, field decl with initializer
   * expression) to the set of @NonNull fields known to be initialized before that member executes.
   *
   * <p>cached for performance. nulled out in {@link #matchClass(ClassTree, VisitorState)}
   */
  private final Map<Symbol.ClassSymbol, Multimap<Tree, Element>> initTree2PrevFieldInit =
      new LinkedHashMap<>();

  /**
   * dynamically computer/overriden nullness facts for certain expressions, such as specific method
   * calls where we can infer a more precise set of facts than those given by the method's
   * annotations.
   */
  private final Map<ExpressionTree, Nullness> computedNullnessMap = new LinkedHashMap<>();

  /**
   * Used to check if a symbol represents a module in {@link #matchMemberSelect(MemberSelectTree,
   * VisitorState)}. We need to use reflection to preserve compatibility with Java 8.
   *
   * <p>TODO remove this once NullAway requires JDK 11
   */
  @Nullable private final Class<?> moduleElementClass;

  /**
   * Error Prone requires us to have an empty constructor for each Plugin, in addition to the
   * constructor taking an ErrorProneFlags object. This constructor should not be used anywhere
   * else. Checker objects constructed with this constructor will fail with IllegalStateException if
   * ever used for analysis.
   */
  public NullAway() {
    config = new DummyOptionsConfig();
    handler = Handlers.buildEmpty();
    nonAnnotatedMethod = this::isMethodUnannotated;
    errorBuilder = new ErrorBuilder(config, "", ImmutableSet.of());
    moduleElementClass = null;
  }

  @Inject // For future Error Prone versions in which checkers are loaded using Guice
  public NullAway(ErrorProneFlags flags) {
    config = new ErrorProneCLIFlagsConfig(flags);
    handler = Handlers.buildDefault(config);
    nonAnnotatedMethod = this::isMethodUnannotated;
    errorBuilder = new ErrorBuilder(config, canonicalName(), allNames());
    Class<?> moduleElementClass = null;
    try {
      moduleElementClass =
          getClass().getClassLoader().loadClass("javax.lang.model.element.ModuleElement");
    } catch (ClassNotFoundException e) {
      // can occur pre JDK 11
    }
    this.moduleElementClass = moduleElementClass;
  }

  private boolean isMethodUnannotated(MethodInvocationNode invocationNode) {
    return invocationNode == null
        || codeAnnotationInfo.isSymbolUnannotated(
            ASTHelpers.getSymbol(invocationNode.getTree()), config);
  }

  private boolean withinAnnotatedCode(VisitorState state) {
    switch (nullMarkingForTopLevelClass) {
      case FULLY_MARKED:
        return true;
      case FULLY_UNMARKED:
        return false;
      case PARTIALLY_MARKED:
        return checkMarkingForPath(state);
    }
    // unreachable but needed to make code compile
    throw new IllegalStateException("unexpected marking state " + nullMarkingForTopLevelClass);
  }

  private boolean checkMarkingForPath(VisitorState state) {
    TreePath path = state.getPath();
    Tree currentTree = path.getLeaf();
    // Find the closest class or method symbol, since those are the only ones we have code
    // annotation info for.
    // For the purposes of determining whether we are inside annotated code or not, when matching
    // a class its enclosing class is itself (otherwise we might not process initialization for
    // top-level classes in general, or @NullMarked inner classes), same for the enclosing method of
    // a method.
    // We use instanceof, since there are multiple Kind's which represent ClassTree's: ENUM,
    // INTERFACE, etc, and we are actually interested in all of them.
    while (!(currentTree instanceof ClassTree || currentTree instanceof MethodTree)) {
      path = path.getParentPath();
      if (path == null) {
        // Not within a class or method (e.g. the package identifier or an import statement)
        return false;
      }
      currentTree = path.getLeaf();
    }
    Symbol enclosingMarkableSymbol = ASTHelpers.getSymbol(currentTree);
    if (enclosingMarkableSymbol == null) {
      return false;
    }
    return !codeAnnotationInfo.isSymbolUnannotated(enclosingMarkableSymbol, config);
  }

  @Override
  public String linkUrl() {
    // add a space to make it clickable from iTerm
    return config.getErrorURL() + " ";
  }

  /**
   * We are trying to see if (1) we are in a method guaranteed to return something non-null, and (2)
   * this return statement can return something null.
   */
  @Override
  public Description matchReturn(ReturnTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    handler.onMatchReturn(this, tree, state);
    ExpressionTree retExpr = tree.getExpression();
    // let's do quick checks on returned expression first
    if (retExpr == null) {
      return Description.NO_MATCH;
    }
    // now let's check the enclosing method
    TreePath enclosingMethodOrLambda =
        NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(state.getPath());
    if (enclosingMethodOrLambda == null) {
      throw new RuntimeException("no enclosing method, lambda or initializer!");
    }
    if (!(enclosingMethodOrLambda.getLeaf() instanceof MethodTree
        || enclosingMethodOrLambda.getLeaf() instanceof LambdaExpressionTree)) {
      throw new RuntimeException(
          "return statement outside of a method or lambda! (e.g. in an initializer block)");
    }
    Tree leaf = enclosingMethodOrLambda.getLeaf();
    Symbol.MethodSymbol methodSymbol;
    LambdaExpressionTree lambdaTree = null;
    if (leaf instanceof MethodTree) {
      MethodTree enclosingMethod = (MethodTree) leaf;
      methodSymbol = ASTHelpers.getSymbol(enclosingMethod);
    } else {
      // we have a lambda
      lambdaTree = (LambdaExpressionTree) leaf;
      methodSymbol = NullabilityUtil.getFunctionalInterfaceMethod(lambdaTree, state.getTypes());
    }
    return checkReturnExpression(retExpr, methodSymbol, lambdaTree, tree, state);
  }

  @Override
  public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    final Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(tree);
    if (methodSymbol == null) {
      throw new RuntimeException("not expecting unresolved method here");
    }
    handler.onMatchMethodInvocation(this, tree, state, methodSymbol);
    // assuming this list does not include the receiver
    List<? extends ExpressionTree> actualParams = tree.getArguments();
    return handleInvocation(tree, state, methodSymbol, actualParams);
  }

  @Override
  public Description matchNewClass(NewClassTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(tree);
    if (methodSymbol == null) {
      throw new RuntimeException("not expecting unresolved method here");
    }
    List<? extends ExpressionTree> actualParams = tree.getArguments();
    if (tree.getClassBody() != null && actualParams.size() > 0) {
      // passing parameters to constructor of anonymous class
      // this constructor just invokes the constructor of the superclass, and
      // in the AST does not have the parameter nullability annotations from the superclass.
      // so, treat as if the superclass constructor is being invoked directly
      // see https://github.com/uber/NullAway/issues/102
      methodSymbol = getSymbolOfSuperConstructor(methodSymbol, state);
    }
    return handleInvocation(tree, state, methodSymbol, actualParams);
  }

  /**
   * Updates the {@link EnclosingEnvironmentNullness} with an entry for lambda or anonymous class,
   * capturing nullability info for locals just before the declaration of the entity
   *
   * @param treePath either a lambda or a local / anonymous class, identified by its tree path
   * @param state visitor state
   */
  private void updateEnvironmentMapping(TreePath treePath, VisitorState state) {
    AccessPathNullnessAnalysis analysis = getNullnessAnalysis(state);
    // two notes:
    // 1. we are free to take local variable information from the program point before
    // the lambda / class declaration as only effectively final variables can be accessed
    // from the nested scope, so the program point doesn't matter
    // 2. we keep info on all locals rather than just effectively final ones for simplicity
    EnclosingEnvironmentNullness.instance(state.context)
        .addEnvironmentMapping(
            treePath.getLeaf(), analysis.getNullnessInfoBeforeNewContext(treePath, state, handler));
  }

  private Symbol.MethodSymbol getSymbolOfSuperConstructor(
      Symbol.MethodSymbol anonClassConstructorSymbol, VisitorState state) {
    // get the statements in the body of the anonymous class constructor
    List<? extends StatementTree> statements =
        getTreesInstance(state).getTree(anonClassConstructorSymbol).getBody().getStatements();
    // there should be exactly one statement, which is an invocation of the super constructor
    if (statements.size() == 1) {
      StatementTree stmt = statements.get(0);
      if (stmt instanceof ExpressionStatementTree) {
        ExpressionTree expression = ((ExpressionStatementTree) stmt).getExpression();
        if (expression instanceof MethodInvocationTree) {
          return ASTHelpers.getSymbol((MethodInvocationTree) expression);
        }
      }
    }
    throw new IllegalStateException("unexpected anonymous class constructor body " + statements);
  }

  @Override
  public Description matchAssignment(AssignmentTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    Type lhsType = ASTHelpers.getType(tree.getVariable());
    if (lhsType != null && lhsType.isPrimitive()) {
      doUnboxingCheck(state, tree.getExpression());
    }
    // generics check
    if (lhsType != null && lhsType.getTypeArguments().length() > 0) {
      GenericsChecks.checkTypeParameterNullnessForAssignability(tree, this, state);
    }

    Symbol assigned = ASTHelpers.getSymbol(tree.getVariable());
    if (assigned == null || assigned.getKind() != ElementKind.FIELD) {
      // not a field of nullable type
      return Description.NO_MATCH;
    }

    if (Nullness.hasNullableAnnotation(assigned, config)) {
      // field already annotated
      return Description.NO_MATCH;
    }
    ExpressionTree expression = tree.getExpression();
    if (mayBeNullExpr(state, expression)) {
      String message = "assigning @Nullable expression to @NonNull field";
      return errorBuilder.createErrorDescriptionForNullAssignment(
          new ErrorMessage(MessageTypes.ASSIGN_FIELD_NULLABLE, message),
          expression,
          buildDescription(tree),
          state,
          ASTHelpers.getSymbol(tree.getVariable()));
    }
    handler.onNonNullFieldAssignment(assigned, getNullnessAnalysis(state), state);
    return Description.NO_MATCH;
  }

  @Override
  public Description matchCompoundAssignment(CompoundAssignmentTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    Type lhsType = ASTHelpers.getType(tree.getVariable());
    Type stringType = Suppliers.STRING_TYPE.get(state);
    if (lhsType != null && !state.getTypes().isSameType(lhsType, stringType)) {
      // both LHS and RHS could get unboxed
      doUnboxingCheck(state, tree.getVariable(), tree.getExpression());
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchArrayAccess(ArrayAccessTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    Description description = matchDereference(tree.getExpression(), tree, state);
    // also check for unboxing of array index expression
    doUnboxingCheck(state, tree.getIndex());
    return description;
  }

  @Override
  public Description matchMemberSelect(MemberSelectTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    Symbol symbol = ASTHelpers.getSymbol(tree);
    // Some checks for cases where we know this cannot be a null dereference.  The tree's symbol may
    // be null in cases where the tree represents part of a package name, e.g., in the package
    // declaration in a class, or in a requires clause in a module-info.java file; it should never
    // be null for a real field dereference or method call
    if (symbol == null
        || symbol.getSimpleName().toString().equals("class")
        || symbol.isEnum()
        || isModuleSymbol(symbol)) {
      return Description.NO_MATCH;
    }

    Description badDeref = matchDereference(tree.getExpression(), tree, state);
    if (!badDeref.equals(Description.NO_MATCH)) {
      return badDeref;
    }
    // if we're accessing a field of this, make sure we're not reading the field before init
    if (tree.getExpression() instanceof IdentifierTree
        && ((IdentifierTree) tree.getExpression()).getName().toString().equals("this")) {
      return checkForReadBeforeInit(tree, state);
    }
    return Description.NO_MATCH;
  }

  /**
   * Checks if {@code symbol} represents a JDK 9+ module using reflection.
   *
   * <p>TODO just check using instanceof once NullAway requires JDK 11
   */
  private boolean isModuleSymbol(Symbol symbol) {
    return moduleElementClass != null && moduleElementClass.isAssignableFrom(symbol.getClass());
  }

  /**
   * Look for @NullMarked or @NullUnmarked annotations at the method level and adjust our scan for
   * annotated code accordingly (fast scan for a fully annotated/unannotated top-level class or
   * slower scan for mixed nullmarkedness code).
   */
  private void checkForMethodNullMarkedness(MethodTree tree, VisitorState state) {
    boolean markedMethodInUnmarkedContext = false;
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(tree);
    switch (nullMarkingForTopLevelClass) {
      case FULLY_MARKED:
        if (ASTHelpers.hasDirectAnnotationWithSimpleName(
            methodSymbol, NullabilityUtil.NULLUNMARKED_SIMPLE_NAME)) {
          nullMarkingForTopLevelClass = NullMarking.PARTIALLY_MARKED;
        }
        break;
      case FULLY_UNMARKED:
        if (ASTHelpers.hasDirectAnnotationWithSimpleName(
            methodSymbol, NullabilityUtil.NULLMARKED_SIMPLE_NAME)) {
          nullMarkingForTopLevelClass = NullMarking.PARTIALLY_MARKED;
          markedMethodInUnmarkedContext = true;
        }
        break;
      case PARTIALLY_MARKED:
        if (ASTHelpers.hasDirectAnnotationWithSimpleName(
            methodSymbol, NullabilityUtil.NULLMARKED_SIMPLE_NAME)) {
          // We still care here if this is a transition between @NullUnmarked and @NullMarked code,
          // within partially marked code, see checks below for markedMethodInUnmarkedContext.
          if (!codeAnnotationInfo.isClassNullAnnotated(methodSymbol.enclClass(), config)) {
            markedMethodInUnmarkedContext = true;
          }
        }
        break;
    }
    if (markedMethodInUnmarkedContext) {
      // If this is a @NullMarked method of a @NullUnmarked local or anonymous class, we need to set
      // its environment mapping, since we skipped it during matchClass.
      TreePath pathToEnclosingClass =
          ASTHelpers.findPathFromEnclosingNodeToTopLevel(state.getPath(), ClassTree.class);
      ClassTree enclosingClass = (ClassTree) pathToEnclosingClass.getLeaf();
      if (enclosingClass == null) {
        return;
      }
      NestingKind nestingKind = ASTHelpers.getSymbol(enclosingClass).getNestingKind();
      if (nestingKind.equals(NestingKind.LOCAL) || nestingKind.equals(NestingKind.ANONYMOUS)) {
        updateEnvironmentMapping(pathToEnclosingClass, state);
      }
    }
  }

  @Override
  public Description matchMethod(MethodTree tree, VisitorState state) {
    checkForMethodNullMarkedness(tree, state);
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    // if the method is overriding some other method,
    // check that nullability annotations are consistent with
    // overridden method (if overridden method is in an annotated
    // package)
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(tree);
    handler.onMatchMethod(this, tree, state, methodSymbol);
    boolean isOverriding = ASTHelpers.hasAnnotation(methodSymbol, "java.lang.Override", state);
    boolean exhaustiveOverride = config.exhaustiveOverride();
    if (isOverriding || !exhaustiveOverride) {
      Symbol.MethodSymbol closestOverriddenMethod =
          NullabilityUtil.getClosestOverriddenMethod(methodSymbol, state.getTypes());
      if (closestOverriddenMethod != null) {
        if (config.isJSpecifyMode()) {
          // Check that any generic type parameters in the return type and parameter types are
          // identical (invariant) across the overriding and overridden methods
          GenericsChecks.checkTypeParameterNullnessForMethodOverriding(
              tree, methodSymbol, closestOverriddenMethod, this, state);
        }
        return checkOverriding(closestOverriddenMethod, methodSymbol, null, state);
      }
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchSwitch(SwitchTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }

    ExpressionTree switchSelectorExpression = tree.getExpression();
    // For a statement `switch (e) { ... }`, javac returns `(e)` as the selector expression.  We
    // strip the outermost parentheses for a nicer-looking error message.
    if (switchSelectorExpression instanceof ParenthesizedTree) {
      switchSelectorExpression = ((ParenthesizedTree) switchSelectorExpression).getExpression();
    }

    if (mayBeNullExpr(state, switchSelectorExpression)) {
      final String message =
          "switch expression " + state.getSourceForNode(switchSelectorExpression) + " is @Nullable";
      ErrorMessage errorMessage =
          new ErrorMessage(MessageTypes.SWITCH_EXPRESSION_NULLABLE, message);

      return errorBuilder.createErrorDescription(
          errorMessage,
          switchSelectorExpression,
          buildDescription(switchSelectorExpression),
          state,
          null);
    }

    return Description.NO_MATCH;
  }

  @Override
  public Description matchTypeCast(TypeCastTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    Type castExprType = ASTHelpers.getType(tree);
    if (castExprType != null && castExprType.isPrimitive()) {
      // casting to a primitive type performs unboxing
      doUnboxingCheck(state, tree.getExpression());
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchParameterizedType(ParameterizedTypeTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    GenericsChecks.checkInstantiationForParameterizedTypedTree(tree, state, this, config);
    return Description.NO_MATCH;
  }

  /**
   * checks that an overriding method does not override a {@code @Nullable} parameter with a
   * {@code @NonNull} parameter
   *
   * @param overridingParamSymbols parameters of the overriding method
   * @param overriddenMethod method being overridden
   * @param lambdaExpressionTree if the overriding method is a lambda, the {@link
   *     LambdaExpressionTree}; otherwise {@code null}
   * @param memberReferenceTree if the overriding method is a member reference (which "overrides" a
   *     functional interface method), the {@link MemberReferenceTree}; otherwise {@code null}
   * @return discovered error, or {@link Description#NO_MATCH} if no error
   */
  private Description checkParamOverriding(
      List<VarSymbol> overridingParamSymbols,
      Symbol.MethodSymbol overriddenMethod,
      @Nullable LambdaExpressionTree lambdaExpressionTree,
      @Nullable MemberReferenceTree memberReferenceTree,
      VisitorState state) {
    com.sun.tools.javac.util.List<VarSymbol> superParamSymbols = overriddenMethod.getParameters();
    final boolean unboundMemberRef =
        (memberReferenceTree != null)
            && ((JCTree.JCMemberReference) memberReferenceTree).kind.isUnbound();
    final boolean isOverriddenMethodAnnotated =
        !codeAnnotationInfo.isSymbolUnannotated(overriddenMethod, config);

    // Get argument nullability for the overridden method.  If overriddenMethodArgNullnessMap[i] is
    // null, parameter i is treated as unannotated.
    Nullness[] overriddenMethodArgNullnessMap = new Nullness[superParamSymbols.size()];

    // Collect @Nullable params of overridden method iff the overridden method is in annotated code
    // (otherwise, whether we acknowledge @Nullable in unannotated code or not depends on the
    // -XepOpt:NullAway:AcknowledgeRestrictiveAnnotations flag and its handler).
    if (isOverriddenMethodAnnotated) {
      for (int i = 0; i < superParamSymbols.size(); i++) {
        Nullness paramNullness;
        if (Nullness.paramHasNullableAnnotation(overriddenMethod, i, config)) {
          paramNullness = Nullness.NULLABLE;
        } else if (config.isJSpecifyMode()) {
          // Check if the parameter type is a type variable and the corresponding generic type
          // argument is @Nullable
          if (memberReferenceTree != null) {
            // For a method reference, we get generic type arguments from the javac's inferred type
            // for the tree, which seems to properly preserve type-use annotations
            paramNullness =
                GenericsChecks.getGenericMethodParameterNullness(
                    i, overriddenMethod, ASTHelpers.getType(memberReferenceTree), state, config);
          } else {
            // Use the enclosing class of the overriding method to find generic type arguments
            paramNullness =
                GenericsChecks.getGenericMethodParameterNullness(
                    i, overriddenMethod, overridingParamSymbols.get(i).owner.owner, state, config);
          }
        } else {
          paramNullness = Nullness.NONNULL;
        }
        overriddenMethodArgNullnessMap[i] = paramNullness;
      }
    }

    // Check handlers for any further/overriding nullness information
    overriddenMethodArgNullnessMap =
        handler.onOverrideMethodInvocationParametersNullability(
            state.context,
            overriddenMethod,
            isOverriddenMethodAnnotated,
            overriddenMethodArgNullnessMap);

    // If we have an unbound method reference, the first parameter of the overridden method must be
    // @NonNull, as this parameter will be used as a method receiver inside the generated lambda.
    // e.g. String::length is implemented as (@NonNull s -> s.length()) when used as a
    // SomeFunc<String> and thus incompatible with, for example, SomeFunc.apply(@Nullable T).
    if (unboundMemberRef && Objects.equals(overriddenMethodArgNullnessMap[0], Nullness.NULLABLE)) {
      String message =
          "unbound instance method reference cannot be used, as first parameter of "
              + "functional interface method "
              + ASTHelpers.enclosingClass(overriddenMethod)
              + "."
              + overriddenMethod.toString()
              + " is @Nullable";
      return errorBuilder.createErrorDescription(
          new ErrorMessage(MessageTypes.WRONG_OVERRIDE_PARAM, message),
          buildDescription(memberReferenceTree),
          state,
          null);
    }

    // for unbound member references, we need to adjust parameter indices by 1 when matching with
    // overridden method
    final int startParam = unboundMemberRef ? 1 : 0;

    for (int i = 0; i < superParamSymbols.size(); i++) {
      if (!Objects.equals(overriddenMethodArgNullnessMap[i], Nullness.NULLABLE)) {
        // No need to check, unless the argument of the overridden method is effectively @Nullable,
        // in which case it can't be overridding a @NonNull arg.
        continue;
      }
      int methodParamInd = i - startParam;
      VarSymbol paramSymbol = overridingParamSymbols.get(methodParamInd);
      // in the case where we have a parameter of a lambda expression, we do
      // *not* force the parameter to be annotated with @Nullable; instead we "inherit"
      // nullability from the corresponding functional interface method.
      // So, we report an error if the @Nullable annotation is missing *and*
      // we don't have a lambda with implicitly typed parameters
      boolean implicitlyTypedLambdaParam =
          lambdaExpressionTree != null
              && NullabilityUtil.lambdaParamIsImplicitlyTyped(
                  lambdaExpressionTree.getParameters().get(methodParamInd));
      if (!Nullness.hasNullableAnnotation(paramSymbol, config) && !implicitlyTypedLambdaParam) {
        final String message =
            "parameter "
                + paramSymbol.name.toString()
                + (memberReferenceTree != null ? " of referenced method" : "")
                + " is @NonNull, but parameter in "
                + ((lambdaExpressionTree != null || memberReferenceTree != null)
                    ? "functional interface "
                    : "superclass ")
                + "method "
                + ASTHelpers.enclosingClass(overriddenMethod)
                + "."
                + overriddenMethod.toString()
                + " is @Nullable";
        Tree errorTree;
        if (memberReferenceTree != null) {
          errorTree = memberReferenceTree;
        } else {
          errorTree = getTreesInstance(state).getTree(paramSymbol);
        }
        return errorBuilder.createErrorDescription(
            new ErrorMessage(MessageTypes.WRONG_OVERRIDE_PARAM, message),
            buildDescription(errorTree),
            state,
            paramSymbol);
      }
    }
    return Description.NO_MATCH;
  }

  static Trees getTreesInstance(VisitorState state) {
    return Trees.instance(JavacProcessingEnvironment.instance(state.context));
  }

  private Nullness getMethodReturnNullness(
      Symbol.MethodSymbol methodSymbol, VisitorState state, Nullness defaultForUnannotated) {
    final boolean isMethodAnnotated = !codeAnnotationInfo.isSymbolUnannotated(methodSymbol, config);
    Nullness methodReturnNullness =
        defaultForUnannotated; // Permissive default for unannotated code.
    if (isMethodAnnotated) {
      methodReturnNullness =
          Nullness.hasNullableAnnotation(methodSymbol, config)
              ? Nullness.NULLABLE
              : Nullness.NONNULL;
    }
    return handler.onOverrideMethodReturnNullability(
        methodSymbol, state, isMethodAnnotated, methodReturnNullness);
  }

  /**
   * Checks that if a returned expression is {@code @Nullable}, the enclosing method does not have a
   * {@code @NonNull} return type. Also performs an unboxing check on the returned expression.
   * Finally, in JSpecify mode, also checks that the nullability of generic type arguments of the
   * returned expression's type match the method return type.
   *
   * @param retExpr the expression being returned
   * @param methodSymbol symbol for the enclosing method
   * @param lambdaTree if return is inside a lambda, the tree for the lambda, otherwise {@code null}
   * @param errorTree tree on which to report an error if needed
   * @param state the visitor state
   * @return {@link Description} of the returning {@code @Nullable} from {@code @NonNull} method
   *     error if one is to be reported, otherwise {@link Description#NO_MATCH}
   */
  private Description checkReturnExpression(
      ExpressionTree retExpr,
      Symbol.MethodSymbol methodSymbol,
      @Nullable LambdaExpressionTree lambdaTree,
      Tree errorTree,
      VisitorState state) {
    Type returnType = methodSymbol.getReturnType();
    if (returnType.isPrimitive()) {
      // check for unboxing
      doUnboxingCheck(state, retExpr);
      return Description.NO_MATCH;
    }
    if (ASTHelpers.isSameType(returnType, Suppliers.JAVA_LANG_VOID_TYPE.get(state), state)) {
      // Temporarily treat a Void return type as if it were @Nullable Void.  Change this once
      // we are confident that all use cases can be type checked reasonably (may require generics
      // support)
      return Description.NO_MATCH;
    }

    // Check generic type arguments for returned expression here, since we need to check the type
    // arguments regardless of the top-level nullability of the return type
    GenericsChecks.checkTypeParameterNullnessForFunctionReturnType(
        retExpr, methodSymbol, this, state);

    // Now, perform the check for returning @Nullable from @NonNull.  First, we check if the return
    // type is @Nullable, and if so, bail out.
    if (getMethodReturnNullness(methodSymbol, state, Nullness.NULLABLE).equals(Nullness.NULLABLE)) {
      return Description.NO_MATCH;
    } else if (config.isJSpecifyMode()
        && lambdaTree != null
        && GenericsChecks.getGenericMethodReturnTypeNullness(
                methodSymbol, ASTHelpers.getType(lambdaTree), state, config)
            .equals(Nullness.NULLABLE)) {
      // In JSpecify mode, the return type of a lambda may be @Nullable via a type argument
      return Description.NO_MATCH;
    }

    // Return type is @NonNull.  Check if the expression is @Nullable
    if (mayBeNullExpr(state, retExpr)) {
      return errorBuilder.createErrorDescriptionForNullAssignment(
          new ErrorMessage(
              MessageTypes.RETURN_NULLABLE,
              "returning @Nullable expression from method with @NonNull return type"),
          retExpr,
          buildDescription(errorTree),
          state,
          methodSymbol);
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchLambdaExpression(LambdaExpressionTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    Symbol.MethodSymbol funcInterfaceMethod =
        NullabilityUtil.getFunctionalInterfaceMethod(tree, state.getTypes());
    // we need to update environment mapping before running the handler, as some handlers
    // (like Rx nullability) run dataflow analysis
    updateEnvironmentMapping(state.getPath(), state);
    handler.onMatchLambdaExpression(this, tree, state, funcInterfaceMethod);
    if (codeAnnotationInfo.isSymbolUnannotated(funcInterfaceMethod, config)) {
      return Description.NO_MATCH;
    }
    Description description =
        checkParamOverriding(
            tree.getParameters().stream().map(ASTHelpers::getSymbol).collect(Collectors.toList()),
            funcInterfaceMethod,
            tree,
            null,
            state);
    if (description != Description.NO_MATCH) {
      return description;
    }
    // if the body has a return statement, that gets checked in matchReturn().  We need this code
    // for lambdas with expression bodies
    if (tree.getBodyKind() == LambdaExpressionTree.BodyKind.EXPRESSION
        && funcInterfaceMethod.getReturnType().getKind() != TypeKind.VOID) {
      ExpressionTree resExpr = (ExpressionTree) tree.getBody();
      return checkReturnExpression(resExpr, funcInterfaceMethod, tree, tree, state);
    }
    return Description.NO_MATCH;
  }

  /**
   * for method references, we check that the referenced method correctly overrides the
   * corresponding functional interface method
   */
  @Override
  public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    Symbol.MethodSymbol referencedMethod = ASTHelpers.getSymbol(tree);
    Symbol.MethodSymbol funcInterfaceSymbol =
        NullabilityUtil.getFunctionalInterfaceMethod(tree, state.getTypes());
    handler.onMatchMethodReference(this, tree, state, referencedMethod);
    return checkOverriding(funcInterfaceSymbol, referencedMethod, tree, state);
  }

  /**
   * check that nullability annotations of an overriding method are consistent with those in the
   * overridden method (both return and parameters)
   *
   * @param overriddenMethod method being overridden
   * @param overridingMethod overriding method
   * @param memberReferenceTree if override is via a method reference, the relevant {@link
   *     MemberReferenceTree}; otherwise {@code null}. If non-null, overridingTree is the AST of the
   *     referenced method
   * @param state visitor state.
   * @return discovered error, or {@link Description#NO_MATCH} if no error
   */
  private Description checkOverriding(
      Symbol.MethodSymbol overriddenMethod,
      Symbol.MethodSymbol overridingMethod,
      @Nullable MemberReferenceTree memberReferenceTree,
      VisitorState state) {
    // if the super method returns nonnull, overriding method better not return nullable
    // Note that, for the overriding method, the permissive default is non-null,
    // but it's nullable for the overridden one.
    if (overriddenMethodReturnsNonNull(
            overriddenMethod, overridingMethod.owner, memberReferenceTree, state)
        && getMethodReturnNullness(overridingMethod, state, Nullness.NONNULL)
            .equals(Nullness.NULLABLE)
        && (memberReferenceTree == null
            || getComputedNullness(memberReferenceTree).equals(Nullness.NULLABLE))) {
      String message;
      if (memberReferenceTree != null) {
        message =
            "referenced method returns @Nullable, but functional interface method "
                + ASTHelpers.enclosingClass(overriddenMethod)
                + "."
                + overriddenMethod.toString()
                + " returns @NonNull";
      } else {
        message =
            "method returns @Nullable, but superclass method "
                + ASTHelpers.enclosingClass(overriddenMethod)
                + "."
                + overriddenMethod.toString()
                + " returns @NonNull";
      }

      Tree errorTree =
          memberReferenceTree != null
              ? memberReferenceTree
              : getTreesInstance(state).getTree(overridingMethod);
      return errorBuilder.createErrorDescription(
          new ErrorMessage(MessageTypes.WRONG_OVERRIDE_RETURN, message),
          buildDescription(errorTree),
          state,
          overriddenMethod);
    }
    // if any parameter in the super method is annotated @Nullable,
    // overriding method cannot assume @Nonnull
    return checkParamOverriding(
        overridingMethod.getParameters(), overriddenMethod, null, memberReferenceTree, state);
  }

  private boolean overriddenMethodReturnsNonNull(
      Symbol.MethodSymbol overriddenMethod,
      Symbol enclosingSymbol,
      @Nullable MemberReferenceTree memberReferenceTree,
      VisitorState state) {
    Nullness methodReturnNullness =
        getMethodReturnNullness(overriddenMethod, state, Nullness.NULLABLE);
    if (!methodReturnNullness.equals(Nullness.NONNULL)) {
      return false;
    }
    // In JSpecify mode, for generic methods, we additionally need to check the return nullness
    // using the type arguments from the type enclosing the overriding method
    if (config.isJSpecifyMode()) {
      if (memberReferenceTree != null) {
        // For a method reference, we get generic type arguments from javac's inferred type for the
        // tree, which properly preserves type-use annotations
        return GenericsChecks.getGenericMethodReturnTypeNullness(
                overriddenMethod, ASTHelpers.getType(memberReferenceTree), state, config)
            .equals(Nullness.NONNULL);
      } else {
        // Use the enclosing class of the overriding method to find generic type arguments
        return GenericsChecks.getGenericMethodReturnTypeNullness(
                overriddenMethod, enclosingSymbol, state, config)
            .equals(Nullness.NONNULL);
      }
    }
    return true;
  }

  @Override
  public Description matchIdentifier(IdentifierTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    return checkForReadBeforeInit(tree, state);
  }

  private Description checkForReadBeforeInit(ExpressionTree tree, VisitorState state) {
    // do a bunch of filtering.  first, filter out anything outside an initializer
    TreePath path = state.getPath();
    TreePath enclosingBlockPath;
    if (config.assertsEnabled()) {
      enclosingBlockPath = NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(path);
    } else {
      enclosingBlockPath =
          NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(
              path, ImmutableSet.of(Tree.Kind.ASSERT));
    }
    if (enclosingBlockPath == null) {
      // is this possible?
      return Description.NO_MATCH;
    }
    if (!config.assertsEnabled()
        && enclosingBlockPath.getLeaf().getKind().equals(Tree.Kind.ASSERT)) {
      return Description.NO_MATCH;
    }
    if (!relevantInitializerMethodOrBlock(enclosingBlockPath, state)) {
      return Description.NO_MATCH;
    }

    // now, make sure we have a field read
    Symbol symbol = ASTHelpers.getSymbol(tree);
    if (symbol == null) {
      return Description.NO_MATCH;
    }
    if (!symbol.getKind().equals(ElementKind.FIELD)) {
      return Description.NO_MATCH;
    }

    // for static fields, make sure the enclosing init is a static method or block
    if (isStatic(symbol)) {
      Tree enclosing = enclosingBlockPath.getLeaf();
      if (enclosing instanceof MethodTree
          && !ASTHelpers.getSymbol((MethodTree) enclosing).isStatic()) {
        return Description.NO_MATCH;
      } else if (enclosing instanceof BlockTree && !((BlockTree) enclosing).isStatic()) {
        return Description.NO_MATCH;
      }
    }
    if (okToReadBeforeInitialized(path, state)) {
      // writing the field, not reading it
      return Description.NO_MATCH;
    }

    // check that the field might actually be problematic to read
    FieldInitEntities entities =
        castToNonNull(class2Entities.get(enclosingClassSymbol(enclosingBlockPath)));
    if (!(entities.nonnullInstanceFields().contains(symbol)
        || entities.nonnullStaticFields().contains(symbol))) {
      // field is either nullable or initialized at declaration
      return Description.NO_MATCH;
    }
    if (errorBuilder.symbolHasSuppressWarningsAnnotation(symbol, INITIALIZATION_CHECK_NAME)) {
      // also suppress checking read before init, as we may not find explicit initialization
      return Description.NO_MATCH;
    }
    return checkPossibleUninitFieldRead(tree, state, symbol, path, enclosingBlockPath);
  }

  private Symbol.ClassSymbol enclosingClassSymbol(TreePath enclosingBlockPath) {
    Tree leaf = enclosingBlockPath.getLeaf();
    if (leaf instanceof BlockTree) {
      // parent must be a ClassTree
      Tree parent = enclosingBlockPath.getParentPath().getLeaf();
      return ASTHelpers.getSymbol((ClassTree) parent);
    } else {
      return castToNonNull(ASTHelpers.enclosingClass(ASTHelpers.getSymbol(leaf)));
    }
  }

  private boolean relevantInitializerMethodOrBlock(
      TreePath enclosingBlockPath, VisitorState state) {
    Tree methodLambdaOrBlock = enclosingBlockPath.getLeaf();
    if (methodLambdaOrBlock instanceof LambdaExpressionTree) {
      return false;
    } else if (methodLambdaOrBlock instanceof MethodTree) {
      MethodTree methodTree = (MethodTree) methodLambdaOrBlock;
      if (isConstructor(methodTree) && !constructorInvokesAnother(methodTree, state)) {
        return true;
      }

      final Symbol.ClassSymbol enclClassSymbol = enclosingClassSymbol(enclosingBlockPath);

      // Checking for initialization is only meaningful if the full class is null-annotated, which
      // might not be the case with @NullMarked methods inside @NullUnmarked classes (note that,
      // in those cases, we won't even have a populated class2Entities map). We skip this check if
      // we are not inside a @NullMarked/annotated *class*:
      if (nullMarkingForTopLevelClass == NullMarking.PARTIALLY_MARKED
          && !codeAnnotationInfo.isClassNullAnnotated(enclClassSymbol, config)) {
        return false;
      }

      if (ASTHelpers.getSymbol(methodTree).isStatic()) {
        Set<MethodTree> staticInitializerMethods =
            castToNonNull(class2Entities.get(enclClassSymbol)).staticInitializerMethods();
        return staticInitializerMethods.size() == 1
            && staticInitializerMethods.contains(methodTree);
      } else {
        Set<MethodTree> instanceInitializerMethods =
            castToNonNull(class2Entities.get(enclClassSymbol)).instanceInitializerMethods();
        return instanceInitializerMethods.size() == 1
            && instanceInitializerMethods.contains(methodTree);
      }
    } else {
      // initializer or field declaration
      return true;
    }
  }

  private Description checkPossibleUninitFieldRead(
      ExpressionTree tree,
      VisitorState state,
      Symbol symbol,
      TreePath path,
      TreePath enclosingBlockPath) {
    if (!fieldInitializedByPreviousInitializer(symbol, enclosingBlockPath, state)
        && !fieldAlwaysInitializedBeforeRead(symbol, path, state, enclosingBlockPath)) {
      ErrorMessage errorMessage =
          new ErrorMessage(
              MessageTypes.NONNULL_FIELD_READ_BEFORE_INIT,
              "read of @NonNull field " + symbol + " before initialization");
      return errorBuilder.createErrorDescription(errorMessage, buildDescription(tree), state, null);
    } else {
      return Description.NO_MATCH;
    }
  }

  /**
   * @param symbol the field being read
   * @param pathToRead TreePath to the read operation
   * @param state visitor state
   * @param enclosingBlockPath TreePath to enclosing initializer block
   * @return true if within the initializer, the field is always initialized before the read
   *     operation, false otherwise
   */
  private boolean fieldAlwaysInitializedBeforeRead(
      Symbol symbol, TreePath pathToRead, VisitorState state, TreePath enclosingBlockPath) {
    AccessPathNullnessAnalysis nullnessAnalysis = getNullnessAnalysis(state);
    Set<Element> nonnullFields;
    if (isStatic(symbol)) {
      nonnullFields = nullnessAnalysis.getNonnullStaticFieldsBefore(pathToRead, state.context);
    } else {
      nonnullFields = new LinkedHashSet<>();
      nonnullFields.addAll(
          nullnessAnalysis.getNonnullFieldsOfReceiverBefore(pathToRead, state.context));
      nonnullFields.addAll(safeInitByCalleeBefore(pathToRead, state, enclosingBlockPath));
    }
    return nonnullFields.contains(symbol);
  }

  /**
   * computes those fields always initialized by callee safe init methods before a read operation
   * (pathToRead) is invoked. See <a
   * href="https://github.com/uber/NullAway/wiki/Error-Messages#initializer-method-does-not-guarantee-nonnull-field-is-initialized--nonnull-field--not-initialized">the
   * docs</a> for what is considered a safe initializer method.
   */
  private ImmutableSet<Element> safeInitByCalleeBefore(
      TreePath pathToRead, VisitorState state, TreePath enclosingBlockPath) {
    Set<Element> safeInitMethods = new LinkedHashSet<>();
    Tree enclosingBlockOrMethod = enclosingBlockPath.getLeaf();
    if (enclosingBlockOrMethod instanceof VariableTree) {
      return ImmutableSet.of();
    }
    ImmutableSet.Builder<Element> resultBuilder = ImmutableSet.builder();
    BlockTree blockTree =
        enclosingBlockOrMethod instanceof BlockTree
            ? (BlockTree) enclosingBlockOrMethod
            : ((MethodTree) enclosingBlockOrMethod).getBody();
    List<? extends StatementTree> statements = blockTree.getStatements();
    Tree readExprTree = pathToRead.getLeaf();
    int readStartPos = getStartPos((JCTree) readExprTree);
    TreePath classTreePath = enclosingBlockPath;
    // look for the parent ClassTree node, which represents the enclosing class / enum / interface
    while (!(classTreePath.getLeaf() instanceof ClassTree)) {
      classTreePath = classTreePath.getParentPath();
      if (classTreePath == null) {
        throw new IllegalStateException(
            "could not find enclosing class / enum / interface for "
                + state.getSourceForNode(enclosingBlockPath.getLeaf()));
      }
    }
    Symbol.ClassSymbol classSymbol = ASTHelpers.getSymbol((ClassTree) classTreePath.getLeaf());
    for (int i = 0; i < statements.size(); i++) {
      StatementTree curStmt = statements.get(i);
      if (getStartPos((JCTree) curStmt) <= readStartPos) {
        Element privMethodElem = getInvokeOfSafeInitMethod(curStmt, classSymbol, state);
        if (privMethodElem != null) {
          safeInitMethods.add(privMethodElem);
        }
        // Hack: Handling try{...}finally{...} statement, see getSafeInitMethods
        if (curStmt.getKind().equals(Tree.Kind.TRY)) {
          TryTree tryTree = (TryTree) curStmt;
          // ToDo: Should we check initialization inside tryTree.getResources ? What is the scope of
          // that initialization?
          if (tryTree.getCatches().size() == 0) {
            if (tryTree.getBlock() != null) {
              resultBuilder.addAll(
                  safeInitByCalleeBefore(
                      pathToRead, state, new TreePath(enclosingBlockPath, tryTree.getBlock())));
            }
            if (tryTree.getFinallyBlock() != null) {
              resultBuilder.addAll(
                  safeInitByCalleeBefore(
                      pathToRead,
                      state,
                      new TreePath(enclosingBlockPath, tryTree.getFinallyBlock())));
            }
          }
        }
      }
    }
    addGuaranteedNonNullFromInvokes(
        state, getTreesInstance(state), safeInitMethods, getNullnessAnalysis(state), resultBuilder);
    return resultBuilder.build();
  }

  private int getStartPos(JCTree tree) {
    return tree.pos().getStartPosition();
  }

  /**
   * @param fieldSymbol the field
   * @param initTreePath TreePath to the initializer method / block
   * @param state visitor state
   * @return true if the field is always initialized (by some other initializer) before the
   *     initializer corresponding to initTreePath executes
   */
  private boolean fieldInitializedByPreviousInitializer(
      Symbol fieldSymbol, TreePath initTreePath, VisitorState state) {
    TreePath enclosingClassPath = initTreePath.getParentPath();
    ClassTree enclosingClass = (ClassTree) enclosingClassPath.getLeaf();
    ClassSymbol classSymbol = ASTHelpers.getSymbol(enclosingClass);
    Multimap<Tree, Element> tree2Init =
        initTree2PrevFieldInit.computeIfAbsent(
            classSymbol, sym -> computeTree2Init(enclosingClassPath, state));
    return tree2Init.containsEntry(initTreePath.getLeaf(), fieldSymbol);
  }

  /**
   * @param enclosingClassPath TreePath to class
   * @param state visitor state
   * @return a map from each initializer <em>i</em> to the fields known to be initialized before
   *     <em>i</em> executes
   */
  private Multimap<Tree, Element> computeTree2Init(
      TreePath enclosingClassPath, VisitorState state) {
    ClassTree enclosingClass = (ClassTree) enclosingClassPath.getLeaf();
    ImmutableMultimap.Builder<Tree, Element> builder = ImmutableMultimap.builder();
    // NOTE: this set includes both instance and static fields
    Set<Element> initThusFar = new LinkedHashSet<>();
    Set<MethodTree> constructors = new LinkedHashSet<>();
    AccessPathNullnessAnalysis nullnessAnalysis = getNullnessAnalysis(state);
    // NOTE: we assume the members are returned in their syntactic order.  This has held
    // true in our testing
    for (Tree memberTree : enclosingClass.getMembers()) {
      if (memberTree instanceof VariableTree || memberTree instanceof BlockTree) {
        // putAll does not keep a reference to initThusFar, so we don't need to make a copy here
        builder.putAll(memberTree, initThusFar);
      }
      if (memberTree instanceof BlockTree) {
        BlockTree blockTree = (BlockTree) memberTree;
        // add whatever gets initialized here
        TreePath memberPath = new TreePath(enclosingClassPath, memberTree);
        if (blockTree.isStatic()) {
          initThusFar.addAll(
              nullnessAnalysis.getNonnullStaticFieldsAtExit(memberPath, state.context));
        } else {
          initThusFar.addAll(
              nullnessAnalysis.getNonnullFieldsOfReceiverAtExit(memberPath, state.context));
        }
      }
      if (memberTree instanceof MethodTree) {
        MethodTree methodTree = (MethodTree) memberTree;
        if (isConstructor(methodTree)) {
          constructors.add(methodTree);
        }
      }
    }
    // all the initializer blocks have run before any code inside a constructor
    constructors.stream().forEach((c) -> builder.putAll(c, initThusFar));
    Symbol.ClassSymbol classSymbol = ASTHelpers.getSymbol(enclosingClass);
    FieldInitEntities entities = castToNonNull(class2Entities.get(classSymbol));
    if (entities.instanceInitializerMethods().size() == 1) {
      MethodTree initMethod = entities.instanceInitializerMethods().iterator().next();
      // collect the fields that may not be initialized by *some* constructor NC
      Set<Symbol> constructorUninitSymbols = class2ConstructorUninit.get(classSymbol);
      // fields initialized after constructors is initThusFar + (nonNullFields - constructorUninit)
      Sets.SetView<Element> initAfterConstructors =
          Sets.union(
              initThusFar,
              Sets.difference(entities.nonnullInstanceFields(), constructorUninitSymbols));
      builder.putAll(initMethod, initAfterConstructors);
    }
    if (entities.staticInitializerMethods().size() == 1) {
      MethodTree staticInitMethod = entities.staticInitializerMethods().iterator().next();
      // constructors aren't relevant here; just use initThusFar
      builder.putAll(staticInitMethod, initThusFar);
    }
    return builder.build();
  }

  /**
   * @param path tree path to read operation
   * @param state the current VisitorState
   * @return true if it is permissible to perform this read before the field has been initialized,
   *     false otherwise
   */
  private boolean okToReadBeforeInitialized(TreePath path, VisitorState state) {
    TreePath parentPath = path.getParentPath();
    Tree leaf = path.getLeaf();
    Tree parent = parentPath.getLeaf();
    if (parent instanceof AssignmentTree) {
      // ok if it's actually a write
      AssignmentTree assignment = (AssignmentTree) parent;
      return assignment.getVariable().equals(leaf);
    } else if (parent instanceof BinaryTree) {
      // ok if we're comparing to null
      BinaryTree binaryTree = (BinaryTree) parent;
      Tree.Kind kind = binaryTree.getKind();
      if (kind.equals(Tree.Kind.EQUAL_TO) || kind.equals(Tree.Kind.NOT_EQUAL_TO)) {
        ExpressionTree left = binaryTree.getLeftOperand();
        ExpressionTree right = binaryTree.getRightOperand();
        return (left.equals(leaf) && right.getKind().equals(Tree.Kind.NULL_LITERAL))
            || (right.equals(leaf) && left.getKind().equals(Tree.Kind.NULL_LITERAL));
      }
    } else if (parent instanceof MethodInvocationTree) {
      // ok if it's invoking castToNonNull and the read is the argument
      MethodInvocationTree methodInvoke = (MethodInvocationTree) parent;
      Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(methodInvoke);
      String qualifiedName =
          ASTHelpers.enclosingClass(methodSymbol) + "." + methodSymbol.getSimpleName().toString();
      List<? extends ExpressionTree> arguments = methodInvoke.getArguments();
      Integer castToNonNullArg;
      if (qualifiedName.equals(config.getCastToNonNullMethod())
          && methodSymbol.getParameters().size() == 1) {
        castToNonNullArg = 0;
      } else {
        castToNonNullArg =
            handler.castToNonNullArgumentPositionsForMethod(
                this, state, methodSymbol, arguments, null);
      }
      if (castToNonNullArg != null && leaf.equals(arguments.get(castToNonNullArg))) {
        return true;
      }
      return false;
    }
    return false;
  }

  @Override
  public Description matchVariable(VariableTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    VarSymbol symbol = ASTHelpers.getSymbol(tree);
    if (tree.getInitializer() != null) {
      GenericsChecks.checkTypeParameterNullnessForAssignability(tree, this, state);
    }

    if (symbol.type.isPrimitive() && tree.getInitializer() != null) {
      doUnboxingCheck(state, tree.getInitializer());
    }
    if (!symbol.getKind().equals(ElementKind.FIELD)) {
      return Description.NO_MATCH;
    }
    ExpressionTree initializer = tree.getInitializer();
    if (initializer != null) {
      if (!symbol.type.isPrimitive() && !skipDueToFieldAnnotation(symbol)) {
        if (mayBeNullExpr(state, initializer)) {
          final ErrorMessage errorMessage =
              new ErrorMessage(
                  MessageTypes.ASSIGN_FIELD_NULLABLE,
                  "assigning @Nullable expression to @NonNull field");
          return errorBuilder.createErrorDescriptionForNullAssignment(
              errorMessage, initializer, buildDescription(tree), state, symbol);
        }
      }
    }
    return Description.NO_MATCH;
  }

  /**
   * Check if an inner class's annotation means this Compilation Unit is partially annotated.
   *
   * <p>Returns true iff classSymbol has a direct @NullMarked or @NullUnmarked annotation which
   * differs from the {@link NullMarking} of the top-level class, meaning the compilation unit is
   * itself partially marked, and we need to switch to our slower mode for detecting whether we are
   * in unannotated code.
   *
   * @param classSymbol a ClassSymbol representing an inner class within the current compilation
   *     unit.
   * @return true iff this inner class is @NullMarked and the top-level class unmarked or vice
   *     versa.
   */
  private boolean classAnnotationIntroducesPartialMarking(Symbol.ClassSymbol classSymbol) {
    return (nullMarkingForTopLevelClass == NullMarking.FULLY_UNMARKED
            && ASTHelpers.hasDirectAnnotationWithSimpleName(
                classSymbol, NullabilityUtil.NULLMARKED_SIMPLE_NAME))
        || (nullMarkingForTopLevelClass == NullMarking.FULLY_MARKED
            && ASTHelpers.hasDirectAnnotationWithSimpleName(
                classSymbol, NullabilityUtil.NULLUNMARKED_SIMPLE_NAME));
  }

  @Override
  public Description matchClass(ClassTree tree, VisitorState state) {
    // Ensure codeAnnotationInfo is initialized here since it requires access to the Context,
    // which is not available in the constructor
    if (codeAnnotationInfo == null) {
      codeAnnotationInfo = CodeAnnotationInfo.instance(state.context);
    }
    // Check if the class is excluded according to the filter
    // if so, set the flag to match within the class to false
    // NOTE: for this mechanism to work, we rely on the enclosing ClassTree
    // always being visited before code within that class.  We also
    // assume that a single checker object is not being
    // used from multiple threads
    // We don't want to update the flag for nested classes.
    // Ideally we would keep a stack of flags to handle nested types,
    // but this is not easy within the Error Prone APIs.
    // Instead, we use this flag as an optimization, skipping work if the
    // top-level class is to be skipped. If a nested class should be
    // skipped, we instead rely on last-minute suppression of the
    // error message, using the mechanism in
    // ErrorBuilder.hasPathSuppression(...)
    Symbol.ClassSymbol classSymbol = ASTHelpers.getSymbol(tree);
    NestingKind nestingKind = classSymbol.getNestingKind();
    if (!nestingKind.isNested()) {
      // Here we optimistically set the marking to either FULLY_UNMARKED or FULLY_MARKED.  If a
      // nested entity has a contradicting annotation, at that point we update the marking level to
      // PARTIALLY_MARKED, which will increase checking overhead for the remainder of the top-level
      // class
      nullMarkingForTopLevelClass =
          isExcludedClass(classSymbol) ? NullMarking.FULLY_UNMARKED : NullMarking.FULLY_MARKED;
      // since we are processing a new top-level class, invalidate any cached
      // results for previous classes
      handler.onMatchTopLevelClass(this, tree, state, classSymbol);
      getNullnessAnalysis(state).invalidateCaches();
      initTree2PrevFieldInit.clear();
      class2Entities.clear();
      class2ConstructorUninit.clear();
      computedNullnessMap.clear();
      EnclosingEnvironmentNullness.instance(state.context).clear();
    } else if (classAnnotationIntroducesPartialMarking(classSymbol)) {
      // Handle the case where the top-class is unannotated, but there is a @NullMarked annotation
      // on a nested class, or, conversely the top-level is annotated but there is a @NullUnmarked
      // annotation on a nested class.
      nullMarkingForTopLevelClass = NullMarking.PARTIALLY_MARKED;
    }
    if (withinAnnotatedCode(state)) {
      // we need to update the environment before checking field initialization, as the latter
      // may run dataflow analysis
      if (nestingKind.equals(NestingKind.LOCAL) || nestingKind.equals(NestingKind.ANONYMOUS)) {
        updateEnvironmentMapping(state.getPath(), state);
      }
      checkFieldInitialization(tree, state);
    }
    return Description.NO_MATCH;
  }

  // UNBOXING CHECKS

  @Override
  public Description matchBinary(BinaryTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    // Perform unboxing checks on operands if needed
    Type binaryExprType = ASTHelpers.getType(tree);
    // If the type of the expression is not primitive, we do not need to do unboxing checks.  This
    // handles the case of `+` used for string concatenation
    if (binaryExprType == null || !binaryExprType.isPrimitive()) {
      return Description.NO_MATCH;
    }
    Tree.Kind kind = tree.getKind();
    ExpressionTree leftOperand = tree.getLeftOperand();
    ExpressionTree rightOperand = tree.getRightOperand();
    if (kind.equals(Tree.Kind.EQUAL_TO) || kind.equals(Tree.Kind.NOT_EQUAL_TO)) {
      // here we need a check if one operand is of primitive type and the other is not, as that will
      // cause unboxing of the non-primitive operand
      Type leftType = ASTHelpers.getType(leftOperand);
      Type rightType = ASTHelpers.getType(rightOperand);
      if (leftType == null || rightType == null) {
        return Description.NO_MATCH;
      }
      if (leftType.isPrimitive() && !rightType.isPrimitive()) {
        doUnboxingCheck(state, rightOperand);
      } else if (rightType.isPrimitive() && !leftType.isPrimitive()) {
        doUnboxingCheck(state, leftOperand);
      }
    } else {
      // in all other cases, both operands should be checked
      doUnboxingCheck(state, leftOperand, rightOperand);
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchUnary(UnaryTree tree, VisitorState state) {
    if (withinAnnotatedCode(state)) {
      doUnboxingCheck(state, tree.getExpression());
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchConditionalExpression(
      ConditionalExpressionTree tree, VisitorState state) {
    if (withinAnnotatedCode(state)) {
      GenericsChecks.checkTypeParameterNullnessForConditionalExpression(tree, this, state);
      doUnboxingCheck(state, tree.getCondition());
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchIf(IfTree tree, VisitorState state) {
    if (withinAnnotatedCode(state)) {
      doUnboxingCheck(state, tree.getCondition());
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchWhileLoop(WhileLoopTree tree, VisitorState state) {
    if (withinAnnotatedCode(state)) {
      doUnboxingCheck(state, tree.getCondition());
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchForLoop(ForLoopTree tree, VisitorState state) {
    if (withinAnnotatedCode(state) && tree.getCondition() != null) {
      doUnboxingCheck(state, tree.getCondition());
    }
    return Description.NO_MATCH;
  }

  @Override
  public Description matchEnhancedForLoop(EnhancedForLoopTree tree, VisitorState state) {
    if (!withinAnnotatedCode(state)) {
      return Description.NO_MATCH;
    }
    ExpressionTree expr = tree.getExpression();
    final ErrorMessage errorMessage =
        new ErrorMessage(
            MessageTypes.DEREFERENCE_NULLABLE,
            "enhanced-for expression " + state.getSourceForNode(expr) + " is @Nullable");
    if (mayBeNullExpr(state, expr)) {
      return errorBuilder.createErrorDescription(errorMessage, buildDescription(expr), state, null);
    }
    return Description.NO_MATCH;
  }

  /**
   * Checks that all given expressions cannot be null, and for those that are {@code @Nullable},
   * reports an unboxing error.
   *
   * @param state the visitor state, used to report errors via {@link
   *     VisitorState#reportMatch(Description)}
   * @param expressions expressions to check
   */
  private void doUnboxingCheck(VisitorState state, ExpressionTree... expressions) {
    for (ExpressionTree tree : expressions) {
      Type type = ASTHelpers.getType(tree);
      if (type == null) {
        throw new RuntimeException("was not expecting null type");
      }
      if (!type.isPrimitive()) {
        if (mayBeNullExpr(state, tree)) {
          final ErrorMessage errorMessage =
              new ErrorMessage(MessageTypes.UNBOX_NULLABLE, "unboxing of a @Nullable value");
          state.reportMatch(
              errorBuilder.createErrorDescription(
                  errorMessage, buildDescription(tree), state, null));
        }
      }
    }
  }

  /**
   * handle either a method invocation or a 'new' invocation
   *
   * @param tree the corresponding MethodInvocationTree or NewClassTree
   * @param state visitor state
   * @param methodSymbol symbol for invoked method
   * @param actualParams parameters passed at call
   * @return description of error or NO_MATCH if no error
   */
  private Description handleInvocation(
      Tree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol,
      List<? extends ExpressionTree> actualParams) {
    List<VarSymbol> formalParams = methodSymbol.getParameters();

    if (formalParams.size() != actualParams.size()
        && !methodSymbol.isVarArgs()
        && !methodSymbol.isStatic()
        && methodSymbol.isConstructor()
        && methodSymbol.enclClass().isInner()) {
      // In special cases like one in issue #366
      // formal params and actual params do not match while using JDK11+
      // we bail out in this particular case
      return Description.NO_MATCH;
    }

    final boolean isMethodAnnotated = !codeAnnotationInfo.isSymbolUnannotated(methodSymbol, config);
    // If argumentPositionNullness[i] == null, parameter i is unannotated
    Nullness[] argumentPositionNullness = new Nullness[formalParams.size()];

    if (isMethodAnnotated) {
      // compute which arguments are @NonNull
      for (int i = 0; i < formalParams.size(); i++) {
        VarSymbol param = formalParams.get(i);
        if (param.type.isPrimitive()) {
          doUnboxingCheck(state, actualParams.get(i));
          argumentPositionNullness[i] = Nullness.NONNULL;
        } else if (ASTHelpers.isSameType(
            param.type, Suppliers.JAVA_LANG_VOID_TYPE.get(state), state)) {
          // Temporarily treat a Void argument type as if it were @Nullable Void. Handling of Void
          // without special-casing, as recommended by JSpecify might: a) require generics support
          // and, b) require checking that third-party libraries considered annotated adopt
          // JSpecify semantics.
          // See the suppression in https://github.com/uber/NullAway/pull/608 for an example of why
          // this is needed.
          argumentPositionNullness[i] = Nullness.NULLABLE;
        } else {
          // we need to call paramHasNullableAnnotation here since the invoked method may be defined
          // in a class file
          argumentPositionNullness[i] =
              Nullness.paramHasNullableAnnotation(methodSymbol, i, config)
                  ? Nullness.NULLABLE
                  : ((config.isJSpecifyMode() && tree instanceof MethodInvocationTree)
                      ? GenericsChecks.getGenericParameterNullnessAtInvocation(
                          i, methodSymbol, (MethodInvocationTree) tree, state, config)
                      : Nullness.NONNULL);
        }
      }
      GenericsChecks.compareGenericTypeParameterNullabilityForCall(
          formalParams, actualParams, methodSymbol.isVarArgs(), this, state);
    }

    // Allow handlers to override the list of non-null argument positions
    argumentPositionNullness =
        handler.onOverrideMethodInvocationParametersNullability(
            state.context, methodSymbol, isMethodAnnotated, argumentPositionNullness);

    // now actually check the arguments
    // NOTE: the case of an invocation on a possibly-null reference
    // is handled by matchMemberSelect()
    for (int argPos = 0; argPos < argumentPositionNullness.length; argPos++) {
      if (!Objects.equals(Nullness.NONNULL, argumentPositionNullness[argPos])) {
        continue;
      }
      ExpressionTree actual = null;
      boolean mayActualBeNull = false;
      if (argPos == formalParams.size() - 1 && methodSymbol.isVarArgs()) {
        // Check all vararg actual arguments for nullability
        if (actualParams.size() <= argPos) {
          continue;
        }
        for (ExpressionTree arg : actualParams.subList(argPos, actualParams.size())) {
          actual = arg;
          mayActualBeNull = mayBeNullExpr(state, actual);
          if (mayActualBeNull) {
            break;
          }
        }
      } else {
        actual = actualParams.get(argPos);
        mayActualBeNull = mayBeNullExpr(state, actual);
      }
      // This statement should be unreachable without assigning actual beforehand:
      Preconditions.checkNotNull(actual);
      // make sure we are passing a non-null value
      if (mayActualBeNull) {
        String message =
            "passing @Nullable parameter '"
                + state.getSourceForNode(actual)
                + "' where @NonNull is required";
        ErrorMessage errorMessage = new ErrorMessage(MessageTypes.PASS_NULLABLE, message);
        state.reportMatch(
            errorBuilder.createErrorDescriptionForNullAssignment(
                errorMessage, actual, buildDescription(actual), state, formalParams.get(argPos)));
      }
    }
    // Check for @NonNull being passed to castToNonNull (if configured)
    return checkCastToNonNullTakesNullable(tree, state, methodSymbol, actualParams);
  }

  private Description checkCastToNonNullTakesNullable(
      Tree tree,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol,
      List<? extends ExpressionTree> actualParams) {
    String qualifiedName =
        ASTHelpers.enclosingClass(methodSymbol) + "." + methodSymbol.getSimpleName().toString();
    Integer castToNonNullPosition;
    if (qualifiedName.equals(config.getCastToNonNullMethod())
        && methodSymbol.getParameters().size() == 1) {
      // castToNonNull method passed to CLI config, it acts as a cast-to-non-null on its first
      // argument. Since this is a single argument method, we skip further querying of handlers.
      castToNonNullPosition = 0;
    } else {
      castToNonNullPosition =
          handler.castToNonNullArgumentPositionsForMethod(
              this, state, methodSymbol, actualParams, null);
    }
    if (castToNonNullPosition != null) {
      ExpressionTree actual = actualParams.get(castToNonNullPosition);
      TreePath enclosingMethodOrLambda =
          NullabilityUtil.findEnclosingMethodOrLambdaOrInitializer(state.getPath());
      boolean isInitializer;
      if (enclosingMethodOrLambda == null) {
        throw new RuntimeException("no enclosing method, lambda or initializer!");
      } else if (enclosingMethodOrLambda.getLeaf() instanceof LambdaExpressionTree) {
        isInitializer = false;
      } else if (enclosingMethodOrLambda.getLeaf() instanceof MethodTree) {
        MethodTree enclosingMethod = (MethodTree) enclosingMethodOrLambda.getLeaf();
        isInitializer = isInitializerMethod(state, ASTHelpers.getSymbol(enclosingMethod));
      } else {
        // Initializer block
        isInitializer = true;
      }
      if (!isInitializer && !mayBeNullExpr(state, actual)) {
        String message =
            "passing known @NonNull parameter '"
                + state.getSourceForNode(actual)
                + "' to CastToNonNullMethod ("
                + qualifiedName
                + ") at position "
                + castToNonNullPosition
                + ". This method argument should only take values that NullAway considers @Nullable "
                + "at the invocation site, but which are known not to be null at runtime.";
        return errorBuilder.createErrorDescription(
            new ErrorMessage(MessageTypes.CAST_TO_NONNULL_ARG_NONNULL, message),
            // The Tree passed as suggestTree is the expression being cast
            // to avoid recomputing the arg index:
            actual,
            buildDescription(tree),
            state,
            null);
      }
    }
    return Description.NO_MATCH;
  }

  /**
   * check that all @NonNull fields of the class are properly initialized
   *
   * @param tree the class
   * @param state visitor state
   */
  private void checkFieldInitialization(ClassTree tree, VisitorState state) {
    FieldInitEntities entities = collectEntities(tree, state);
    Symbol.ClassSymbol classSymbol = ASTHelpers.getSymbol(tree);
    class2Entities.put(classSymbol, entities);
    // set of all non-null instance fields f such that *some* constructor does not initialize f
    Set<Symbol> notInitializedInConstructors;
    SetMultimap<MethodTree, Symbol> constructorInitInfo;
    if (entities.constructors().isEmpty()) {
      constructorInitInfo = null;
      notInitializedInConstructors = entities.nonnullInstanceFields();
    } else {
      constructorInitInfo = checkConstructorInitialization(entities, state);
      notInitializedInConstructors = ImmutableSet.copyOf(constructorInitInfo.values());
    }
    // Filter out final fields, since javac will already check initialization
    notInitializedInConstructors =
        ImmutableSet.copyOf(
            Sets.filter(
                notInitializedInConstructors,
                symbol -> !symbol.getModifiers().contains(Modifier.FINAL)));
    class2ConstructorUninit.putAll(classSymbol, notInitializedInConstructors);
    Set<Symbol> notInitializedAtAll =
        notAssignedInAnyInitializer(entities, notInitializedInConstructors, state);
    SetMultimap<Element, Element> errorFieldsForInitializer = LinkedHashMultimap.create();
    // non-null if we have a single initializer method
    Symbol.MethodSymbol singleInitializerMethod = null;
    if (entities.instanceInitializerMethods().size() == 1) {
      singleInitializerMethod =
          ASTHelpers.getSymbol(entities.instanceInitializerMethods().iterator().next());
    }
    for (Symbol uninitField : notInitializedAtAll) {
      if (errorBuilder.symbolHasSuppressWarningsAnnotation(
          uninitField, INITIALIZATION_CHECK_NAME)) {
        continue;
      }
      if (singleInitializerMethod != null) {
        // report it on the initializer
        errorFieldsForInitializer.put(singleInitializerMethod, uninitField);
      } else if (constructorInitInfo == null) {
        // report it on the field, except in the case where the class is externalInit and
        // we have no initializer methods
        if (!(symbolHasExternalInitAnnotation(classSymbol)
            && entities.instanceInitializerMethods().isEmpty())) {
          errorBuilder.reportInitErrorOnField(
              uninitField, state, buildDescription(getTreesInstance(state).getTree(uninitField)));
        }
      } else {
        // report it on each constructor that does not initialize it
        for (MethodTree methodTree : constructorInitInfo.keySet()) {
          Set<Symbol> uninitFieldsForConstructor = constructorInitInfo.get(methodTree);
          if (uninitFieldsForConstructor.contains(uninitField)) {
            errorFieldsForInitializer.put(ASTHelpers.getSymbol(methodTree), uninitField);
          }
        }
      }
    }
    for (Element constructorElement : errorFieldsForInitializer.keySet()) {
      ImmutableList<Symbol> fieldSymbols =
          errorFieldsForInitializer.get(constructorElement).stream()
              .map(element -> ASTHelpers.getSymbol(getTreesInstance(state).getTree(element)))
              .collect(ImmutableList.toImmutableList());

      errorBuilder.reportInitializerError(
          (Symbol.MethodSymbol) constructorElement,
          errMsgForInitializer(errorFieldsForInitializer.get(constructorElement), state),
          state,
          buildDescription(getTreesInstance(state).getTree(constructorElement)),
          fieldSymbols);
    }
    // For static fields
    Set<Symbol> notInitializedStaticFields = notInitializedStatic(entities, state);
    for (Symbol uninitSField : notInitializedStaticFields) {
      // Always report it on the field for static fields (can't do @SuppressWarnings on a static
      // initialization block
      // anyways).
      errorBuilder.reportInitErrorOnField(
          uninitSField, state, buildDescription(getTreesInstance(state).getTree(uninitSField)));
    }
  }

  /**
   * @param entities relevant entities from class
   * @param notInitializedInConstructors those fields not initialized in some constructor
   * @param state visitor state
   * @return those fields from notInitializedInConstructors that are not initialized in any
   *     initializer method
   */
  private Set<Symbol> notAssignedInAnyInitializer(
      FieldInitEntities entities, Set<Symbol> notInitializedInConstructors, VisitorState state) {
    Trees trees = getTreesInstance(state);
    Symbol.ClassSymbol classSymbol = entities.classSymbol();
    ImmutableSet.Builder<Element> initInSomeInitializerBuilder = ImmutableSet.builder();
    for (MethodTree initMethodTree : entities.instanceInitializerMethods()) {
      if (initMethodTree.getBody() == null) {
        continue;
      }
      addInitializedFieldsForBlock(
          state,
          trees,
          classSymbol,
          initInSomeInitializerBuilder,
          initMethodTree.getBody(),
          new TreePath(state.getPath(), initMethodTree));
    }
    for (BlockTree block : entities.instanceInitializerBlocks()) {
      addInitializedFieldsForBlock(
          state,
          trees,
          classSymbol,
          initInSomeInitializerBuilder,
          block,
          new TreePath(state.getPath(), block));
    }
    Set<Symbol> result = new LinkedHashSet<>();
    ImmutableSet<Element> initInSomeInitializer = initInSomeInitializerBuilder.build();
    for (Symbol fieldSymbol : notInitializedInConstructors) {
      if (!initInSomeInitializer.contains(fieldSymbol)) {
        result.add(fieldSymbol);
      }
    }
    return result;
  }

  private void addInitializedFieldsForBlock(
      VisitorState state,
      Trees trees,
      Symbol.ClassSymbol classSymbol,
      ImmutableSet.Builder<Element> initInSomeInitializerBuilder,
      BlockTree block,
      TreePath path) {
    AccessPathNullnessAnalysis nullnessAnalysis = getNullnessAnalysis(state);
    Set<Element> nonnullAtExit =
        nullnessAnalysis.getNonnullFieldsOfReceiverAtExit(path, state.context);
    initInSomeInitializerBuilder.addAll(nonnullAtExit);
    Set<Element> safeInitMethods = getSafeInitMethods(block, classSymbol, state);
    addGuaranteedNonNullFromInvokes(
        state, trees, safeInitMethods, nullnessAnalysis, initInSomeInitializerBuilder);
  }

  /**
   * @param entities field init info
   * @param state visitor state
   * @return a map from each constructor C to the nonnull fields that C does *not* initialize
   */
  private SetMultimap<MethodTree, Symbol> checkConstructorInitialization(
      FieldInitEntities entities, VisitorState state) {
    SetMultimap<MethodTree, Symbol> result = LinkedHashMultimap.create();
    Set<Symbol> nonnullInstanceFields = entities.nonnullInstanceFields();
    Trees trees = getTreesInstance(state);
    boolean isExternalInitClass = symbolHasExternalInitAnnotation(entities.classSymbol());
    for (MethodTree constructor : entities.constructors()) {
      if (constructorInvokesAnother(constructor, state)) {
        continue;
      }
      if (constructor.getParameters().size() == 0
          && (isExternalInitClass
              || symbolHasExternalInitAnnotation(ASTHelpers.getSymbol(constructor)))) {
        // external framework initializes fields in this case
        continue;
      }
      Set<Element> guaranteedNonNull =
          guaranteedNonNullForConstructor(entities, state, trees, constructor);
      for (Symbol fieldSymbol : nonnullInstanceFields) {
        if (!guaranteedNonNull.contains(fieldSymbol)) {
          result.put(constructor, fieldSymbol);
        }
      }
    }
    return result;
  }

  private boolean symbolHasExternalInitAnnotation(Symbol symbol) {
    return StreamSupport.stream(
            NullabilityUtil.getAllAnnotations(symbol, config).spliterator(), false)
        .map((anno) -> anno.getAnnotationType().toString())
        .anyMatch(config::isExternalInitClassAnnotation);
  }

  private ImmutableSet<Element> guaranteedNonNullForConstructor(
      FieldInitEntities entities, VisitorState state, Trees trees, MethodTree constructor) {
    Set<Element> safeInitMethods =
        getSafeInitMethods(constructor.getBody(), entities.classSymbol(), state);
    AccessPathNullnessAnalysis nullnessAnalysis = getNullnessAnalysis(state);
    ImmutableSet.Builder<Element> guaranteedNonNullBuilder = ImmutableSet.builder();
    guaranteedNonNullBuilder.addAll(
        nullnessAnalysis.getNonnullFieldsOfReceiverAtExit(
            new TreePath(state.getPath(), constructor), state.context));
    addGuaranteedNonNullFromInvokes(
        state, trees, safeInitMethods, nullnessAnalysis, guaranteedNonNullBuilder);
    return guaranteedNonNullBuilder.build();
  }

  /** does the constructor invoke another constructor in the same class via this(...)? */
  private boolean constructorInvokesAnother(MethodTree constructor, VisitorState state) {
    BlockTree body = constructor.getBody();
    List<? extends StatementTree> statements = body.getStatements();
    if (statements.size() > 0) {
      StatementTree statementTree = statements.get(0);
      if (isThisCall(statementTree, state)) {
        return true;
      }
    }
    return false;
  }

  private Set<Symbol> notInitializedStatic(FieldInitEntities entities, VisitorState state) {
    Set<Symbol> nonNullStaticFields = entities.nonnullStaticFields();
    Set<Element> initializedInStaticInitializers = new LinkedHashSet<Element>();
    AccessPathNullnessAnalysis nullnessAnalysis = getNullnessAnalysis(state);
    for (BlockTree initializer : entities.staticInitializerBlocks()) {
      Set<Element> nonnullAtExit =
          nullnessAnalysis.getNonnullStaticFieldsAtExit(
              new TreePath(state.getPath(), initializer), state.context);
      initializedInStaticInitializers.addAll(nonnullAtExit);
    }
    for (MethodTree initializerMethod : entities.staticInitializerMethods()) {
      Set<Element> nonnullAtExit =
          nullnessAnalysis.getNonnullStaticFieldsAtExit(
              new TreePath(state.getPath(), initializerMethod), state.context);
      initializedInStaticInitializers.addAll(nonnullAtExit);
    }
    Set<Symbol> notInitializedStaticFields = new LinkedHashSet<Symbol>();
    for (Symbol field : nonNullStaticFields) {
      if (!initializedInStaticInitializers.contains(field)) {
        notInitializedStaticFields.add(field);
      }
    }
    return notInitializedStaticFields;
  }

  private void addGuaranteedNonNullFromInvokes(
      VisitorState state,
      Trees trees,
      Set<Element> safeInitMethods,
      AccessPathNullnessAnalysis nullnessAnalysis,
      ImmutableSet.Builder<Element> guaranteedNonNullBuilder) {
    for (Element invoked : safeInitMethods) {
      Tree invokedTree = trees.getTree(invoked);
      guaranteedNonNullBuilder.addAll(
          nullnessAnalysis.getNonnullFieldsOfReceiverAtExit(
              new TreePath(state.getPath(), invokedTree), state.context));
    }
  }

  /**
   * @param blockTree block of statements
   * @param state visitor state
   * @return Elements of safe init methods that are invoked as top-level statements in the method
   */
  private Set<Element> getSafeInitMethods(
      BlockTree blockTree, Symbol.ClassSymbol classSymbol, VisitorState state) {
    Set<Element> result = new LinkedHashSet<>();
    List<? extends StatementTree> statements = blockTree.getStatements();
    for (StatementTree stmt : statements) {
      Element privMethodElem = getInvokeOfSafeInitMethod(stmt, classSymbol, state);
      if (privMethodElem != null) {
        result.add(privMethodElem);
      }
      // Hack: If we see a try{...}finally{...} statement, without a catch, we consider the methods
      // inside both blocks
      // as "top level" for the purposes of finding initialization methods. Any exception happening
      // there is also an
      // exception of the full method.
      if (stmt.getKind().equals(Tree.Kind.TRY)) {
        TryTree tryTree = (TryTree) stmt;
        if (tryTree.getCatches().size() == 0) {
          if (tryTree.getBlock() != null) {
            result.addAll(getSafeInitMethods(tryTree.getBlock(), classSymbol, state));
          }
          if (tryTree.getFinallyBlock() != null) {
            result.addAll(getSafeInitMethods(tryTree.getFinallyBlock(), classSymbol, state));
          }
        }
      }
    }
    return result;
  }

  /**
   * A safe init method is an instance method that is either private or final (so no overriding is
   * possible)
   *
   * @param stmt the statement
   * @param enclosingClassSymbol symbol for enclosing constructor / initializer
   * @param state visitor state
   * @return element of safe init function if stmt invokes that function; null otherwise
   */
  @Nullable
  private Element getInvokeOfSafeInitMethod(
      StatementTree stmt, final Symbol.ClassSymbol enclosingClassSymbol, VisitorState state) {
    Matcher<ExpressionTree> invokeMatcher =
        (expressionTree, s) -> {
          if (!(expressionTree instanceof MethodInvocationTree)) {
            return false;
          }
          MethodInvocationTree methodInvocationTree = (MethodInvocationTree) expressionTree;
          Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(methodInvocationTree);
          Set<Modifier> modifiers = symbol.getModifiers();
          Set<Modifier> classModifiers = enclosingClassSymbol.getModifiers();
          if ((symbol.isPrivate()
                  || modifiers.contains(Modifier.FINAL)
                  || classModifiers.contains(Modifier.FINAL))
              && !symbol.isStatic()
              && !modifiers.contains(Modifier.NATIVE)) {
            // check it's the same class (could be an issue with inner classes)
            if (castToNonNull(ASTHelpers.enclosingClass(symbol)).equals(enclosingClassSymbol)) {
              // make sure the receiver is 'this'
              ExpressionTree receiver = ASTHelpers.getReceiver(expressionTree);
              return receiver == null || isThisIdentifier(receiver);
            }
          }
          return false;
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
    Symbol.ClassSymbol classSymbol = ASTHelpers.getSymbol(tree);
    Set<Symbol> nonnullInstanceFields = new LinkedHashSet<>();
    Set<Symbol> nonnullStaticFields = new LinkedHashSet<>();
    List<BlockTree> instanceInitializerBlocks = new ArrayList<>();
    List<BlockTree> staticInitializerBlocks = new ArrayList<>();
    Set<MethodTree> constructors = new LinkedHashSet<>();
    Set<MethodTree> instanceInitializerMethods = new LinkedHashSet<>();
    Set<MethodTree> staticInitializerMethods = new LinkedHashSet<>();

    // we assume getMembers() returns members in the same order as the declarations
    for (Tree memberTree : tree.getMembers()) {
      if (TreeUtils.isClassTree(memberTree)) {
        // do nothing
        continue;
      }
      switch (memberTree.getKind()) {
        case METHOD:
          // check if it is a constructor or an @Initializer method
          MethodTree methodTree = (MethodTree) memberTree;
          Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(methodTree);
          if (isConstructor(methodTree)) {
            constructors.add(methodTree);
          } else if (isInitializerMethod(state, symbol)) {
            if (symbol.isStatic()) {
              staticInitializerMethods.add(methodTree);
            } else {
              instanceInitializerMethods.add(methodTree);
            }
          }
          break;
        case VARIABLE:
          // field declaration
          VariableTree varTree = (VariableTree) memberTree;
          Symbol fieldSymbol = ASTHelpers.getSymbol(varTree);
          if (fieldSymbol.type.isPrimitive() || skipDueToFieldAnnotation(fieldSymbol)) {
            continue;
          }
          if (varTree.getInitializer() != null) {
            // note that we check that the initializer does the right thing in
            // matchVariable()
            continue;
          }
          if (isStatic(fieldSymbol)) {
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
        default:
          throw new RuntimeException(
              memberTree.getKind().toString() + " " + state.getSourceForNode(memberTree));
      }
    }

    return FieldInitEntities.create(
        classSymbol,
        ImmutableSet.copyOf(nonnullInstanceFields),
        ImmutableSet.copyOf(nonnullStaticFields),
        ImmutableList.copyOf(instanceInitializerBlocks),
        ImmutableList.copyOf(staticInitializerBlocks),
        ImmutableSet.copyOf(constructors),
        ImmutableSet.copyOf(instanceInitializerMethods),
        ImmutableSet.copyOf(staticInitializerMethods));
  }

  private boolean isConstructor(MethodTree methodTree) {
    return ASTHelpers.getSymbol(methodTree).isConstructor()
        && !ASTHelpers.isGeneratedConstructor(methodTree);
  }

  private boolean isInitializerMethod(VisitorState state, Symbol.MethodSymbol symbol) {
    if (ASTHelpers.hasDirectAnnotationWithSimpleName(symbol, "Initializer")
        || config.isKnownInitializerMethod(symbol)) {
      return true;
    }
    for (AnnotationMirror anno : symbol.getAnnotationMirrors()) {
      String annoTypeStr = anno.getAnnotationType().toString();
      if (config.isInitializerMethodAnnotation(annoTypeStr)) {
        return true;
      }
    }
    Symbol.MethodSymbol closestOverriddenMethod =
        NullabilityUtil.getClosestOverriddenMethod(symbol, state.getTypes());
    if (closestOverriddenMethod == null) {
      return false;
    }
    return isInitializerMethod(state, closestOverriddenMethod);
  }

  private boolean skipDueToFieldAnnotation(Symbol fieldSymbol) {
    return NullabilityUtil.getAllAnnotations(fieldSymbol, config)
        .map(anno -> anno.getAnnotationType().toString())
        .anyMatch(config::isExcludedFieldAnnotation);
  }

  // classSymbol must be a top-level class
  private boolean isExcludedClass(Symbol.ClassSymbol classSymbol) {
    String className = classSymbol.getQualifiedName().toString();
    if (config.isExcludedClass(className)) {
      return true;
    }
    if (!codeAnnotationInfo.isClassNullAnnotated(classSymbol, config)) {
      return true;
    }
    // check annotations
    ImmutableSet<String> excludedClassAnnotations = config.getExcludedClassAnnotations();
    return classSymbol.getAnnotationMirrors().stream()
        .map(anno -> anno.getAnnotationType().toString())
        .anyMatch(excludedClassAnnotations::contains);
  }

  private boolean mayBeNullExpr(VisitorState state, ExpressionTree expr) {
    expr = stripParensAndCasts(expr);
    if (ASTHelpers.constValue(expr) != null) {
      // This should include literals such as "true" or a string
      // obviously not null
      return false;
    }
    // return early for expressions that no handler overrides and will not need dataflow analysis
    switch (expr.getKind()) {
      case NULL_LITERAL:
        // obviously null
        return true;
      case ARRAY_ACCESS:
        // unsound!  we cannot check for nullness of array contents yet
      case NEW_CLASS:
      case NEW_ARRAY:
        // for string concatenation, auto-boxing
      case LAMBDA_EXPRESSION:
        // Lambdas may return null, but the lambda literal itself should not be null
      case MEMBER_REFERENCE:
        // These cannot be null; the compiler would catch it
      case MULTIPLY_ASSIGNMENT:
      case DIVIDE_ASSIGNMENT:
      case REMAINDER_ASSIGNMENT:
      case PLUS_ASSIGNMENT:
      case MINUS_ASSIGNMENT:
      case LEFT_SHIFT_ASSIGNMENT:
      case RIGHT_SHIFT_ASSIGNMENT:
      case UNSIGNED_RIGHT_SHIFT_ASSIGNMENT:
      case AND_ASSIGNMENT:
      case XOR_ASSIGNMENT:
      case OR_ASSIGNMENT:
        // result of compound assignment cannot be null
      case PLUS:
        // rest are for auto-boxing
      case MINUS:
      case MULTIPLY:
      case DIVIDE:
      case REMAINDER:
      case CONDITIONAL_AND:
      case CONDITIONAL_OR:
      case BITWISE_COMPLEMENT:
      case LOGICAL_COMPLEMENT:
      case INSTANCE_OF:
      case PREFIX_INCREMENT:
      case PREFIX_DECREMENT:
      case POSTFIX_DECREMENT:
      case POSTFIX_INCREMENT:
      case EQUAL_TO:
      case NOT_EQUAL_TO:
      case GREATER_THAN:
      case GREATER_THAN_EQUAL:
      case LESS_THAN:
      case LESS_THAN_EQUAL:
      case UNARY_MINUS:
      case UNARY_PLUS:
      case AND:
      case OR:
      case XOR:
      case LEFT_SHIFT:
      case RIGHT_SHIFT:
      case UNSIGNED_RIGHT_SHIFT:
        // clearly not null
        return false;
      default:
        break;
    }
    // the logic here is to avoid doing dataflow analysis whenever possible
    Symbol exprSymbol = ASTHelpers.getSymbol(expr);
    boolean exprMayBeNull;
    switch (expr.getKind()) {
      case MEMBER_SELECT:
        if (exprSymbol == null) {
          throw new IllegalStateException(
              "unexpected null symbol for dereference expression " + state.getSourceForNode(expr));
        }
        exprMayBeNull =
            NullabilityUtil.mayBeNullFieldFromType(exprSymbol, config, codeAnnotationInfo);
        break;
      case IDENTIFIER:
        if (exprSymbol == null) {
          throw new IllegalStateException(
              "unexpected null symbol for identifier " + state.getSourceForNode(expr));
        }
        if (exprSymbol.getKind() == ElementKind.FIELD) {
          exprMayBeNull =
              NullabilityUtil.mayBeNullFieldFromType(exprSymbol, config, codeAnnotationInfo);
        } else {
          // rely on dataflow analysis for local variables
          exprMayBeNull = true;
        }
        break;
      case METHOD_INVOCATION:
        if (!(exprSymbol instanceof Symbol.MethodSymbol)) {
          throw new IllegalStateException(
              "unexpected symbol "
                  + exprSymbol
                  + " for method invocation "
                  + state.getSourceForNode(expr));
        }
        exprMayBeNull =
            mayBeNullMethodCall(
                (Symbol.MethodSymbol) exprSymbol, (MethodInvocationTree) expr, state);
        break;
      case CONDITIONAL_EXPRESSION:
      case ASSIGNMENT:
        exprMayBeNull = true;
        break;
      default:
        // match switch expressions by comparing strings, so the code compiles on JDK versions < 12
        if (expr.getKind().name().equals("SWITCH_EXPRESSION")) {
          exprMayBeNull = true;
        } else {
          throw new RuntimeException(
              "whoops, better handle " + expr.getKind() + " " + state.getSourceForNode(expr));
        }
    }
    exprMayBeNull = handler.onOverrideMayBeNullExpr(this, expr, exprSymbol, state, exprMayBeNull);
    return exprMayBeNull && nullnessFromDataflow(state, expr);
  }

  private boolean mayBeNullMethodCall(
      Symbol.MethodSymbol exprSymbol, MethodInvocationTree invocationTree, VisitorState state) {
    if (codeAnnotationInfo.isSymbolUnannotated(exprSymbol, config)) {
      return false;
    }
    if (Nullness.hasNullableAnnotation(exprSymbol, config)) {
      return true;
    }
    if (config.isJSpecifyMode()
        && GenericsChecks.getGenericReturnNullnessAtInvocation(
                exprSymbol, invocationTree, state, config)
            .equals(Nullness.NULLABLE)) {
      return true;
    }
    return false;
  }

  public boolean nullnessFromDataflow(VisitorState state, ExpressionTree expr) {
    Nullness nullness =
        getNullnessAnalysis(state).getNullness(new TreePath(state.getPath(), expr), state.context);
    if (nullness == null) {
      // this may be unsound, like for field initializers
      // figure out if we care
      return false;
    }
    return NullabilityUtil.nullnessToBool(nullness);
  }

  public AccessPathNullnessAnalysis getNullnessAnalysis(VisitorState state) {
    return AccessPathNullnessAnalysis.instance(state, nonAnnotatedMethod, config, this.handler);
  }

  private Description matchDereference(
      ExpressionTree baseExpression, ExpressionTree derefExpression, VisitorState state) {
    Symbol baseExpressionSymbol = ASTHelpers.getSymbol(baseExpression);
    // Note that a null dereference is possible even if baseExpressionSymbol is null,
    // e.g., in cases where baseExpression contains conditional logic (like a ternary
    // expression, or a switch expression in JDK 12+)
    if (baseExpressionSymbol != null) {
      if (baseExpressionSymbol.type.isPrimitive()
          || baseExpressionSymbol.getKind() == ElementKind.PACKAGE
          || ElementUtils.isTypeElement(baseExpressionSymbol)) {
        // we know we don't have a null dereference here
        return Description.NO_MATCH;
      }
    }
    if (mayBeNullExpr(state, baseExpression)) {
      final String message =
          "dereferenced expression " + state.getSourceForNode(baseExpression) + " is @Nullable";
      ErrorMessage errorMessage = new ErrorMessage(MessageTypes.DEREFERENCE_NULLABLE, message);

      return errorBuilder.createErrorDescriptionForNullAssignment(
          errorMessage, baseExpression, buildDescription(derefExpression), state, null);
    }

    Optional<ErrorMessage> handlerErrorMessage =
        handler.onExpressionDereference(derefExpression, baseExpression, state);
    if (handlerErrorMessage.isPresent()) {
      return errorBuilder.createErrorDescriptionForNullAssignment(
          handlerErrorMessage.get(),
          derefExpression,
          buildDescription(derefExpression),
          state,
          null);
    }

    return Description.NO_MATCH;
  }

  private static boolean isThisIdentifier(ExpressionTree expressionTree) {
    return expressionTree.getKind().equals(IDENTIFIER)
        && ((IdentifierTree) expressionTree).getName().toString().equals("this");
  }

  private static boolean isThisIdentifierMatcher(
      ExpressionTree expressionTree, VisitorState state) {
    return isThisIdentifier(expressionTree);
  }

  public ErrorBuilder getErrorBuilder() {
    return errorBuilder;
  }

  /**
   * strip out enclosing parentheses, type casts and Nullchk operators.
   *
   * @param expr a potentially parenthesised expression.
   * @return the same expression without parentheses.
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

      // Strips Nullchk operator
      if (expr.getKind().equals(OTHER) && expr instanceof JCTree.JCUnary) {
        expr = ((JCTree.JCUnary) expr).getExpression();
        someChange = true;
      }
    }
    return expr;
  }

  /**
   * Returns the computed nullness information from an expression. If none is available, it returns
   * Nullable.
   *
   * <p>Computed information can be added by handlers or by the core, and should supersede that
   * comming from annotations.
   *
   * <p>The default value of an expression without additional computed nullness information is
   * always Nullable, since this method should only be called when the fact that the expression is
   * NonNull is not clear from looking at annotations.
   *
   * @param e an expression
   * @return computed nullness for e, if any, else Nullable
   */
  public Nullness getComputedNullness(ExpressionTree e) {
    return computedNullnessMap.getOrDefault(e, Nullness.NULLABLE);
  }

  /**
   * Add computed nullness information to an expression.
   *
   * <p>Used by handlers to communicate that an expression should has a more precise nullness than
   * what is known from source annotations.
   *
   * @param e any expression in the AST.
   * @param nullness the added nullness information.
   */
  public void setComputedNullness(ExpressionTree e, Nullness nullness) {
    computedNullnessMap.put(e, nullness);
  }

  @AutoValue
  abstract static class FieldInitEntities {

    static FieldInitEntities create(
        Symbol.ClassSymbol classSymbol,
        Set<Symbol> nonnullInstanceFields,
        Set<Symbol> nonnullStaticFields,
        List<BlockTree> instanceInitializerBlocks,
        List<BlockTree> staticInitializerBlocks,
        Set<MethodTree> constructors,
        Set<MethodTree> instanceInitializerMethods,
        Set<MethodTree> staticInitializerMethods) {
      return new AutoValue_NullAway_FieldInitEntities(
          classSymbol,
          ImmutableSet.copyOf(nonnullInstanceFields),
          ImmutableSet.copyOf(nonnullStaticFields),
          ImmutableList.copyOf(instanceInitializerBlocks),
          ImmutableList.copyOf(staticInitializerBlocks),
          ImmutableSet.copyOf(constructors),
          ImmutableSet.copyOf(instanceInitializerMethods),
          ImmutableSet.copyOf(staticInitializerMethods));
    }

    /** Returns symbol for class. */
    abstract Symbol.ClassSymbol classSymbol();

    /**
     * Returns <code>@NonNull</code> instance fields that are not directly initialized at
     * declaration.
     */
    abstract ImmutableSet<Symbol> nonnullInstanceFields();

    /**
     * Returns <code>@NonNull</code> static fields that are not directly initialized at declaration.
     */
    abstract ImmutableSet<Symbol> nonnullStaticFields();

    /**
     * Returns the list of instance initializer blocks (e.g. blocks of the form `class X { { //Code
     * } } ), in the order in which they appear in the class.
     */
    abstract ImmutableList<BlockTree> instanceInitializerBlocks();

    /**
     * Returns the list of static initializer blocks (e.g. blocks of the form `class X { static {
     * //Code } } ), in the order in which they appear in the class.
     */
    abstract ImmutableList<BlockTree> staticInitializerBlocks();

    /** Returns constructors in the class. */
    abstract ImmutableSet<MethodTree> constructors();

    /**
     * Returns the list of non-static (instance) initializer methods. This includes methods
     * annotated @Initializer, as well as those specified by -XepOpt:NullAway:KnownInitializers or
     * annotated with annotations passed to -XepOpt:NullAway:CustomInitializerAnnotations.
     */
    abstract ImmutableSet<MethodTree> instanceInitializerMethods();

    /**
     * Returns the list of static initializer methods. This includes static methods
     * annotated @Initializer, as well as those specified by -XepOpt:NullAway:KnownInitializers or
     * annotated with annotations passed to -XepOpt:NullAway:CustomInitializerAnnotations.
     */
    abstract ImmutableSet<MethodTree> staticInitializerMethods();
  }
}
