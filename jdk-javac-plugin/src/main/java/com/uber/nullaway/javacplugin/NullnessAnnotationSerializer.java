package com.uber.nullaway.javacplugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
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

/**
 * A Javac plugin that serializes nullness annotations from Java source files into a JSON file.
 * Primarily intended for serializing annotations from the JSpecify JDK models.
 */
public class NullnessAnnotationSerializer implements Plugin {

  private static final String NULLMARKED_NAME = "org.jspecify.annotations.NullMarked";
  private static final String NULLUNMARKED_NAME = "org.jspecify.annotations.NullUnmarked";

  // Data classes for JSON output
  record TypeParamInfo(String name, List<String> bounds) {}

  record MethodInfo(
      String returnType,
      String name,
      boolean nullMarked,
      boolean nullUnmarked,
      List<TypeParamInfo> typeParams) {}

  record ClassInfo(
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

                @Override
                public Void visitClass(ClassTree classTree, Void unused) {
                  Name simpleName = classTree.getSimpleName();
                  if (simpleName.contentEquals("")) {
                    return null; // skip anonymous
                  }
                  ClassSymbol classSym = (ClassSymbol) trees.getElement(getCurrentPath());
                  // get enclosing class symbol (if any nullmarked, this is nullmarked)
                  // get the closest annotation
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
                  System.err.println(simpleName + "= " + hasNullMarked + " & " + hasNullUnmarked);
                  if (!(hasNullMarked
                      || hasNullUnmarked)) { // no @NullMarked or @NullUnmarked annotation at all
                    if (currentClass != null) {
                      if (currentClass.nullMarked) {
                        hasNullMarked = true;
                      }
                      if (currentClass.nullUnmarked) {
                        hasNullUnmarked = true;
                      }
                    }
                    //                    // not needed because any marked nullness in the hierarchy
                    // will be in the currentClass
                    //                    for (ClassInfo enclosingInfo : classStack) {
                    //                      System.err.println(enclosingInfo.name + "= " +
                    // enclosingInfo.nullMarked + " & " + enclosingInfo.nullUnmarked);
                    //                      if(enclosingInfo.nullMarked) {
                    //                        hasNullMarked = true;
                    //                        break;
                    //                      }
                    //                      if(enclosingInfo.nullUnmarked) {
                    //                        hasNullUnmarked = true;
                    //                        break;
                    //                      }
                    //                    }
                    // check enclosing types recursively
                    // if one of the annotation exists, follow that and exit loop
                  }
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
                  Tree mt = methodTree.getReturnType();
                  //                  if(mt != null) {
                  //                    TreePath returnTypePath = new TreePath(getCurrentPath(),
                  // mt);
                  //                    if (returnTypePath == null) {
                  //                    }
                  //                  }
                  //                  TypeMirror returnTypeMirror =
                  // trees.getTypeMirror(returnTypePath);
                  //                  System.out.println("Method: " + mSym.getSimpleName());
                  //                  System.out.println("Return Type Mirror: " +
                  // returnTypeMirror.toString());
                  //                  System.out.println("Annotations on Return Type: " +
                  // returnTypeMirror.getAnnotationMirrors());
                  //                  if(returnTypePath==null){}
                  String returnType = "";
                  if (mt != null) {
                    //                    TypeMirror returnTypeMirror = mSym.getReturnType();
                    //                    if(returnTypeMirror==null){}
                    //                    List<? extends AnnotationMirror> annotations =
                    // mSym.getAnnotationMirrors();
                    //                    var returnTypeTree = methodTree.getReturnType();
                    //                    TreePath returnTypePath = new TreePath(getCurrentPath(),
                    // returnTypeTree);
                    //                    TypeMirror returnTypeMirror =
                    // trees.getTypeMirror(returnTypePath);
                    //                    System.out.println("Method: " + mSym.getSimpleName());
                    //                    System.out.println("Return Type Mirror: " +
                    // returnTypeMirror.toString());
                    //                    System.out.println("Annotations on Return Type: " +
                    // returnTypeMirror.getAnnotationMirrors());
                    //                    for(var am : annotations) {
                    //                      returnType += am.getAnnotationType().toString();
                    //                      System.out.println(am.getAnnotationType().toString());
                    //                    }
                    returnType += mSym.getReturnType().toString();
                  }
                  //                  System.out.println(returnType);
                  boolean hasNullMarked = hasAnnotation(mSym, NULLMARKED_NAME);
                  boolean hasNullUnmarked = hasAnnotation(mSym, NULLUNMARKED_NAME);
                  System.err.println(currentClass.name + "." + mSym.toString());
                  if (!(hasNullMarked || hasNullUnmarked)) {
                    if (currentClass.nullMarked) {
                      hasNullMarked = true;
                    }
                    if (currentClass.nullUnmarked) {
                      hasNullUnmarked = true;
                    }
                  }
                  List<TypeParamInfo> methodTypeParams = new ArrayList<>();
                  for (TypeParameterTree tp : methodTree.getTypeParameters()) {
                    methodTypeParams.add(typeParamInfo(tp));
                  }
                  MethodInfo methodInfo =
                      new MethodInfo(
                          returnType,
                          mSym.toString(),
                          hasNullMarked,
                          hasNullUnmarked,
                          methodTypeParams);
                  if (currentClass != null) {
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
