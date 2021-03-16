package com.uber.nullaway.fixer;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.handlers.AbstractFieldContractHandler;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import javax.lang.model.element.Modifier;

@SuppressWarnings({
  "UnusedVariable",
  "PackageAccessibility",
  "UnusedMethod"
}) // This class is still under construction
public class Fixer {

  protected final Config config;
  protected final Writer writer;
  protected final HashMap<ErrorMessage.MessageTypes, List<Location.Kind>> messageTypeLocationMap;

  public Fixer(Config config) {
    this.config = config;
    this.writer = new Writer(config);
    messageTypeLocationMap = new HashMap<>();
    fillMessageTypeLocationMap();
    cleanFixOutPutFolder();
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private void cleanFixOutPutFolder() {
    File file = new File(config.getJsonFileWriterPath());
    file.delete();
  }

  private void fillMessageTypeLocationMap() {}

  public void fix(ErrorMessage errorMessage, Location location) {
    // todo: remove this condition later, for now we are not supporting anonymous classes
    if (!config.autofixIsEnabled()) return;
    if (ASTHelpers.getSymbol(location.classTree).toString().startsWith("<anonymous")) return;
    Fix fix = buildFix(errorMessage, location);
    if (fix != null) {
      writer.saveFix(fix);
    }
  }

  protected Fix buildFix(ErrorMessage errorMessage, Location location) {
    Fix fix;
    switch (errorMessage.getMessageType()) {
      case RETURN_NULLABLE:
      case WRONG_OVERRIDE_RETURN:
        fix = addReturnNullableFix(location);
        break;
      case WRONG_OVERRIDE_PARAM:
        fix = addParamNullableFix(location);
        break;
      case PASS_NULLABLE:
        fix = addParamPassNullableFix(location);
        break;
      case FIELD_NO_INIT:
      case ASSIGN_FIELD_NULLABLE:
        fix = addFieldNullableFix(location);
        break;
      default:
        suggestSuppressWarning(errorMessage, location);
        return null;
    }
    if (fix != null) {
      fix.reason = errorMessage.getMessageType().toString();
    }
    return fix;
  }

  protected Fix addFieldNullableFix(Location location) {
    final Fix fix = new Fix();
    fix.location = location;
    Symbol.VarSymbol varSymbol = (Symbol.VarSymbol) location.variableSymbol;
    // skip final properties
    if (varSymbol.getModifiers().contains(Modifier.FINAL)) return null;
    fix.annotation = config.getAnnotationFactory().getNullable();
    fix.inject = true;
    return fix;
  }

  protected Fix addParamPassNullableFix(Location location) {
    AnnotationFactory.Annotation nonNull = config.getAnnotationFactory().getNonNull();
    VariableTree variableTree =
        LocationUtils.getVariableTree(
            location.methodTree, (Symbol.VarSymbol) location.variableSymbol);
    if (variableTree != null) {
      final List<? extends AnnotationTree> annotations =
          variableTree.getModifiers().getAnnotations();
      Optional<? extends AnnotationTree> nonNullAnnot =
          Iterables.tryFind(
              annotations, annot -> annot.toString().equals("@" + nonNull.name + "()"));
      if (nonNullAnnot.isPresent()) return null;
      final Fix fix = new Fix();
      fix.location = location;
      fix.annotation = config.getAnnotationFactory().getNullable();
      fix.inject = true;
      return fix;
    }
    return null;
  }

  protected Fix addParamNullableFix(Location location) {
    if (!location.kind.equals(Location.Kind.METHOD_PARAM)) {
      throw new RuntimeException(
          "Incompatible Fix Call: Cannot fix location type: "
              + location.kind.label
              + " with this method: addParamNullableFix");
    }
    final Fix fix = new Fix();
    fix.location = location;
    fix.annotation = config.getAnnotationFactory().getNullable();
    fix.inject = true;
    return fix;
  }

  protected Fix addReturnNullableFix(Location location) {
    AnnotationFactory.Annotation nonNull = config.getAnnotationFactory().getNonNull();

    if (!location.kind.equals(Location.Kind.METHOD_RETURN)) {
      throw new RuntimeException(
          "Incompatible Fix Call: Cannot fix location type: "
              + location.kind.label
              + " with this method: addReturnNullableFix");
    }
    final Fix fix = new Fix();
    final ModifiersTree modifiers = location.methodTree.getModifiers();
    final List<? extends AnnotationTree> annotations = modifiers.getAnnotations();
    // noinspection ConstantConditions
    com.google.common.base.Optional<? extends AnnotationTree> nonNullAnnot =
        Iterables.tryFind(
            annotations, annot -> annot.getAnnotationType().toString().endsWith(nonNull.name));
    fix.location = location;
    fix.annotation = config.getAnnotationFactory().getNullable();
    fix.inject = !nonNullAnnot.isPresent();
    return fix;
  }

  protected void suggestSuppressWarning(ErrorMessage errorMessage, Location location) {}

  public void exploreNullableFieldClass(NullAway nullAway, IfTree tree, VisitorState state) {
    ExpressionTree condition = tree.getCondition();
    if (condition == null) return;
    if (condition instanceof ParenthesizedTree) {
      ExpressionTree conditionExpression = ((ParenthesizedTree) condition).getExpression();
      if (conditionExpression.getKind().equals(Tree.Kind.EQUAL_TO)) {
        BinaryTree binaryTree = (BinaryTree) conditionExpression;
        ExpressionTree forceNullableField = null;
        if (binaryTree.getLeftOperand().getKind().equals(Tree.Kind.NULL_LITERAL)
            && isFieldClass(binaryTree.getRightOperand())) {
          forceNullableField = binaryTree.getRightOperand();
        } else {
          if (binaryTree.getRightOperand().getKind().equals(Tree.Kind.NULL_LITERAL)
              && isFieldClass(binaryTree.getLeftOperand())) {
            forceNullableField = binaryTree.getLeftOperand();
          }
        }
        if (forceNullableField != null) {
          Symbol symbol = ASTHelpers.getSymbol(forceNullableField);
          CompilationUnitTree c =
              Trees.instance(JavacProcessingEnvironment.instance(state.context))
                  .getPath(symbol)
                  .getCompilationUnit();
          Location location =
              Location.Builder()
                  .setCompilationUnitTree(c)
                  .setClassTree(LocationUtils.getClassTree(forceNullableField, state))
                  .setVariableSymbol(symbol)
                  .setKind(Location.Kind.CLASS_FIELD)
                  .build();
          fix(
              new ErrorMessage(ErrorMessage.MessageTypes.FIELD_NO_INIT, "Must be nullable"),
              location);
        }
      }
    }
  }

  private boolean isFieldClass(ExpressionTree expr) {
    Symbol.ClassSymbol classSymbol = ASTHelpers.enclosingClass(ASTHelpers.getSymbol(expr));
    if (expr instanceof IdentifierTree) {
      IdentifierTree identifierTree = (IdentifierTree) expr;
      return AbstractFieldContractHandler.getInstanceFieldOfClass(
              classSymbol, identifierTree.getName().toString())
          != null;
    }
    return false;
  }

  public Writer getWriter() {
    return writer;
  }
}
