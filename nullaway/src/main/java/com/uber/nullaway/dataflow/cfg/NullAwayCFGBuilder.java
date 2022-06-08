package com.uber.nullaway.dataflow.cfg;

import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ThrowTree;
import com.sun.source.tree.TreeVisitor;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.handlers.Handler;
import javax.annotation.Nullable;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;
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
import org.checkerframework.nullaway.dataflow.cfg.node.ThrowNode;
import org.checkerframework.nullaway.dataflow.cfg.node.VariableDeclarationNode;
import org.checkerframework.nullaway.javacutil.AnnotationProvider;
import org.checkerframework.nullaway.javacutil.BasicAnnotationProvider;
import org.checkerframework.nullaway.javacutil.trees.TreeBuilder;

public class NullAwayCFGBuilder extends CFGBuilder {

  /** This class should never be instantiated. */
  private NullAwayCFGBuilder() {}

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
    if (bodyPath
        .getCompilationUnit()
        .getSourceFile()
        .getName()
        .contains("NullAwayPreconditionTest")) {
      System.err.println("Phase 1:");
      System.err.println("============");
      System.err.println(phase1result.toString());
      System.err.println("============");
    }
    ControlFlowGraph phase2result = CFGTranslationPhaseTwo.process(phase1result);
    ControlFlowGraph phase3result = CFGTranslationPhaseThree.process(phase2result);
    if (bodyPath
        .getCompilationUnit()
        .getSourceFile()
        .getName()
        .contains("NullAwayPreconditionTest")) {
      System.err.println("Phase 3:");
      System.err.println("============");
      System.err.println(phase3result.toString());
      System.err.println("============");
    }
    return phase3result;
  }

  private static class NullAwayCFGTranslationPhaseOne extends CFGTranslationPhaseOne {

    private static final String PRECONDITIONS_CLASS_NAME = "com.google.common.base.Preconditions";
    private static final String CHECK_ARGUMENT_METHOD_NAME = "checkArgument";

    @Nullable private Name preconditionsClass;
    @Nullable private Name checkArgumentMethod;

    final TypeMirror preconditionErrorType;

    @SuppressWarnings("UnusedVariable")
    private final Handler handler;

    public NullAwayCFGTranslationPhaseOne(
        TreeBuilder builder,
        AnnotationProvider annotationProvider,
        boolean assumeAssertionsEnabled,
        boolean assumeAssertionsDisabled,
        ProcessingEnvironment env,
        Handler handler) {
      super(builder, annotationProvider, assumeAssertionsEnabled, assumeAssertionsDisabled, env);
      this.handler = handler;
      this.preconditionErrorType = this.getTypeMirror(IllegalArgumentException.class);
    }

    @SuppressWarnings("NullAway") // (Void)null issue
    @Override
    public MethodInvocationNode visitMethodInvocation(MethodInvocationTree tree, Void p) {
      // Add nodes before
      // ToDo: Move to handler call
      Types types = this.getProcessingEnvironment().getTypeUtils();
      Symbol.MethodSymbol callee = ASTHelpers.getSymbol(tree);
      if (preconditionsClass == null) {
        preconditionsClass = callee.name.table.fromString(PRECONDITIONS_CLASS_NAME);
        checkArgumentMethod = callee.name.table.fromString(CHECK_ARGUMENT_METHOD_NAME);
      }
      MethodInvocationTree processedTree = tree;
      if (callee.enclClass().getQualifiedName().equals(preconditionsClass)
          && callee.name.equals(checkArgumentMethod)
          && callee.getParameters().size() > 0) {
        String localName = uniqueName("preconditionCheck");
        Element owner = findOwner();
        VariableTree condExprVarTree =
            this.treeBuilder.buildVariableDecl(
                types.getPrimitiveType(TypeKind.BOOLEAN),
                localName,
                owner,
                tree.getArguments().get(0));
        VariableDeclarationNode condExprVarNode = new VariableDeclarationNode(condExprVarTree);
        condExprVarNode.setInSource(false);
        extendWithNode(condExprVarNode);
        ExpressionTree[] arguments = tree.getArguments().toArray(new ExpressionTree[0]);
        // Replace expression with stored temporary
        arguments[0] = this.treeBuilder.buildVariableUse(condExprVarTree);
        processedTree = this.treeBuilder.buildMethodInvocation(tree.getMethodSelect(), arguments);
      }
      // ToDo: End of handler call
      MethodInvocationNode originalNode = super.visitMethodInvocation(processedTree, p);
      // Add nodes after
      if (callee.enclClass().getQualifiedName().equals(preconditionsClass)
          && callee.name.equals(checkArgumentMethod)
          && callee.getParameters().size() > 0) {
        Label falsePreconditionEntry = new Label();
        Label endPrecondition = new Label();
        this.scan(tree.getArguments().get(0), (Void) null);
        ConditionalJump cjump = new ConditionalJump(endPrecondition, falsePreconditionEntry);
        this.extendWithExtendedNode(cjump);
        this.addLabelForNextNode(falsePreconditionEntry);
        ExtendedNode exNode =
            this.extendWithNodeWithException(
                new ThrowNode(
                    new ThrowTree() {
                      @Override
                      public ExpressionTree getExpression() {
                        return tree.getArguments().get(0);
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
                    originalNode,
                    this.getProcessingEnvironment().getTypeUtils()),
                this.preconditionErrorType);
        exNode.setTerminatesExecution(true);
        this.addLabelForNextNode(endPrecondition);
      }
      // ToDo: End of handler call
      return originalNode;
    }
  }
}
