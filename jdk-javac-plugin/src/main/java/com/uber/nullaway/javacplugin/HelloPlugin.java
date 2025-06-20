package com.uber.nullaway.javacplugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
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

public class HelloPlugin implements Plugin {

  // Data classes for JSON output
  record TypeParamInfo(String name, List<String> annotations, List<String> bounds) {}

  record MethodInfo(
      String name, boolean nullMarked, boolean nullUnmarked, List<TypeParamInfo> typeParams) {}

  record ClassInfo(
      String name,
      String type,
      boolean nullMarked,
      boolean nullUnmarked,
      List<TypeParamInfo> typeParams,
      List<MethodInfo> methods) {}

  // Map from module name to list of classes
  private final Map<String, List<ClassInfo>> moduleClasses = new HashMap<>();

  @Override
  public String getName() {
    return "HelloPlugin";
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
                Deque<ClassInfo> classStack = new ArrayDeque<>();
                ClassInfo currentClass = null;

                @Override
                public Void visitClass(ClassTree classTree, Void unused) {
                  Name simpleName = classTree.getSimpleName();
                  if (simpleName.contentEquals("")) {
                    return null; // skip anonymous
                  }
                  ClassSymbol classSym = (ClassSymbol) trees.getElement(getCurrentPath());
                  // Determine module containing this class by walking owner chain
                  @SuppressWarnings("ASTHelpersSuggestions")
                  com.sun.tools.javac.code.Symbol s = classSym.packge().getEnclosingElement();
                  String moduleName =
                      (s instanceof com.sun.tools.javac.code.Symbol.ModuleSymbol
                              && !s.getQualifiedName().isEmpty())
                          ? s.getQualifiedName().toString()
                          : "unnamed";
                  if (classSym.getModifiers().contains(Modifier.PRIVATE)) {
                    return null; // skip private classes
                  }
                  TypeMirror classType = trees.getTypeMirror(getCurrentPath());
                  boolean hasNullMarked =
                      hasAnnotation(classSym, "org.jspecify.annotations.NullMarked");
                  boolean hasNullUnmarked =
                      hasAnnotation(classSym, "org.jspecify.annotations.NullUnmarked");
                  // push previous class context
                  if (currentClass != null) {
                    // save current class context
                    classStack.push(currentClass);
                  }
                  // build new class context
                  List<TypeParamInfo> classTypeParams = new ArrayList<>();
                  for (TypeParameterTree tp : classTree.getTypeParameters()) {
                    classTypeParams.add(typeParamInfo(tp));
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
                  boolean hasNullMarked =
                      hasAnnotation(mSym, "org.jspecify.annotations.NullMarked");
                  boolean hasNullUnmarked =
                      hasAnnotation(mSym, "org.jspecify.annotations.NullUnmarked");
                  // build method type parameters list
                  List<TypeParamInfo> methodTypeParams = new ArrayList<>();
                  for (TypeParameterTree tp : methodTree.getTypeParameters()) {
                    methodTypeParams.add(typeParamInfo(tp));
                  }
                  MethodInfo methodInfo =
                      new MethodInfo(
                          mSym.toString(), hasNullMarked, hasNullUnmarked, methodTypeParams);
                  if (currentClass != null) {
                    currentClass.methods().add(methodInfo);
                  }
                  return super.visitMethod(methodTree, null);
                }

                private TypeParamInfo typeParamInfo(TypeParameterTree tp) {
                  String name = tp.getName().toString();
                  List<String> annotations = new ArrayList<>();
                  for (var ann : tp.getAnnotations()) {
                    annotations.add(ann.toString());
                  }
                  List<String> bounds = new ArrayList<>();
                  for (var b : tp.getBounds()) {
                    bounds.add(b.toString());
                  }
                  return new TypeParamInfo(name, annotations, bounds);
                }

                private boolean hasAnnotation(com.sun.tools.javac.code.Symbol sym, String fqn) {
                  return sym.getAnnotationMirrors().stream()
                      .map(AnnotationMirror::getAnnotationType)
                      .map(Object::toString)
                      .anyMatch(fqn::equals);
                }
              }.scan(cu, null);
            } else if (e.getKind() == com.sun.source.util.TaskEvent.Kind.COMPILATION) {
              Gson gson = new GsonBuilder().setPrettyPrinting().create();
              // generate unique file name with UUID
              String jsonFileName = "classes-" + UUID.randomUUID() + ".json";
              // write string to file
              Path p = Paths.get(outputDir, jsonFileName);
              System.out.println(p.toAbsolutePath());
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
