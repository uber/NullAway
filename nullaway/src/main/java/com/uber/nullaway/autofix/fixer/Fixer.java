package com.uber.nullaway.autofix.fixer;

import com.google.errorprone.VisitorState;
import com.uber.nullaway.Config;
import com.uber.nullaway.ErrorMessage;
import com.uber.nullaway.autofix.AutoFixConfig;
import com.uber.nullaway.autofix.out.Fix;

@SuppressWarnings("ALL")
public class Fixer {

  protected final AutoFixConfig config;

  public Fixer(Config config) {
    this.config = config.getAutoFixConfig();
  }

  public void fix(ErrorMessage errorMessage, Location location, VisitorState state) {
    // todo: remove this condition later, for now we are not supporting anonymous classes
    if (!config.SUGGEST_ENABLED) return;
    if (location.classSymbol.toString().startsWith("<anonymous")) return;
    Fix fix = buildFix(errorMessage, location);
    if (fix != null) {
      if (config.SUGGEST_DEEP) {
        fix.findEnclosing(state, errorMessage);
      }
      config.WRITER.saveFix(fix);
    }
  }

  protected Fix buildFix(ErrorMessage errorMessage, Location location) {
    Fix fix;
    switch (errorMessage.getMessageType()) {
      case RETURN_NULLABLE:
      case WRONG_OVERRIDE_RETURN:
      case WRONG_OVERRIDE_PARAM:
      case PASS_NULLABLE:
      case FIELD_NO_INIT:
      case ASSIGN_FIELD_NULLABLE:
        fix = new Fix();
        fix.location = location;
        fix.annotation = config.ANNOTATION_FACTORY.getNullable();
        fix.inject = true;
        break;
      default:
        return null;
    }
    if (fix != null) {
      fix.errorMessage = errorMessage;
    }
    return fix;
  }

  protected void suggestSuppressWarning(ErrorMessage errorMessage, Location location) {}
}
