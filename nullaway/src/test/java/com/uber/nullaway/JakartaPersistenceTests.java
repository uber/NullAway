package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import org.junit.Test;

@SuppressWarnings("deprecation")
public class JakartaPersistenceTests extends NullAwayTestsBase {

  private CompilationTestHelper addJpaAnnotationStubs(CompilationTestHelper helper) {
    return helper
        .addSourceLines(
            "jakarta/persistence/Access.java",
            """
            package jakarta.persistence;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Access {
              AccessType value();
            }
            """)
        .addSourceLines(
            "jakarta/persistence/AccessType.java",
            """
            package jakarta.persistence;
            public enum AccessType {
              FIELD,
              PROPERTY
            }
            """)
        .addSourceLines(
            "jakarta/persistence/Column.java",
            """
            package jakarta.persistence;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target({ElementType.METHOD, ElementType.FIELD})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Column {}
            """)
        .addSourceLines(
            "jakarta/persistence/Embeddable.java",
            """
            package jakarta.persistence;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Embeddable {}
            """)
        .addSourceLines(
            "jakarta/persistence/Entity.java",
            """
            package jakarta.persistence;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Entity {}
            """)
        .addSourceLines(
            "jakarta/persistence/Id.java",
            """
            package jakarta.persistence;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target({ElementType.METHOD, ElementType.FIELD})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Id {}
            """)
        .addSourceLines(
            "jakarta/persistence/MappedSuperclass.java",
            """
            package jakarta.persistence;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface MappedSuperclass {}
            """)
        .addSourceLines(
            "jakarta/persistence/Transient.java",
            """
            package jakarta.persistence;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target({ElementType.METHOD, ElementType.FIELD})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Transient {}
            """)
        .addSourceLines(
            "javax/persistence/Entity.java",
            """
            package javax.persistence;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target(ElementType.TYPE)
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Entity {}
            """)
        .addSourceLines(
            "javax/persistence/Id.java",
            """
            package javax.persistence;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target({ElementType.METHOD, ElementType.FIELD})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Id {}
            """)
        .addSourceLines(
            "javax/persistence/Transient.java",
            """
            package javax.persistence;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target({ElementType.METHOD, ElementType.FIELD})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Transient {}
            """)
        .addSourceLines(
            "org/springframework/data/annotation/Transient.java",
            """
            package org.springframework.data.annotation;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            @Target({ElementType.METHOD, ElementType.FIELD})
            @Retention(RetentionPolicy.RUNTIME)
            public @interface Transient {}
            """);
  }

