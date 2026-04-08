package com.uber.nullaway.handlers;

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

  /**
   * Returns true when a field annotated with Spring {@code @Value} should be treated as initialized
   * externally.
   */
  @Override
  public boolean shouldSkipFieldInitializationCheck(AnnotationMirror annotationMirror) {
    if (!annotationMirror.getAnnotationType().toString().equals(VALUE_ANNOT)) {
      return false;
    }
    String annotationValue = NullabilityUtil.getAnnotationValue(annotationMirror);
    return annotationValue == null || !containsNullSpELExpression(annotationValue);
  }

  private static boolean containsNullSpELExpression(String annotationValue) {
    return VALUE_NULL_SPEL_PATTERN.matcher(annotationValue).find();
  }
}
