package com.uber.nullaway.handlers;

import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullabilityUtil;
import java.util.regex.Pattern;
import javax.lang.model.element.AnnotationMirror;

/** Handler for constructs from the Spring framework */
public class SpringHandler implements Handler {

  static final String VALUE_ANNOT = "org.springframework.beans.factory.annotation.Value";

  private static final Supplier<Type> VALUE_TYPE_SUPPLIER = Suppliers.typeFromString(VALUE_ANNOT);

  /**
   * Matches a SpEL fragment like {@code #{...}} when it contains {@code null} as a standalone
   * token. This lets us distinguish Spring {@code @Value} expressions that may produce {@code null}
   * from plain property placeholders or string literals containing the letters {@code null}.
   */
  private static final Pattern VALUE_NULL_SPEL_PATTERN =
      Pattern.compile("#\\{[^}]*\\bnull\\b[^}]*}");

  /**
   * Matches {@code null} used as an operand in equality comparisons ({@code == null}, {@code !=
   * null}, {@code null ==}, {@code null !=}). These occurrences do not produce a {@code null} value
   * and should be excluded from the SpEL null detection heuristic.
   */
  private static final Pattern NULL_COMPARISON_PATTERN =
      Pattern.compile("[!=]=\\s*\\bnull\\b|\\bnull\\b\\s*[!=]=");

  @Override
  public FieldSkipResult shouldSkipFieldInitializationCheck(
      Symbol.ClassSymbol classSymbol, Symbol fieldSymbol, VisitorState state) {
    for (AnnotationMirror annotationMirror : fieldSymbol.getAnnotationMirrors()) {
      if (ASTHelpers.isSameType(
          (Type) annotationMirror.getAnnotationType(), VALUE_TYPE_SUPPLIER.get(state), state)) {
        String annotationValue = NullabilityUtil.getAnnotationValue(annotationMirror);
        // We return FieldSkipResult.YES here when there is an appropriate @Value annotation, since
        // Spring framework initialization can also invoke constructors that have arguments and then
        // initialize other fields
        return annotationValue == null || !containsNullSpELExpression(annotationValue)
            ? FieldSkipResult.YES
            : FieldSkipResult.NO;
      }
    }
    return FieldSkipResult.NO;
  }

  private static boolean containsNullSpELExpression(String annotationValue) {
    if (!VALUE_NULL_SPEL_PATTERN.matcher(annotationValue).find()) {
      return false;
    }
    // Strip null occurrences that are only used in equality comparisons (e.g., != null, == null)
    // and re-check whether any standalone null token remains as a potential return value.
    String withoutComparisons = NULL_COMPARISON_PATTERN.matcher(annotationValue).replaceAll("");
    return VALUE_NULL_SPEL_PATTERN.matcher(withoutComparisons).find();
  }
}
