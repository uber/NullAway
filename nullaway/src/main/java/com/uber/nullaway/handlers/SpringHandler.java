package com.uber.nullaway.handlers;

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullabilityUtil;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import org.jspecify.annotations.Nullable;

/** Handler for constructs from the Spring framework */
public class SpringHandler implements Handler {

  static final String VALUE_ANNOT = "org.springframework.beans.factory.annotation.Value";
  private static final Set<String> JPA_MANAGED_TYPE_ANNOTS =
      Set.of(
          "javax.persistence.Entity",
          "javax.persistence.MappedSuperclass",
          "javax.persistence.Embeddable",
          "jakarta.persistence.Entity",
          "jakarta.persistence.MappedSuperclass",
          "jakarta.persistence.Embeddable");
  private static final Set<String> JPA_TRANSIENT_ANNOTS =
      Set.of(
          "javax.persistence.Transient",
          "jakarta.persistence.Transient",
          "org.springframework.data.annotation.Transient");
  private static final Set<String> JPA_ACCESS_ANNOTS =
      Set.of("javax.persistence.Access", "jakarta.persistence.Access");

  /*
   * JPA defaults an entity hierarchy to field access or property access based on where the mapping
   * annotations are placed. We use this set only to infer that access strategy when there is no
   * explicit @Access annotation. Once access is known, unannotated fields/properties can still be
   * persistent by default.
   */
  private static final Set<String> JPA_MAPPING_ANNOTS =
      Set.of(
          "javax.persistence.AssociationOverride",
          "javax.persistence.AssociationOverrides",
          "javax.persistence.AttributeOverride",
          "javax.persistence.AttributeOverrides",
          "javax.persistence.Basic",
          "javax.persistence.CollectionTable",
          "javax.persistence.Column",
          "javax.persistence.ElementCollection",
          "javax.persistence.Embedded",
          "javax.persistence.EmbeddedId",
          "javax.persistence.Enumerated",
          "javax.persistence.GeneratedValue",
          "javax.persistence.Id",
          "javax.persistence.JoinColumn",
          "javax.persistence.JoinColumns",
          "javax.persistence.JoinTable",
          "javax.persistence.Lob",
          "javax.persistence.ManyToMany",
          "javax.persistence.ManyToOne",
          "javax.persistence.MapKey",
          "javax.persistence.MapKeyColumn",
          "javax.persistence.MapKeyEnumerated",
          "javax.persistence.MapKeyJoinColumn",
          "javax.persistence.MapKeyJoinColumns",
          "javax.persistence.MapKeyTemporal",
          "javax.persistence.OneToMany",
          "javax.persistence.OneToOne",
          "javax.persistence.OrderBy",
          "javax.persistence.OrderColumn",
          "javax.persistence.Temporal",
          "javax.persistence.Version",
          "jakarta.persistence.AssociationOverride",
          "jakarta.persistence.AssociationOverrides",
          "jakarta.persistence.AttributeOverride",
          "jakarta.persistence.AttributeOverrides",
          "jakarta.persistence.Basic",
          "jakarta.persistence.CollectionTable",
          "jakarta.persistence.Column",
          "jakarta.persistence.ElementCollection",
          "jakarta.persistence.Embedded",
          "jakarta.persistence.EmbeddedId",
          "jakarta.persistence.Enumerated",
          "jakarta.persistence.GeneratedValue",
          "jakarta.persistence.Id",
          "jakarta.persistence.JoinColumn",
          "jakarta.persistence.JoinColumns",
          "jakarta.persistence.JoinTable",
          "jakarta.persistence.Lob",
          "jakarta.persistence.ManyToMany",
          "jakarta.persistence.ManyToOne",
          "jakarta.persistence.MapKey",
          "jakarta.persistence.MapKeyColumn",
          "jakarta.persistence.MapKeyEnumerated",
          "jakarta.persistence.MapKeyJoinColumn",
          "jakarta.persistence.MapKeyJoinColumns",
          "jakarta.persistence.MapKeyTemporal",
          "jakarta.persistence.OneToMany",
          "jakarta.persistence.OneToOne",
          "jakarta.persistence.OrderBy",
          "jakarta.persistence.OrderColumn",
          "jakarta.persistence.Temporal",
          "jakarta.persistence.Version");

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
    if (shouldSkipSpringValueFieldInitializationCheck(fieldSymbol)) {
      return true;
    }
    if (!hasAnnotation(classSymbol, JPA_MANAGED_TYPE_ANNOTS)
        || !isJpaFieldEligibleForExternalInitialization(fieldSymbol)) {
      return false;
    }
    if (hasAnnotation(fieldSymbol, JPA_ACCESS_ANNOTS)) {
      return hasJpaAccess(fieldSymbol, "FIELD");
    }
    return switch (getJpaAccess(classSymbol)) {
      case FIELD -> true;
      case PROPERTY -> isBackingFieldForPersistentProperty(classSymbol, fieldSymbol);
      case UNKNOWN -> false;
    };
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

  private static boolean isJpaFieldEligibleForExternalInitialization(Symbol fieldSymbol) {
    Set<Modifier> modifiers = fieldSymbol.getModifiers();
    return !modifiers.contains(Modifier.STATIC)
        && !modifiers.contains(Modifier.TRANSIENT)
        && !hasAnnotation(fieldSymbol, JPA_TRANSIENT_ANNOTS);
  }

