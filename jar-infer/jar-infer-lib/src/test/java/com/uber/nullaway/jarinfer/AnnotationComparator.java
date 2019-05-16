package com.uber.nullaway.jarinfer;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.tree.AnnotationNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

public class AnnotationComparator {
  public static boolean CompareMethodAnnotationsInClass(String classFile1, String classFile2)
      throws IOException {
    System.out.println("File1: " + classFile1);
    System.out.println("File2: " + classFile2);

    ClassReader cr1 = new ClassReader(new FileInputStream(classFile1));
    ClassNode cn1 = new ClassNode();
    cr1.accept(cn1, 0);

    ClassReader cr2 = new ClassReader(new FileInputStream(classFile2));
    ClassNode cn2 = new ClassNode();
    cr2.accept(cn2, 0);

    System.out.println("  Class1: " + cn1.name);
    System.out.println("  Class2: " + cn2.name);
    if (!cn1.name.equals(cn2.name)) return false;

    List<MethodNode> methods1 = cn1.methods;
    List<MethodNode> methods2 = cn2.methods;
    for (MethodNode method1 : methods1) {
      System.out.println("    Looking for method: " + method1.name + " " + method1.desc);
      boolean foundMatchingMethod = false;
      for (MethodNode method2 : methods2) {
        if (AreMethodsSame(method1, method2)) {
          if (!AreAnnotationsSame(method1, method2)) {
            return false;
          }
          foundMatchingMethod = true;
          break;
        }
      }
      System.out.println("       Found matching method in the other class: " + foundMatchingMethod);
      if (!foundMatchingMethod) {
        return false;
      }
    }
    return true;
  }

  private static boolean AreAnnotationsSame(MethodNode method1, MethodNode method2) {
    if (!AreAnnotationsSame(method1.visibleAnnotations, method2.visibleAnnotations)) {
      return false;
    }
    List<AnnotationNode>[] paramAnnotations1 = method1.visibleParameterAnnotations;
    List<AnnotationNode>[] paramAnnotations2 = method2.visibleParameterAnnotations;
    if (paramAnnotations1 == null && paramAnnotations2 == null) return true;
    if ((paramAnnotations1 != null && paramAnnotations2 == null)
        || (paramAnnotations1 == null && paramAnnotations2 != null)) {
      return false;
    }
    if (paramAnnotations1.length != paramAnnotations2.length) {
      return false;
    }
    for (int i = 0; i < paramAnnotations1.length; ++i) {
      if (!AreAnnotationsSame(paramAnnotations1[i], paramAnnotations2[i])) {
        return false;
      }
    }
    return true;
  }

  private static boolean AreAnnotationsSame(
      List<AnnotationNode> annotations1, List<AnnotationNode> annotations2) {
    if (annotations1 == null && annotations2 == null) return true;
    if ((annotations1 == null && annotations2 != null)
        || (annotations1 != null && annotations2 == null)) {
      return false;
    }
    if (annotations1.size() != annotations2.size()) return false;
    for (int i = 0; i < annotations1.size(); ++i) {
      System.out.println("    Annotation1 : " + annotations1.get(i).desc);
      System.out.println("    Annotation2 : " + annotations2.get(i).desc);
      if (!annotations1.get(i).desc.equals(annotations2.get(i).desc)) {
        return false;
      }
    }
    return true;
  }

  private static boolean AreMethodsSame(MethodNode method1, MethodNode method2) {
    return method1.name.equals(method2.name) && method1.desc.equals(method2.desc);
  }
}
