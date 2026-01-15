package com.uber.nullaway.javacplugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A Javac plugin that serializes nullness annotations from Java source files into a JSON file.
 * Primarily intended for serializing annotations from the JSpecify JDK models.
 */
@NullMarked
public class NullnessAnnotationSerializer implements Plugin {

  private static final String NULLMARKED_NAME = "org.jspecify.annotations.NullMarked";
  private static final String NULLUNMARKED_NAME = "org.jspecify.annotations.NullUnmarked";
  private static final String NULLABLE_NAME = "org.jspecify.annotations.Nullable";
  private static final String NONNULL_NAME = "org.jspecify.annotations.NonNull";

  // Data classes for JSON output
  public record TypeParamInfo(String name, List<String> bounds) {}

  public record MethodInfo(
      String returnType,
      String name,
      boolean nullMarked,
      boolean nullUnmarked,
      List<TypeParamInfo> typeParams,
      Map<Integer, Set<NestedAnnotationInfo>> nestedAnnotationsList) {}

  public record ClassInfo(
      String name,
      String type,
      boolean nullMarked,
      boolean nullUnmarked,
      List<TypeParamInfo> typeParams,
      List<MethodInfo> methods) {}

  /** Map from module name to information for classes in that module. */
  private final Map<String, List<ClassInfo>> moduleClasses = new HashMap<>();

  @Override
  public String getName() {
    return "NullnessAnnotationSerializer";
  }

