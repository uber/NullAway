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
import java.util.UUID;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.type.TypeMirror;

/**
 * A Javac plugin that serializes nullness annotations from Java source files into a JSON file.
 * Primarily intended for serializing annotations from the JSpecify JDK models.
 */
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
      List<TypeParamInfo> typeParams) {}

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
              new TreePathScanner<Void, Void>() {
                /** keep a stack of class contexts to handle nested classes */
                Deque<ClassInfo> classStack = new ArrayDeque<>();

                ClassInfo currentClass = null;
                boolean currentClassHasAnnotation = false;

                @Override
                public Void visitClass(ClassTree classTree, Void unused) {
                  Name simpleName = classTree.getSimpleName();
                  if (simpleName.contentEquals("")) {
                    return null; // skip anonymous
                  }
                  ClassSymbol classSym = (ClassSymbol) trees.getElement(getCurrentPath());
                  @SuppressWarnings("ASTHelpersSuggestions")
                  String moduleName =
                      classSym.packge().getEnclosingElement().getQualifiedName().toString();
                  if (moduleName.isEmpty()) { // unnamed module
                    moduleName = "unnamed";
                  }
                  if (classSym.getModifiers().contains(Modifier.PRIVATE)) {
                    return null; // skip private classes
                  }
                  TypeMirror classType = trees.getTypeMirror(getCurrentPath());
                  boolean hasNullMarked = hasAnnotation(classSym, NULLMARKED_NAME);
                  boolean hasNullUnmarked = hasAnnotation(classSym, NULLUNMARKED_NAME);
                  currentClassHasAnnotation =
                      currentClassHasAnnotation
                          || hasNullMarked
                          || hasNullUnmarked
                          || (currentClass != null && currentClass.methods().isEmpty());
                  // only save classes containing jspecify annotations
                  if (currentClass != null && currentClassHasAnnotation) {
                    // save current class context
                    classStack.push(currentClass);
                  }
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
                  moduleClasses
                      .computeIfAbsent(moduleName, k -> new ArrayList<>())
                      .add(currentClass);
                  super.visitClass(classTree, null);
                  // restore previous class context
                  currentClass = !classStack.isEmpty() ? classStack.pop() : null;
                  return null;
                }

                @Override
                public Void visitMethod(MethodTree methodTree, Void unused) {
                  MethodSymbol mSym = (MethodSymbol) trees.getElement(getCurrentPath());
                  if (mSym.getModifiers().contains(Modifier.PRIVATE)) {
                    return super.visitMethod(methodTree, null);
                  }
                  boolean methodHasAnnotations = false;
                  String returnType = "";
                  if (methodTree.getReturnType() != null) {
                    returnType += mSym.getReturnType().toString();
                    if (hasJspecifyAnnotation(mSym.getReturnType().getAnnotationMirrors())) {
                      methodHasAnnotations = true;
                    }
                  }
                  boolean hasNullMarked = hasAnnotation(mSym, NULLMARKED_NAME);
                  boolean hasNullUnmarked = hasAnnotation(mSym, NULLUNMARKED_NAME);
                  methodHasAnnotations = methodHasAnnotations || hasNullMarked || hasNullUnmarked;
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
                          methodTypeParams);
                  // only add currentClass if there are annotations in it
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

                private boolean hasAnnotation(com.sun.tools.javac.code.Symbol sym, String fqn) {
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
                  com.sun.tools.javac.code.Symbol tpSym =
                      (com.sun.tools.javac.code.Symbol) trees.getElement(tpPath);
                  if (tpSym == null) {
                    return false;
                  }

                  // 1. Check annotations on the type variable declaration itself (e.g.,
                  // <@MyAnnotation T>)
                  if (hasJspecifyAnnotation(tpSym.getAnnotationMirrors())) {
                    return true;
                  }

                  // 2. Check annotations on the *bounds* (e.g., <T extends @Nullable Object>)
                  // These are type-use annotations and live on the TypeMirror of the bound.
                  TypeMirror tpType = tpSym.asType();
                  if (tpType instanceof javax.lang.model.type.TypeVariable typeVar) {
                    // Check upper bound
                    TypeMirror upperBound = typeVar.getUpperBound();
                    if (upperBound != null
                        && hasJspecifyAnnotation(upperBound.getAnnotationMirrors())) {
                      return true;
                    }

                    // Check lower bound
                    TypeMirror lowerBound = typeVar.getLowerBound();
                    if (lowerBound != null
                        && hasJspecifyAnnotation(lowerBound.getAnnotationMirrors())) {
                      return true;
                    }
                  }
                  return false;
                }

                /** Helper method to check a list of AnnotationMirrors for JSpecify annotations. */
                private boolean hasJspecifyAnnotation(List<? extends AnnotationMirror> mirrors) {
                  if (mirrors == null) {
                    return false;
                  }
                  for (AnnotationMirror am : mirrors) {
                    String fqn = am.getAnnotationType().toString();
                    if (fqn.equals(NULLABLE_NAME)
                        || fqn.equals(NONNULL_NAME)
                        || fqn.equals(NULLMARKED_NAME)
                        || fqn.equals(NULLUNMARKED_NAME)) {
                      return true;
                    }
                  }
                  return false;
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
