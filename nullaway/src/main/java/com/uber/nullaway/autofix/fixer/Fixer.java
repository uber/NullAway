package com.uber.nullaway.autofix.fixer;

import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.VariableTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.autofix.AutoFixConfig;
import com.uber.nullaway.autofix.out.Fix;
import com.uber.nullaway.autofix.qual.AnnotationFactory;
import java.util.List;
import javax.lang.model.element.Modifier;

@SuppressWarnings("ALL")
public class Fixer {

  protected final AutoFixConfig config;

  public Fixer(Config config) {
    this.config = config.getAutoFixConfig();
  }

  public void fix(ErrorMessage errorMessage, Location location, VisitorState state) {
    // todo: remove this condition later, for now we are not supporting anonymous classes
    if (!config.SUGGEST_ENABLED) return;
    if (ASTHelpers.getSymbol(location.classTree).toString().startsWith("<anonymous")) return;
    Fix fix = buildFix(errorMessage, location);
    if (fix != null) {
      if (config.SUGGEST_DEEP) {
        fix.findEnclosing(state, errorMessage);
      }
      saveFix(fix);
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
      fix.errorMessage = errorMessage;
    }
    return fix;
  }

  protected Fix addFieldNullableFix(Location location) {
    final Fix fix = new Fix();
    fix.location = location;
    Symbol.VarSymbol varSymbol = (Symbol.VarSymbol) location.variableSymbol;
    // skip final properties
    if (varSymbol.getModifiers().contains(Modifier.FINAL)) return null;
    fix.annotation = config.ANNOTATION_FACTORY.getNullable();
    fix.inject = true;
    return fix;
  }

  protected Fix addParamPassNullableFix(Location location) {
    AnnotationFactory.Annotation nonNull = config.ANNOTATION_FACTORY.getNonNull();
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
      fix.annotation = config.ANNOTATION_FACTORY.getNullable();
      fix.inject = true;
      return fix;
    }
    return null;
  }

  protected Fix addParamNullableFix(Location location) {
    final Fix fix = new Fix();
    fix.location = location;
    fix.annotation = config.ANNOTATION_FACTORY.getNullable();
    fix.inject = true;
    return fix;
  }

  protected Fix addReturnNullableFix(Location location) {
    AnnotationFactory.Annotation nonNull = config.ANNOTATION_FACTORY.getNonNull();
    final Fix fix = new Fix();
    final ModifiersTree modifiers = location.methodTree.getModifiers();
    final List<? extends AnnotationTree> annotations = modifiers.getAnnotations();
    com.google.common.base.Optional<? extends AnnotationTree> nonNullAnnot =
        Iterables.tryFind(
            annotations, annot -> annot.getAnnotationType().toString().endsWith(nonNull.name));
    fix.location = location;
    fix.annotation = config.ANNOTATION_FACTORY.getNullable();
    fix.inject = !nonNullAnnot.isPresent();
    return fix;
  }

  protected void suggestSuppressWarning(ErrorMessage errorMessage, Location location) {}
}