  @Override
  public void init(JavacTask task, String... args) {
    String outputDir = args[0];
    Trees trees = Trees.instance(task);
    task.addTaskListener(
        new com.sun.source.util.TaskListener() {

          @Override
          public void finished(com.sun.source.util.TaskEvent e) {
            if (e.getKind() == com.sun.source.util.TaskEvent.Kind.ANALYZE) {
              CompilationUnitTree cu = e.getCompilationUnit();
              new TreePathScanner<@Nullable Void, @Nullable Void>() {
                /** keep a stack of class contexts to handle nested classes */
                final Deque<ClassInfo> classStack = new ArrayDeque<>();

                @Nullable ClassInfo currentClass = null;

                @Override
                public @Nullable Void visitClass(ClassTree classTree, @Nullable Void unused) {
                  Name simpleName = classTree.getSimpleName();
                  if (simpleName.contentEquals("")) {
                    return null; // skip anonymous
                  }
                  ClassSymbol classSym = (ClassSymbol) trees.getElement(getCurrentPath());
                  @SuppressWarnings("ASTHelpersSuggestions")
                  String moduleName =
                      Objects.requireNonNull(classSym.packge().getEnclosingElement())
                          .getQualifiedName()
                          .toString();
                  if (moduleName.isEmpty()) { // unnamed module
                    moduleName = "unnamed";
                  }
                  if (classSym.getModifiers().contains(Modifier.PRIVATE)) {
                    return null; // skip private classes
                  }
                  TypeMirror classType = trees.getTypeMirror(getCurrentPath());
                  boolean hasNullMarked = hasAnnotation(classSym, NULLMARKED_NAME);
                  boolean hasNullUnmarked = hasAnnotation(classSym, NULLUNMARKED_NAME);
                  if (currentClass != null) {
                    // save current class context
                    classStack.push(currentClass);
                  }
                  boolean currentClassHasAnnotation = hasNullMarked || hasNullUnmarked;
                  // build new class context
                  List<TypeParamInfo> classTypeParams = new ArrayList<>();
                  for (TypeParameterTree tp : classTree.getTypeParameters()) {
                    classTypeParams.add(typeParamInfo(tp));
                    if (typeParamHasAnnotation(tp)) {
                      currentClassHasAnnotation = true;
                    }
                  }
                  List<MethodInfo> classMethods = new ArrayList<>();
                  currentClass =
                      new ClassInfo(
                          simpleName.toString(),
                          classType.toString(),
                          hasNullMarked,
                          hasNullUnmarked,
                          classTypeParams,
                          classMethods);
                  super.visitClass(classTree, null);
                  currentClassHasAnnotation =
                      currentClassHasAnnotation || !currentClass.methods().isEmpty();
                  // only save classes containing jspecify annotations
                  if (currentClassHasAnnotation) {
                    moduleClasses
                        .computeIfAbsent(moduleName, k -> new ArrayList<>())
                        .add(currentClass);
                  }
                  // restore previous class context
                  currentClass = !classStack.isEmpty() ? classStack.pop() : null;
                  return null;
                }

                @Override
                public @Nullable Void visitMethod(MethodTree methodTree, @Nullable Void unused) {
                  MethodSymbol mSym = (MethodSymbol) trees.getElement(getCurrentPath());
                  if (mSym.getModifiers().contains(Modifier.PRIVATE)) {
                    return super.visitMethod(methodTree, null);
                  }
                  boolean methodHasAnnotations = false;
                  Map<Integer, Set<NestedAnnotationInfo>> nestedAnnotationsMap = new HashMap<>();
                  String returnType = "";
                  if (methodTree.getReturnType() != null) {
                    returnType += mSym.getReturnType().toString();
                    if (hasJSpecifyAnnotationDeep(mSym.getReturnType())) {
                      methodHasAnnotations = true;
                      CreateNestedAnnotationInfoVisitor visitor =
                          new CreateNestedAnnotationInfoVisitor();
                      mSym.getReturnType().accept(visitor, null);
                      Set<NestedAnnotationInfo> nested = visitor.getNestedAnnotationInfoSet();
                      if (nested != null && !nested.isEmpty()) {
                        nestedAnnotationsMap.put(-1, nested);
                      }
                    }
                  }
                  boolean hasNullMarked = hasAnnotation(mSym, NULLMARKED_NAME);
                  boolean hasNullUnmarked = hasAnnotation(mSym, NULLUNMARKED_NAME);
                  methodHasAnnotations = methodHasAnnotations || hasNullMarked || hasNullUnmarked;
                  // check each parameter annotations
                  for (int idx = 0; idx < mSym.getParameters().size(); idx++) {
                    Symbol.VarSymbol vSym = mSym.getParameters().get(idx);
                    if (hasJSpecifyAnnotationDeep(vSym.asType())) {
                      methodHasAnnotations = true;
                      CreateNestedAnnotationInfoVisitor visitor =
                          new CreateNestedAnnotationInfoVisitor();
                      vSym.asType().accept(visitor, null);
                      Set<NestedAnnotationInfo> nested = visitor.getNestedAnnotationInfoSet();
                      if (!nested.isEmpty()) {
                        nestedAnnotationsMap.put(idx, nested);
                      }
                    }
                  }
                  List<TypeParamInfo> methodTypeParams = new ArrayList<>();
                  for (TypeParameterTree tp : methodTree.getTypeParameters()) {
                    if (typeParamHasAnnotation(tp)) {
                      methodHasAnnotations = true;
                    }
                    methodTypeParams.add(typeParamInfo(tp));
                  }
                  MethodInfo methodInfo =
                      new MethodInfo(
                          returnType,
                          mSym.toString(),
                          hasNullMarked,
                          hasNullUnmarked,
                          methodTypeParams,
                          nestedAnnotationsMap);
                  // only add this method if it uses JSpecify annotations
                  if (currentClass != null && methodHasAnnotations) {
                    currentClass.methods().add(methodInfo);
                  }
                  return super.visitMethod(methodTree, null);
                }

                private TypeParamInfo typeParamInfo(TypeParameterTree tp) {
                  String name = tp.getName().toString();
                  List<String> bounds = new ArrayList<>();
                  for (var b : tp.getBounds()) {
                    bounds.add(b.toString());
                  }
                  return new TypeParamInfo(name, bounds);
                }

                private boolean hasAnnotation(Symbol sym, String fqn) {
                  return sym.getAnnotationMirrors().stream()
                      .map(AnnotationMirror::getAnnotationType)
                      .map(Object::toString)
                      .anyMatch(fqn::equals);
                }

                private boolean typeParamHasAnnotation(TypeParameterTree tp) {
                  // Get the path to the type parameter relative to the current class path
                  TreePath tpPath = TreePath.getPath(getCurrentPath(), tp);
                  if (tpPath == null) {
                    return false;
                  }
                  // Get the symbol for the type parameter
                  Symbol tpSym = (Symbol) trees.getElement(tpPath);
                  if (tpSym == null) {
                    return false;
                  }
                  TypeVariable tv = (TypeVariable) tpSym.asType();
                  boolean hasAnnotation =
                      hasJSpecifyAnnotationDeep(tv.getUpperBound())
                          || hasJSpecifyAnnotationDeep(tv.getLowerBound());
                  return hasAnnotation || hasJSpecifyAnnotationDeep(tpSym.asType());
                }

                /**
                 * Checks if a list of {@link AnnotationMirror}s contains any JSpecify nullness
                 * annotations ({@code @Nullable} or {@code @NonNull}).
                 *
                 * @param mirrors the list of {@link AnnotationMirror}s to check
                 * @return {@code true} if any JSpecify nullness annotations are present, {@code
                 *     false} otherwise
                 */
                private boolean typeHasJSpecifyAnnotation(
                    List<? extends AnnotationMirror> mirrors) {
                  for (AnnotationMirror am : mirrors) {
                    String fqn = am.getAnnotationType().toString();
                    if (fqn.equals(NULLABLE_NAME) || fqn.equals(NONNULL_NAME)) {
                      return true;
                    }
                  }
                  return false;
                }

                /**
                 * Recursively checks if a {@code TypeMirror} or any of its nested type components
                 * (like array components or bounds) have a JSpecify nullness annotation.
                 *
                 * <p>This method performs a "deep" search, traversing the structure of a given
                 * type. It returns {@code true} as soon as the first JSpecify annotation is found.
                 *
                 * @param type The {@link TypeMirror} to inspect.
                 * @return Returns {@code true} if {@code type} has JSpecify annotations.
                 */
                private boolean hasJSpecifyAnnotationDeep(@Nullable TypeMirror type) {
                  if (type == null) {
                    return false;
                  }
                  if (typeHasJSpecifyAnnotation(type.getAnnotationMirrors())) {
                    return true;
                  }
                  switch (type.getKind()) {
                    case ARRAY -> {
                      return hasJSpecifyAnnotationDeep(
                          ((javax.lang.model.type.ArrayType) type).getComponentType());
                    }
                    case DECLARED -> {
                      for (TypeMirror arg :
                          ((javax.lang.model.type.DeclaredType) type).getTypeArguments()) {
                        if (hasJSpecifyAnnotationDeep(arg)) {
                          return true;
                        }
                      }
                      return false;
                    }
                    case WILDCARD -> {
                      javax.lang.model.type.WildcardType wt =
                          (javax.lang.model.type.WildcardType) type;
                      return hasJSpecifyAnnotationDeep(wt.getExtendsBound())
                          || hasJSpecifyAnnotationDeep(wt.getSuperBound());
                    }
                    case INTERSECTION -> {
                      for (TypeMirror b :
                          ((javax.lang.model.type.IntersectionType) type).getBounds()) {
                        if (hasJSpecifyAnnotationDeep(b)) {
                          return true;
                        }
                      }
                      return false;
                    }
                    default -> {
                      return false;
                    }
                  }
                }
              }.scan(cu, null);
            } else if (e.getKind() == com.sun.source.util.TaskEvent.Kind.COMPILATION) {
              Gson gson = new GsonBuilder().setPrettyPrinting().create();
              String jsonFileName = "classes-" + UUID.randomUUID() + ".json";
              Path p = Paths.get(outputDir, jsonFileName);
              try {
                Files.writeString(p, gson.toJson(moduleClasses));
              } catch (IOException ex) {
                throw new RuntimeException(ex);
              }
            }
          }
        });
  }
}
