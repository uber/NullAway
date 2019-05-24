package com.uber.nullaway.jarinfer;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.tree.AnnotationNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

/** Class to check if the methods in the given class / jar files have the expected annotations. */
public class AnnotationChecker {
  /**
   * Checks if the given jar file contains the expected annotations. The annotations that are
   * expected are specified in the form of a map. For example: map = {"ExpectNullable;",
   * "Ljavax/annotation/Nullable;"} will check if all methods and params contain
   * "Ljavax/annotation/Nullable;" iff "ExpectNullable;" is present.
   */
  public static boolean checkMethodAnnotationsInJar(
      String jarFile, Map<String, String> expectedToActualAnnotations) throws IOException {
    Preconditions.checkArgument(jarFile.endsWith(".jar"), "invalid jar file: " + jarFile);
    JarFile jar = new JarFile(jarFile);
    Enumeration<JarEntry> entries = jar.entries();
    while (entries.hasMoreElements()) {
      JarEntry entry = entries.nextElement();
      if (entry.getName().endsWith(".class")
          && !checkMethodAnnotationsInClass(
              jar.getInputStream(entry), expectedToActualAnnotations)) {
        return false;
      }
    }
    return true;
  }

  private static boolean checkMethodAnnotationsInClass(
      InputStream is, Map<String, String> expectedToActualAnnotations) throws IOException {
    ClassReader cr = new ClassReader(is);
    ClassNode cn = new ClassNode();
    cr.accept(cn, 0);

    for (MethodNode method : cn.methods) {
      if (!checkExpectedAnnotations(method.visibleAnnotations, expectedToActualAnnotations)) {
        System.out.println("Error: Expected annotations not found on method " + method.name);
        return false;
      }
      List<AnnotationNode>[] paramAnnotations = method.visibleParameterAnnotations;
      if (paramAnnotations == null) continue;
      for (List<AnnotationNode> annotations : paramAnnotations) {
        if (!checkExpectedAnnotations(annotations, expectedToActualAnnotations)) {
          System.out.println(
              "Error: Expected annotations not found in a parameter of " + method.name);
          return false;
        }
      }
    }
    return true;
  }

  private static boolean checkExpectedAnnotations(
      List<AnnotationNode> annotations, Map<String, String> expectedToActualAnnotations) {
    for (Map.Entry<String, String> item : expectedToActualAnnotations.entrySet()) {
      if (!checkExpectedAnnotation(annotations, item.getKey(), item.getValue())) {
        return false;
      }
    }
    return true;
  }

  // If `annotations` contain `expectAnnotation`:
  //    - Returns true iff `annotations` contain `actualAnnotation`, false otherwise.
  // If `annotations` do not contain `expectAnnotation`:
  //    - Returns true iff `annotations` do not contain `actualAnnotation`, false otherwise.
  private static boolean checkExpectedAnnotation(
      List<AnnotationNode> annotations, String expectAnnotation, String actualAnnotation) {
    if (containsAnnotation(annotations, expectAnnotation)) {
      return containsAnnotation(annotations, actualAnnotation);
    }
    return !containsAnnotation(annotations, actualAnnotation);
  }

  // Returns true iff `annotation` is found in the list `annotations`, false otherwise.
  private static boolean containsAnnotation(List<AnnotationNode> annotations, String annotation) {
    if (annotations == null) return false;
    for (AnnotationNode annotationNode : annotations) {
      if (annotationNode.desc.equals(annotation)) {
        return true;
      }
    }
    return false;
  }
}
