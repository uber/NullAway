package com.uber.nullaway.dataflow.cfg;

import com.google.common.base.Preconditions;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TreeVisitor;
import com.sun.source.util.TreePath;
import com.uber.nullaway.handlers.Handler;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.type.TypeMirror;
import org.checkerframework.nullaway.dataflow.cfg.ControlFlowGraph;
import org.checkerframework.nullaway.dataflow.cfg.UnderlyingAST;
import org.checkerframework.nullaway.dataflow.cfg.builder.CFGBuilder;
import org.checkerframework.nullaway.dataflow.cfg.builder.CFGTranslationPhaseOne;
import org.checkerframework.nullaway.dataflow.cfg.builder.CFGTranslationPhaseThree;
import org.checkerframework.nullaway.dataflow.cfg.builder.CFGTranslationPhaseTwo;
import org.checkerframework.nullaway.dataflow.cfg.builder.ConditionalJump;
import org.checkerframework.nullaway.dataflow.cfg.builder.ExtendedNode;
import org.checkerframework.nullaway.dataflow.cfg.builder.Label;
import org.checkerframework.nullaway.dataflow.cfg.builder.PhaseOneResult;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;
import org.checkerframework.nullaway.dataflow.cfg.node.ThrowNode;
import org.checkerframework.nullaway.javacutil.AnnotationProvider;
import org.checkerframework.nullaway.javacutil.BasicAnnotationProvider;
import org.checkerframework.nullaway.javacutil.trees.TreeBuilder;

/**
 * A NullAway specific CFGBuilder subclass, which allows to more directly control the AST to CFG
 * translation performed by the checker framework.
 *
 * <p>This holds the static method {@link #build(TreePath, UnderlyingAST, boolean, boolean,
 * ProcessingEnvironment, Handler)}, called to perform the CFG translation, and the class {@link
 * NullAwayCFGTranslationPhaseOne}, which extends {@link CFGTranslationPhaseOne} and adds hooks for
 * the NullAway handlers mechanism and some utility methods.
 */
public final class NullAwayCFGBuilder extends CFGBuilder {

  /** This class should never be instantiated. */
  private NullAwayCFGBuilder() {}

  /**
   * This static method produces a new CFG representation given a method's (or lambda/initializer)
   * body.
   *
   * <p>It is analogous to {@link CFGBuilder#build(TreePath, UnderlyingAST, boolean, boolean,
   * ProcessingEnvironment)}, but it also takes a handler to be called at specific extention points
   * during the CFG translation.
   *
   * @param bodyPath the TreePath to the body of the method, lambda, or initializer.
   * @param underlyingAST the AST that underlies the control frow graph
   * @param assumeAssertionsEnabled can assertions be assumed to be disabled?
   * @param assumeAssertionsDisabled can assertions be assumed to be enabled?
   * @param env annotation processing environment containing type utilities
   * @param handler a NullAway handler or chain of handlers (through {@link
   *     com.uber.nullaway.handlers.CompositeHandler}
   * @return a control flow graph
   */
  public static ControlFlowGraph build(
      TreePath bodyPath,
      UnderlyingAST underlyingAST,
      boolean assumeAssertionsEnabled,
      boolean assumeAssertionsDisabled,
      ProcessingEnvironment env,
      Handler handler) {
    TreeBuilder builder = new TreeBuilder(env);
    AnnotationProvider annotationProvider = new BasicAnnotationProvider();
    CFGTranslationPhaseOne phase1translator =
        new NullAwayCFGTranslationPhaseOne(
            builder,
            annotationProvider,
            assumeAssertionsEnabled,
            assumeAssertionsDisabled,
            env,
            handler);
    PhaseOneResult phase1result = phase1translator.process(bodyPath, underlyingAST);
    ControlFlowGraph phase2result = CFGTranslationPhaseTwo.process(phase1result);
    ControlFlowGraph phase3result = CFGTranslationPhaseThree.process(phase2result);
    return phase3result;
  }

  /**
   * A NullAway specific subclass of the Checker Framework's {@link CFGTranslationPhaseOne},
   * augmented with handler extension points and some utility methods meant to be called from
   * handlers to customize the AST to CFG translation.
   */
  public static class NullAwayCFGTranslationPhaseOne extends CFGTranslationPhaseOne {

