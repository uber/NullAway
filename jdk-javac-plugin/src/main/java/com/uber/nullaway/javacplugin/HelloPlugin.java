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
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.type.TypeMirror;

public class HelloPlugin implements Plugin {

  // Data classes for JSON output
  static class TypeParamInfo {
    String name;
    List<String> annotations;
    List<String> bounds;
  }

  static class MethodInfo {
    String name;
    boolean nullMarked;
    boolean nullUnmarked;
    List<TypeParamInfo> typeParams = new ArrayList<>();
  }

  static class ClassInfo {
    String name;
    String type;
    boolean nullMarked;
    boolean nullUnmarked;
    List<TypeParamInfo> typeParams = new ArrayList<>();
    List<MethodInfo> methods = new ArrayList<>();
  }

  private final List<ClassInfo> classes = new ArrayList<>();

  @Override
  public String getName() {
    return "HelloPlugin";
  }

  @Override
  public void init(JavacTask task, String... args) {
    Trees trees = Trees.instance(task);
    task.addTaskListener(
        new com.sun.source.util.TaskListener() {
          @Override
          public void started(com.sun.source.util.TaskEvent e) {
            // No need to count compilation units
          }

          @Override
          public void finished(com.sun.source.util.TaskEvent e) {
            if (e.getKind() == com.sun.source.util.TaskEvent.Kind.ANALYZE) {
              CompilationUnitTree cu = e.getCompilationUnit();
              new TreePathScanner<Void, Void>() {
                ClassInfo currentClass = null;

                @Override
                public Void visitClass(ClassTree classTree, Void unused) {
                  Name simpleName = classTree.getSimpleName();
                  if (simpleName.contentEquals("")) {
                    return null; // skip anonymous
                  }
                  ClassSymbol classSym = (ClassSymbol) trees.getElement(getCurrentPath());
                  if (classSym.getModifiers().contains(Modifier.PRIVATE)) {
                    return null; // skip private classes
                  }
                  TypeMirror classType = trees.getTypeMirror(getCurrentPath());
                  boolean hasNullMarked =
                      hasAnnotation(classSym, "org.jspecify.annotations.NullMarked");
                  boolean hasNullUnmarked =
                      hasAnnotation(classSym, "org.jspecify.annotations.NullUnmarked");
                  currentClass = new ClassInfo();
                  currentClass.name = simpleName.toString();
                  currentClass.type = classType.toString();
                  currentClass.nullMarked = hasNullMarked;
                  currentClass.nullUnmarked = hasNullUnmarked;
                  for (TypeParameterTree tp : classTree.getTypeParameters()) {
                    currentClass.typeParams.add(typeParamInfo(tp));
                  }
                  classes.add(currentClass);
                  super.visitClass(classTree, null);
                  currentClass = null;
                  return null;
                }

                @Override
                public Void visitMethod(MethodTree methodTree, Void unused) {
                  MethodSymbol mSym = (MethodSymbol) trees.getElement(getCurrentPath());
                  if (mSym == null || mSym.getModifiers().contains(Modifier.PRIVATE)) {
                    return null; // skip private methods
                  }
                  boolean hasNullMarked =
                      hasAnnotation(mSym, "org.jspecify.annotations.NullMarked");
                  boolean hasNullUnmarked =
                      hasAnnotation(mSym, "org.jspecify.annotations.NullUnmarked");
                  MethodInfo methodInfo = new MethodInfo();
                  methodInfo.name = mSym.toString();
                  methodInfo.nullMarked = hasNullMarked;
                  methodInfo.nullUnmarked = hasNullUnmarked;
                  for (TypeParameterTree tp : methodTree.getTypeParameters()) {
                    methodInfo.typeParams.add(typeParamInfo(tp));
                  }
                  if (currentClass != null) {
                    currentClass.methods.add(methodInfo);
                  }
                  return null;
                }

                private TypeParamInfo typeParamInfo(TypeParameterTree tp) {
                  TypeParamInfo info = new TypeParamInfo();
                  info.name = tp.getName().toString();
                  info.annotations = new ArrayList<>();
                  for (var ann : tp.getAnnotations()) {
                    info.annotations.add(ann.toString());
                  }
                  info.bounds = new ArrayList<>();
                  for (var b : tp.getBounds()) {
                    info.bounds.add(b.toString());
                  }
                  return info;
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
              // write string to file
              Path p = Paths.get("foo.json");
              System.out.println(p.toAbsolutePath());
              try {
                Files.writeString(p, gson.toJson(classes));
              } catch (IOException ex) {
                throw new RuntimeException(ex);
              }
            }
          }
        });
  }
}
