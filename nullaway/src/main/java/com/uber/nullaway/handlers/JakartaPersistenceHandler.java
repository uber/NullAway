package com.uber.nullaway.handlers;

import static com.uber.nullaway.NullabilityUtil.hasAnyAnnotationMatching;

import com.google.errorprone.VisitorState;
import com.sun.source.tree.ClassTree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.NullabilityUtil;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import org.jspecify.annotations.Nullable;

/**
 * Handler for Jakarta Persistence and JPA constructs, to reason about which fields of a class do
 * not require explicit initialization.
 *
 * @see <a
 *     href="https://jakarta.ee/learn/docs/jakartaee-tutorial/current/persist/persistence-intro/persistence-intro.html">Jakarta
 *     Persistence documentation</a>.
 */
public class JakartaPersistenceHandler implements Handler {

  /** Type annotations that mark a class as managed by JPA or Jakarta Persistence. */
  private static final Set<String> JPA_MANAGED_TYPE_ANNOTS =
      Set.of(
          "javax.persistence.Entity",
          "javax.persistence.MappedSuperclass",
          "javax.persistence.Embeddable",
          "jakarta.persistence.Entity",
          "jakarta.persistence.MappedSuperclass",
          "jakarta.persistence.Embeddable");

  /** Field or accessor annotations that exclude a member from persistence handling. */
  private static final Set<String> JPA_TRANSIENT_ANNOTS =
      Set.of(
          "javax.persistence.Transient",
          "jakarta.persistence.Transient",
          "org.springframework.data.annotation.Transient");

  /** Annotations that explicitly select field-based or property-based persistence access. */
  private static final Set<String> JPA_ACCESS_ANNOTS =
      Set.of("javax.persistence.Access", "jakarta.persistence.Access");

  /*
   * JPA defaults an entity hierarchy to field access or property access based on where the mapping
   * annotations are placed. We use this set to infer that access strategy when there is no
   * explicit @Access annotation.
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

  /** Cache of the discovered access strategy for classes. */
  private final Map<Symbol.ClassSymbol, JpaAccess> jpaAccessCache = new HashMap<>();

  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    jpaAccessCache.clear();
  }

  /**
   * Detects whether a field is handled by JPA persistence, in which case we can skip the field
   * initialization check.
   */
  @Override
  public boolean shouldSkipFieldInitializationCheck(
      Symbol.ClassSymbol classSymbol, Symbol fieldSymbol, VisitorState state) {
    // There must be an appropriate annotation on the enclosing class, and the field must be
    // eligible for persistence
    if (!hasAnyAnnotationMatching(classSymbol, JPA_MANAGED_TYPE_ANNOTS::contains)
        || !isJpaFieldEligibleForPersistence(fieldSymbol)) {
      return false;
    }
    // if the field itself has an @Access(FIELD) annotation, it is managed
    if (hasJpaAccessAnnotation(fieldSymbol, "FIELD")) {
      return true;
    }
    // otherwise, determine the access type for the enclosing class, and proceed appropriately
    return switch (getJpaAccess(classSymbol)) {
      case FIELD -> true;
      case PROPERTY -> isBackingFieldForPersistentProperty(classSymbol, fieldSymbol);
      case UNKNOWN -> false;
    };
  }

  /** Returns false for static or transient fields, which are not managed by JPA persistence */
  private static boolean isJpaFieldEligibleForPersistence(Symbol fieldSymbol) {
    Set<Modifier> modifiers = fieldSymbol.getModifiers();
    return !modifiers.contains(Modifier.STATIC)
        && !modifiers.contains(Modifier.TRANSIENT)
        && !hasAnyAnnotationMatching(fieldSymbol, JPA_TRANSIENT_ANNOTS::contains);
  }

  private JpaAccess getJpaAccess(Symbol.ClassSymbol classSymbol) {
    return jpaAccessCache.computeIfAbsent(classSymbol, JakartaPersistenceHandler::computeJpaAccess);
  }

  private static JpaAccess computeJpaAccess(Symbol.ClassSymbol classSymbol) {
    boolean hasFieldMapping = false;
    boolean hasPropertyMapping = false;
    Symbol.ClassSymbol currentClass = classSymbol;
    // we traverse the class hierarchy
    while (currentClass != null
        && hasAnyAnnotationMatching(currentClass, JPA_MANAGED_TYPE_ANNOTS::contains)) {
      // if we see an explicit @Access annotation on a class, that is the final answer
      if (hasJpaAccessAnnotation(currentClass, "FIELD")) {
        return JpaAccess.FIELD;
      }
      if (hasJpaAccessAnnotation(currentClass, "PROPERTY")) {
        return JpaAccess.PROPERTY;
      }
      // otherwise, see if we observe a JPA mapping annotation on either a field or a getter method
      for (Symbol member : currentClass.members().getSymbols()) {
        if (member instanceof Symbol.VarSymbol fieldSymbol
            && isJpaFieldEligibleForPersistence(fieldSymbol)
            && hasAnyAnnotationMatching(fieldSymbol, JPA_MAPPING_ANNOTS::contains)) {
          hasFieldMapping = true;
        } else if (member instanceof Symbol.MethodSymbol methodSymbol
            && isGetter(methodSymbol)
            && hasAnyAnnotationMatching(methodSymbol, JPA_MAPPING_ANNOTS::contains)) {
          hasPropertyMapping = true;
        }
      }
      currentClass = superclassSymbol(currentClass);
    }
    // If we observed both styles or neither style in the managed hierarchy, conservatively answer
    // UNKNOWN
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

  /**
   * For a field to be a backing field for a persistent property, there must be a corresponding
   * getter and setter, and the getter should not be marked as transient
   */
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
    return getter != null
        && hasSetter
        && !hasAnyAnnotationMatching(getter, JPA_TRANSIENT_ANNOTS::contains);
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
    if (methodName.startsWith("get") && methodName.length() > 3) {
      String propertyName = methodName.substring(3);
      return decapitalize(propertyName);
    }
    return null;
  }

  private static String capitalize(String name) {
    return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
  }

  /** de-capitalize according to Java Beans rules for property names */
  private static String decapitalize(String name) {
    if (name.length() > 1
        && Character.isUpperCase(name.charAt(0))
        && Character.isUpperCase(name.charAt(1))) {
      return name;
    }
    return name.substring(0, 1).toLowerCase(Locale.ROOT) + name.substring(1);
  }

  private static boolean hasJpaAccessAnnotation(Symbol symbol, String accessType) {
    for (AnnotationMirror annotationMirror : symbol.getAnnotationMirrors()) {
      if (JPA_ACCESS_ANNOTS.contains(annotationMirror.getAnnotationType().toString())) {
        String value = getAnnotationValue(annotationMirror);
        return value != null && (value.equals(accessType) || value.endsWith("." + accessType));
      }
    }
    return false;
  }

  /**
   * NOTE: we cannot use {@link NullabilityUtil#getAnnotationValue(AnnotationMirror)} since that
   * method requires the element value to be a string. This method allows for any value type and
   * converts it to a string.
   */
  private static @Nullable String getAnnotationValue(AnnotationMirror annot) {
    Map<? extends ExecutableElement, ? extends AnnotationValue> elementValues =
        annot.getElementValues();
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry :
        elementValues.entrySet()) {
      ExecutableElement elem = entry.getKey();
      if (elem.getSimpleName().contentEquals("value")) {
        Object value = entry.getValue().getValue();
        return value.toString();
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