  private static JpaAccess getJpaAccess(Symbol.ClassSymbol classSymbol) {
    if (hasJpaAccess(classSymbol, "FIELD")) {
      return JpaAccess.FIELD;
    }
    if (hasJpaAccess(classSymbol, "PROPERTY")) {
      return JpaAccess.PROPERTY;
    }
    boolean hasFieldMapping = false;
    boolean hasPropertyMapping = false;
    Symbol.ClassSymbol currentClass = classSymbol;
    while (currentClass != null && hasAnnotation(currentClass, JPA_MANAGED_TYPE_ANNOTS)) {
      // Access can be inherited through a JPA-managed superclass.
      if (hasJpaAccess(currentClass, "FIELD")) {
        return JpaAccess.FIELD;
      }
      if (hasJpaAccess(currentClass, "PROPERTY")) {
        return JpaAccess.PROPERTY;
      }
      for (Symbol member : currentClass.members().getSymbols()) {
        if (member instanceof Symbol.VarSymbol varSymbol
            && !varSymbol.getModifiers().contains(Modifier.STATIC)
            && hasAnnotation(varSymbol, JPA_MAPPING_ANNOTS)) {
          hasFieldMapping = true;
        } else if (member instanceof Symbol.MethodSymbol methodSymbol
            && isGetter(methodSymbol)
            && hasAnnotation(methodSymbol, JPA_MAPPING_ANNOTS)) {
          hasPropertyMapping = true;
        }
      }
      currentClass = superclassSymbol(currentClass);
    }
    // If both styles, or neither style, appear in the managed hierarchy, stay conservative.
    if (hasFieldMapping == hasPropertyMapping) {
      return JpaAccess.UNKNOWN;
    }
    return hasFieldMapping ? JpaAccess.FIELD : JpaAccess.PROPERTY;
  }

  private static Symbol.@Nullable ClassSymbol superclassSymbol(Symbol.ClassSymbol classSymbol) {
    Symbol superclassSymbol = classSymbol.getSuperclass().tsym;
    return superclassSymbol instanceof Symbol.ClassSymbol superclassClassSymbol
        ? superclassClassSymbol
        : null;
  }

  private static boolean isBackingFieldForPersistentProperty(
      Symbol.ClassSymbol classSymbol, Symbol fieldSymbol) {
    String propertyName = fieldSymbol.getSimpleName().toString();
    Symbol.MethodSymbol getter = null;
    boolean hasSetter = false;
    for (Symbol member : classSymbol.members().getSymbols()) {
      if (!(member instanceof Symbol.MethodSymbol methodSymbol)
          || methodSymbol.getModifiers().contains(Modifier.STATIC)) {
        continue;
      }
      if (propertyName.equals(propertyNameForGetter(methodSymbol))) {
        getter = methodSymbol;
      } else if (isSetterForProperty(methodSymbol, propertyName)) {
        hasSetter = true;
      }
    }
    return getter != null && hasSetter && !hasAnnotation(getter, JPA_TRANSIENT_ANNOTS);
  }

  private static boolean isSetterForProperty(
      Symbol.MethodSymbol methodSymbol, String propertyName) {
    String methodName = methodSymbol.getSimpleName().toString();
    return methodName.equals("set" + capitalize(propertyName)) && methodSymbol.params().size() == 1;
  }

  private static boolean isGetter(Symbol.MethodSymbol methodSymbol) {
    return propertyNameForGetter(methodSymbol) != null;
  }

  private static @Nullable String propertyNameForGetter(Symbol.MethodSymbol methodSymbol) {
    if (!methodSymbol.params().isEmpty()) {
      return null;
    }
    String methodName = methodSymbol.getSimpleName().toString();
    String propertyName = null;
    if (methodName.startsWith("get") && methodName.length() > 3) {
      propertyName = methodName.substring(3);
    } else if (methodName.startsWith("is") && methodName.length() > 2) {
      propertyName = methodName.substring(2);
    }
    return propertyName == null ? null : decapitalize(propertyName);
  }

  private static String capitalize(String name) {
    if (name.isEmpty()) {
      return name;
    }
    return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
  }

  private static String decapitalize(String name) {
    if (name.isEmpty()) {
      return name;
    }
    if (name.length() > 1
        && Character.isUpperCase(name.charAt(0))
        && Character.isUpperCase(name.charAt(1))) {
      return name;
    }
    return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
  }

  private static boolean hasJpaAccess(Symbol symbol, String accessType) {
    for (AnnotationMirror annotationMirror : symbol.getAnnotationMirrors()) {
      if (JPA_ACCESS_ANNOTS.contains(annotationMirror.getAnnotationType().toString())) {
        String value = getAnnotationValueString(annotationMirror);
        return value != null && (value.equals(accessType) || value.endsWith("." + accessType));
      }
    }
    return false;
  }

  private static boolean hasAnnotation(Symbol symbol, Set<String> annotationNames) {
    return symbol.getAnnotationMirrors().stream()
        .map(annotationMirror -> annotationMirror.getAnnotationType().toString())
        .anyMatch(annotationNames::contains);
  }

  private static @Nullable String getAnnotationValueString(AnnotationMirror annotationMirror) {
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        annotationMirror.getElementValues().entrySet()) {
      if (entry.getKey().getSimpleName().contentEquals("value")) {
        return entry.getValue().getValue().toString();
      }
    }
    return null;
  }

  private enum JpaAccess {
    FIELD,
    PROPERTY,
    UNKNOWN
  }
}
