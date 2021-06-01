package com.uber.nullaway.autofixer.fixers;

import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.autofixer.results.Fix;

public class PreliminaryFixer extends Fixer {
  public PreliminaryFixer(Config config) {
    super(config);
  }

  @Override
  protected Fix buildFix(ErrorMessage errorMessage, Location location) {
    Fix fix;
    switch (errorMessage.getMessageType()) {
      case RETURN_NULLABLE:
        fix = addReturnNullableFix(location);
        break;
      case PASS_NULLABLE:
        fix = addParamPassNullableFix(location);
        break;
      case ASSIGN_FIELD_NULLABLE:
        fix = addFieldNullableFix(location);
        break;
      default:
        return null;
    }
    return fix;
  }
}