  @Test
  public void jakartaPersistenceFieldAccessEntityInitialization() {
    addJpaAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "JpaEntities.java",
            """
            package com.uber;
            import jakarta.persistence.Access;
            import jakarta.persistence.AccessType;
            import jakarta.persistence.Column;
            import jakarta.persistence.Embeddable;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            import jakarta.persistence.MappedSuperclass;
            class JpaEntities {
              @Entity
              static class FieldAccessEntity {
                @Id Long id;
                String name;
              }
              @MappedSuperclass
              static class FieldAccessMappedSuperclass {
                @Id Long id;
                String name;
              }
              @Entity
              static class FieldAccessEntityWithMappedSuperclass
                  extends FieldAccessMappedSuperclass {
                String childName;
              }
              @Embeddable
              static class FieldAccessEmbeddable {
                @Column String street;
                String city;
              }
              @Entity
              static class FieldExplicitAccessEntity {
                @Access(AccessType.FIELD) Long idExplicitAccess;
                // @Access annotation does not apply to this other field
                // BUG: Diagnostic contains: @NonNull field JpaEntities$FieldExplicitAccessEntity.name not initialized
                String name;
              }
            }
            """)
        .addSourceLines(
            "JavaxJpaEntity.java",
            """
            package com.uber;
            import javax.persistence.Entity;
            import javax.persistence.Id;
            @Entity
            class JavaxJpaEntity {
              @Id Long id;
              String name;
            }
            """)
        .doTest();
  }

  @Test
  public void jakartaPersistenceDoesNotSkipNonPersistentFields() {
    addJpaAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "JpaNonPersistentFields.java",
            """
            package com.uber;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            @Entity
            class JpaNonPersistentFields {
              @Id Long id;
              // BUG: Diagnostic contains: @NonNull field javaTransient not initialized
              transient String javaTransient;
              @jakarta.persistence.Transient
              // BUG: Diagnostic contains: @NonNull field jakartaTransient not initialized
              String jakartaTransient;
              @javax.persistence.Transient
              // BUG: Diagnostic contains: @NonNull field javaxTransient not initialized
              String javaxTransient;
              @org.springframework.data.annotation.Transient
              // BUG: Diagnostic contains: @NonNull field springDataTransient not initialized
              String springDataTransient;
              // BUG: Diagnostic contains: @NonNull static field staticField not initialized
              static String staticField;
            }
            """)
        .addSourceLines(
            "UnknownJpaAccess.java",
            """
            package com.uber;
            import jakarta.persistence.Entity;
            @Entity
            class UnknownJpaAccess {
              // BUG: Diagnostic contains: @NonNull field name not initialized
              String name;
            }
            """)
        .doTest();
  }

  @Test
  public void jakartaPersistencePropertyAccessEntityInitialization() {
    addJpaAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "PropertyAccessEntity.java",
            """
            package com.uber;
            import jakarta.persistence.Access;
            import jakarta.persistence.AccessType;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            @Entity
            @Access(AccessType.PROPERTY)
            class PropertyAccessEntity {
              Long id;
              String name;
              // BUG: Diagnostic contains: @NonNull field helper not initialized
              String helper;
              // BUG: Diagnostic contains: @NonNull field derived not initialized
              String derived;
              @Id
              public Long getId() {
                return id;
              }
              public void setId(Long id) {
                this.id = id;
              }
              public String getName() {
                return name;
              }
              public void setName(String name) {
                this.name = name;
              }
              @jakarta.persistence.Transient
              public String getDerived() {
                return derived;
              }
              public void setDerived(String derived) {
                this.derived = derived;
              }
            }
            """)
        .doTest();
  }

  @Test
  public void accessFieldOnClass() {
    addJpaAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import jakarta.persistence.Access;
            import jakarta.persistence.AccessType;
            import jakarta.persistence.Entity;
            @Entity
            @Access(AccessType.FIELD)
            class Test {
              String name;
            }
            """)
        .doTest();
  }

  @Test
  public void mappingOnMethod() {
    addJpaAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            @Entity
            class Test {
              Long id;
              @Id
              public Long getId() {
                return id;
              }
              public void setId(Long id) { this.id = id; }
            }
            """)
        .doTest();
  }

  @Test
  public void mappingOnIsGetterInfersPropertyAccess() {
    addJpaAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            @Entity
            class Test {
              boolean active;
              String name;
              @Id
              public boolean isActive() {
                return active;
              }
              public void setActive(boolean active) { this.active = active; }
              public String getName() {
                return name;
              }
              public void setName(String name) { this.name = name; }
            }
            """)
        .doTest();
  }

  @Test
  public void ignoreStaticGetter() {
    addJpaAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "Test.java",
            """
            package com.uber;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            @Entity
            class Test {
              // BUG: Diagnostic contains: @NonNull field id not initialized
              Long id;
              @Id
              public static Long getId() {
                return Long.valueOf(0);
              }
              public void setId(Long id) { this.id = id; }
            }
            """)
        .doTest();
  }

  @Test
  public void jpaManagedFieldsSkippedOnlyForZeroArgConstructors() {
    addJpaAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "TestEntity.java",
            """
            package com.uber;
            import jakarta.persistence.Entity;
            import jakarta.persistence.Id;
            @Entity
            class TestEntity {
              private String name;
              private String extraInfo;

              public TestEntity() {}

              // BUG: Diagnostic contains: initializer method does not guarantee @NonNull field extraInfo
              public TestEntity(String name) {
                this.name = name;
              }

              @Id
              public String getName() {
                return name;
              }
              public void setName(String name) {
                this.name = name;
              }
              public String getExtraInfo() {
                return extraInfo;
              }
              public void setExtraInfo(String extraInfo) {
                this.extraInfo = extraInfo;
              }
            }
            """)
        .addSourceLines(
            "ZeroArgOnlyEntity.java",
            """
            package com.uber;
            import jakarta.persistence.Access;
            import jakarta.persistence.AccessType;
            import jakarta.persistence.Entity;
            @Entity
            @Access(AccessType.FIELD)
            class ZeroArgOnlyEntity {
              private String name;

              public ZeroArgOnlyEntity() {}
            }
            """)
        .doTest();
  }

  @Test
  public void jpaZeroArgSkipDoesNotSuppressInitializerReadBeforeInit() {
    addJpaAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "JpaInitializerRead.java",
            """
            package com.uber;
            import com.uber.nullaway.annotations.Initializer;
            import jakarta.persistence.Access;
            import jakarta.persistence.AccessType;
            import jakarta.persistence.Entity;
            @Entity
            @Access(AccessType.FIELD)
            class JpaInitializerRead {
              private String name;

              public JpaInitializerRead() {}

              @Initializer
              public void init() {
                // BUG: Diagnostic contains: read of @NonNull field name before initialization
                name.toString();
              }
            }
            """)
        .doTest();
  }

  @Test
  public void lombokGetterSetter() {
    addJpaAnnotationStubs(defaultCompilationHelper)
        .addSourceLines(
            "Super.java",
            """
            package com.uber;
            import jakarta.persistence.*;
            @MappedSuperclass
            @lombok.Getter
            @lombok.Setter
            abstract class Super {
              @Column private Object o1;
              @Id private Object o2;
            }
            """)
        .addSourceLines(
            "Sub.java",
            """
            package com.uber;
            import jakarta.persistence.*;
            @Entity
            @lombok.Getter
            @lombok.Setter
            @lombok.NoArgsConstructor
            class Sub extends Super {
              @Column private Object o3;
              @Id private Object o4;
            }
            """)
        .doTest();
  }
}