    private final Handler handler;

    /**
     * Create a new NullAway phase one translation visitor.
     *
     * @param builder a TreeBuilder object (used to create synthetic AST nodes to feed to the
     *     translation process)
     * @param annotationProvider an {@link AnnotationProvider}.
     * @param assumeAssertionsEnabled can assertions be assumed to be disabled?
     * @param assumeAssertionsDisabled can assertions be assumed to be enabled?
     * @param env annotation processing environment containing type utilities
     * @param handler a NullAway handler or chain of handlers (through {@link
     *     com.uber.nullaway.handlers.CompositeHandler}
     */
    public NullAwayCFGTranslationPhaseOne(
        TreeBuilder builder,
        AnnotationProvider annotationProvider,
        boolean assumeAssertionsEnabled,
        boolean assumeAssertionsDisabled,
        ProcessingEnvironment env,
        Handler handler) {
      super(builder, annotationProvider, assumeAssertionsEnabled, assumeAssertionsDisabled, env);
      this.handler = handler;
    }

    /**
     * Obtain the type mirror for a given class, used for exception throwing.
     *
     * <p>We use this method to expose the otherwise protected method {@link #getTypeMirror(Class)}
     * to handlers.
     *
     * @param klass a Java class
     * @return the corresponding type mirror
     */
    public TypeMirror classToErrorType(Class<?> klass) {
      return this.getTypeMirror(klass);
    }

    /**
     * Extend the CFG to throw an exception if the passed expression node evaluates to {@code
     * false}.
     *
     * @param booleanExpressionNode a CFG Node representing a boolean expression.
     * @param errorType the type of the exception to throw if booleanExpressionNode evaluates to
     *     {@code false}.
     */
    public void insertThrowOnFalse(Node booleanExpressionNode, TypeMirror errorType) {
      insertThrowOn(false, booleanExpressionNode, errorType);
    }

    /**
     * Extend the CFG to throw an exception if the passed expression node evaluates to {@code true}.
     *
     * @param booleanExpressionNode a CFG Node representing a boolean expression.
     * @param errorType the type of the exception to throw if booleanExpressionNode evaluates to
     *     {@code true}.
     */
    public void insertThrowOnTrue(Node booleanExpressionNode, TypeMirror errorType) {
      insertThrowOn(true, booleanExpressionNode, errorType);
    }

    private void insertThrowOn(boolean throwOn, Node booleanExpressionNode, TypeMirror errorType) {
      Tree tree = booleanExpressionNode.getTree();
      Preconditions.checkArgument(
          tree instanceof ExpressionTree,
          "Argument booleanExpressionNode must represent a boolean expression");
      ExpressionTree booleanExpressionTree = (ExpressionTree) booleanExpressionNode.getTree();
      Preconditions.checkNotNull(booleanExpressionTree);
      Label preconditionEntry = new Label();
      Label endPrecondition = new Label();
      this.scan(booleanExpressionTree, (Void) null);
      ConditionalJump cjump =
          new ConditionalJump(
              throwOn ? preconditionEntry : endPrecondition,
              throwOn ? endPrecondition : preconditionEntry);
      this.extendWithExtendedNode(cjump);
      this.addLabelForNextNode(preconditionEntry);
      ExtendedNode exNode =
          this.extendWithNodeWithException(
              new ThrowNode(
                  new ThrowTree() {
                    // Dummy throw tree, unused. We could use null here, but that violates nullness
                    // typing.
                    @Override
                    public ExpressionTree getExpression() {
                      return booleanExpressionTree;
                    }

                    @Override
                    public Kind getKind() {
                      return Kind.THROW;
                    }

                    @Override
                    public <R, D> R accept(TreeVisitor<R, D> visitor, D data) {
                      return visitor.visitThrow(this, data);
                    }
                  },
                  booleanExpressionNode,
                  this.env.getTypeUtils()),
              errorType);
      exNode.setTerminatesExecution(true);
      this.addLabelForNextNode(endPrecondition);
    }

    @Override
    public MethodInvocationNode visitMethodInvocation(MethodInvocationTree tree, Void p) {
      MethodInvocationNode originalNode = super.visitMethodInvocation(tree, p);
      return handler.onCFGBuildPhase1AfterVisitMethodInvocation(this, tree, originalNode);
    }
  }
}
