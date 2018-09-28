package com.uber.nullaway.dataflow;

import com.google.common.base.Preconditions;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.util.Context;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.lang.model.element.NestingKind;

/**
 * Stores info on nullness of local variables in enclosing environments, used when performing
 * dataflow analysis on lambdas or methods in anonymous classes that may access these locals
 */
public class EnclosingEnvironmentNullness {

  public static final Context.Key<EnclosingEnvironmentNullness>
      ENCLOSING_ENVIRONMENT_NULLNESS_ANALYSIS_KEY = new Context.Key<>();

  private final Map<Tree, NullnessStore> environmentNullness = new LinkedHashMap<>();

  public static EnclosingEnvironmentNullness instance(Context context) {
    EnclosingEnvironmentNullness instance =
        context.get(ENCLOSING_ENVIRONMENT_NULLNESS_ANALYSIS_KEY);
    if (instance == null) {
      instance = new EnclosingEnvironmentNullness();
      context.put(ENCLOSING_ENVIRONMENT_NULLNESS_ANALYSIS_KEY, instance);
    }
    return instance;
  }

  public void addEnvironmentMapping(Tree t, NullnessStore s) {
    Preconditions.checkArgument(isValidTreeType(t), "cannot store environment for node " + t);
    environmentNullness.put(t, s);
  }

  @Nullable
  public NullnessStore getEnvironmentMapping(Tree t) {
    Preconditions.checkArgument(isValidTreeType(t));
    return environmentNullness.get(t);
  }

  public void clear() {
    environmentNullness.clear();
  }

  /** Is t an anonymous inner class or a lambda? */
  private boolean isValidTreeType(Tree t) {
    if (t instanceof LambdaExpressionTree) {
      return true;
    }
    if (t instanceof ClassTree) {
      NestingKind nestingKind = ASTHelpers.getSymbol((ClassTree) t).getNestingKind();
      return nestingKind.equals(NestingKind.ANONYMOUS) || nestingKind.equals(NestingKind.LOCAL);
    }
    return false;
  }
}
