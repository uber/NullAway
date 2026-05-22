package com.uber.nullaway.handlers;

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullabilityUtil;
import java.util.regex.Pattern;
import javax.lang.model.element.AnnotationMirror;

/** Handler for constructs from the Spring framework */
public class SpringHandler implements Handler {

  static final String VALUE_ANNOT = "org.springframework.beans.factory.annotation.Value";

  /**
   * Matches a SpEL fragment like {@code #{...}} when it contains {@code null} as a standalone
   * token. This lets us distinguish Spring {@code @Value} expressions that may produce {@code null}
   * from plain property placeholders or string literals containing the letters {@code null}. This
   * is a heuristic match and may have false positives.
   */
  private static final Pattern VALUE_NULL_SPEL_PATTERN =
      Pattern.compile("#\\{[^}]*\\bnull\\b[^}]*}");

  @Override
  public boolean shouldSkipFieldInitializationCheck(
      Symbol.ClassSymbol classSymbol, Symbol fieldSymbol, VisitorState state) {
    return shouldSkipSpringValueFieldInitializationCheck(fieldSymbol);
  }

  private static boolean containsNullSpELExpression(String annotationValue) {
    return VALUE_NULL_SPEL_PATTERN.matcher(annotationValue).find();
  }

  private static boolean shouldSkipSpringValueFieldInitializationCheck(Symbol fieldSymbol) {
    for (AnnotationMirror annotationMirror : fieldSymbol.getAnnotationMirrors()) {
      if (annotationMirror.getAnnotationType().toString().equals(VALUE_ANNOT)) {
        String annotationValue = NullabilityUtil.getAnnotationValue(annotationMirror);
        return annotationValue == null || !containsNullSpELExpression(annotationValue);
      }
    }
    return false;
  }
}
