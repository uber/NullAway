package com.uber.nullaway.fixer;

import com.sun.source.tree.Tree;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;

public class PreliminaryFixer extends Fixer {
  public PreliminaryFixer(Config config) {
    super(config);
  }

  @Override
  protected Fix buildFix(ErrorMessage errorMessage, Location location, Tree cause) {
    Fix fix;
    if (!(cause.getKind() == Tree.Kind.NULL_LITERAL)) return null;
    switch (errorMessage.getMessageType()) {
      case RETURN_NULLABLE:
        fix = addReturnNullableFix(location, cause);
        break;
      case PASS_NULLABLE:
        fix = addParamPassNullableFix(location, cause);
        break;
      case ASSIGN_FIELD_NULLABLE:
        fix = addFieldNullableFix(location, cause);
        break;
      default:
        return null;
    }
    return fix;
  }
}
