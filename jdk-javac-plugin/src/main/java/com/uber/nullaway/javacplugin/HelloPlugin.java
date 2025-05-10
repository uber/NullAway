package com.uber.nullaway.javacplugin;

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
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.type.TypeMirror;

public class HelloPlugin implements Plugin {

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
          public void started(com.sun.source.util.TaskEvent e) {}

          @Override
          public void finished(com.sun.source.util.TaskEvent e) {
            if (e.getKind() != com.sun.source.util.TaskEvent.Kind.ANALYZE) {
              return;
            }
            CompilationUnitTree cu = e.getCompilationUnit();
            new TreePathScanner<Void, Void>() {

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

                System.out.println(
                    "Class: "
                        + simpleName
                        + "  Type: "
                        + classType
                        + "  @NullMarked="
                        + hasNullMarked
                        + "  @NullUnmarked="
                        + hasNullUnmarked);

                // class type‐parameters
                for (TypeParameterTree tp : classTree.getTypeParameters()) {
                  System.out.println(formatTypeParam(tp));
                }

                return super.visitClass(classTree, null);
              }

              @Override
              public Void visitMethod(MethodTree methodTree, Void unused) {
                MethodSymbol mSym = (MethodSymbol) trees.getElement(getCurrentPath());
                if (mSym == null || mSym.getModifiers().contains(Modifier.PRIVATE)) {
                  return null; // skip private methods
                }

                boolean hasNullMarked = hasAnnotation(mSym, "org.jspecify.annotations.NullMarked");
                boolean hasNullUnmarked =
                    hasAnnotation(mSym, "org.jspecify.annotations.NullUnmarked");

                System.out.println("  Method: " + mSym);
                System.out.println(
                    "    @NullMarked=" + hasNullMarked + "  @NullUnmarked=" + hasNullUnmarked);

                // method type‐parameters (for generic methods)
                for (TypeParameterTree tp : methodTree.getTypeParameters()) {
                  System.out.println("    " + formatTypeParam(tp));
                }

                return null;
              }

              /** Helper to format a TypeParameterTree with annotations and bounds */
              private String formatTypeParam(TypeParameterTree tp) {
                String anns =
                    tp.getAnnotations().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(" "));
                String bounds =
                    tp.getBounds().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(" & "));
                return "  TypeParameter: <"
                    + (anns.isEmpty() ? "" : anns + " ")
                    + tp.getName()
                    + (bounds.isEmpty() ? "" : " extends " + bounds)
                    + ">";
              }

              private boolean hasAnnotation(com.sun.tools.javac.code.Symbol sym, String fqn) {
                return sym.getAnnotationMirrors().stream()
                    .map(AnnotationMirror::getAnnotationType)
                    .map(Object::toString)
                    .anyMatch(fqn::equals);
              }
            }.scan(cu, null);
          }
        });
  }
}
