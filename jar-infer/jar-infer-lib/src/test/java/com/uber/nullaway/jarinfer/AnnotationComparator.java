package com.uber.nullaway.jarinfer;

import com.google.common.base.Preconditions;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.tree.AnnotationNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

public class AnnotationComparator {
  public static boolean CompareMethodAnnotationsInJars(String jarFile1, String jarFile2)
      throws IOException {
    Preconditions.checkArgument(
        jarFile1.endsWith(".jar") && jarFile2.endsWith(".jar"),
        "invalid jar files: " + jarFile1 + " " + jarFile2);
    JarFile jar1 = new JarFile(jarFile1);
    JarFile jar2 = new JarFile(jarFile2);
    Enumeration<JarEntry> entries1 = jar1.entries();
    Enumeration<JarEntry> entries2 = jar2.entries();
    while (entries1.hasMoreElements()) {
      if (!entries2.hasMoreElements()) {
        return false;
      }
      JarEntry entry1 = entries1.nextElement();
      JarEntry entry2 = entries2.nextElement();
      if (!entry1.getName().equals(entry2.getName())) {
        System.out.println(
            "  Jar entries don't match: " + entry1.getName() + " " + entry2.getName());
        return false;
      }
      if (entry1.getName().endsWith(".class")) {
        if (!CompareMethodAnnotationsInClasses(
            jar1.getInputStream(entry1), jar2.getInputStream(entry2))) {
          System.out.println(
              "  Method annotations don't match in " + entry1.getName() + " " + entry2.getName());
          return false;
        }
      }
    }
    return !entries2.hasMoreElements();
  }

  public static boolean CompareMethodAnnotationsInClasses(String classFile1, String classFile2)
      throws IOException {
    Preconditions.checkArgument(
        classFile1.endsWith(".class") && classFile2.endsWith(".class"),
        "invalid class files: " + classFile1 + " " + classFile2);
    System.out.println("File1: " + classFile1);
    System.out.println("File2: " + classFile2);

    return CompareMethodAnnotationsInClasses(
        new FileInputStream(classFile1), new FileInputStream(classFile2));
  }

  public static boolean CompareMethodAnnotationsInClasses(InputStream is1, InputStream is2)
      throws IOException {
    ClassReader cr1 = new ClassReader(is1);
    ClassNode cn1 = new ClassNode();
    cr1.accept(cn1, 0);

    ClassReader cr2 = new ClassReader(is2);
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
