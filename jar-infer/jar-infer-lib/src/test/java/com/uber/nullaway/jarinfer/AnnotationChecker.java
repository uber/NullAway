/*
 * Copyright (C) 2019. Uber Technologies
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.uber.nullaway.jarinfer;

import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.tree.AnnotationNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.Type;

/** Class to check if the methods in the given class / jar files have the expected annotations. */
public class AnnotationChecker {
  private static final String expectNullableMethod = "expectNullable";
  private static final String expectNonnullParamsMethod = "expectNonnull";

  /**
   * Checks if the given aar file contains the expected annotations. The annotations that are
   * expected are specified in the form of a map. For example: map = {"ExpectNullable;",
   * "Ljavax/annotation/Nullable;"} will check if all methods and params contain
   * "Ljavax/annotation/Nullable;" iff "ExpectNullable;" is present.
   *
   * @param aarFile Path to the input aar file.
   * @param expectedToActualAnnotations Map from 'Expect*' annotations to the actual annotations
   *     that are expected to be present.
   * @return True when the actual annotations that are expected to be present are present iff the
   *     'Expect*' annotations are present.
   * @throws IOException
   */
  public static boolean checkMethodAnnotationsInAar(
      String aarFile, Map<String, String> expectedToActualAnnotations) throws IOException {
    Preconditions.checkArgument(aarFile.endsWith(".aar"), "invalid aar file: " + aarFile);
    ZipFile zip = new ZipFile(aarFile);
    Enumeration<? extends ZipEntry> zipEntries = zip.entries();
    while (zipEntries.hasMoreElements()) {
      ZipEntry zipEntry = zipEntries.nextElement();
      if (zipEntry.getName().equals("classes.jar")) {
        JarInputStream jarIS = new JarInputStream(zip.getInputStream(zipEntry));
        JarEntry jarEntry = jarIS.getNextJarEntry();
        while (jarEntry != null) {
          if (jarEntry.getName().endsWith(".class")
              && !checkMethodAnnotationsInClass(jarIS, expectedToActualAnnotations)) {
            return false;
          }
          jarEntry = jarIS.getNextJarEntry();
        }
      }
    }
    return true;
  }

  /**
   * Checks if the given jar file contains the expected annotations. The annotations that are
   * expected are specified in the form of a map. For example: map = {"ExpectNullable;",
   * "Ljavax/annotation/Nullable;"} will check if all methods and params contain
   * "Ljavax/annotation/Nullable;" iff "ExpectNullable;" is present.
   *
   * @param jarFile Path to the input jar file.
   * @param expectedToActualAnnotations Map from 'Expect*' annotations to the actual annotations
   *     that are expected to be present.
   * @return True when the actual annotations that are expected to be present are present iff the
   *     'Expect*' annotations are present.
   * @throws IOException
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
      if (!checkExpectedAnnotations(method.visibleAnnotations, expectedToActualAnnotations)
          && !checkTestMethodAnnotation(method)) {
        System.out.println(
            "Error: Invalid / Unexpected annotations found on method '" + method.name + "'");
        return false;
      }
      List<AnnotationNode>[] paramAnnotations = method.visibleParameterAnnotations;
      if (paramAnnotations == null) continue;
      for (List<AnnotationNode> annotations : paramAnnotations) {
        if (!checkExpectedAnnotations(annotations, expectedToActualAnnotations)
            && !checkTestMethodParamAnnotation(method)) {
          System.out.println(
              "Error: Invalid / Unexpected annotations found in a parameter of method '"
                  + method.name
                  + "'.");
          return false;
        }
      }
    }
    return true;
  }

  // If the given method matches the expected test method name 'expectNullable', check if the method
  // has the 'javaxNullableDesc' annotation on it exactly once.
  private static boolean checkTestMethodAnnotation(MethodNode method) {
    if (method.name.equals(expectNullableMethod)) {
      return countAnnotations(method.visibleAnnotations, BytecodeAnnotator.javaxNullableDesc) == 1;
    }
    return true;
  }

  // If the given method matches the expected test method name 'expectNonnull', check if all the
  // parameters
  // of the method has the 'javaxNonnullDesc' annotation on it exactly once. All such methods are
  // also
  // expected to have at least one parameter with this annotation.
  private static boolean checkTestMethodParamAnnotation(MethodNode method) {
    if (method.name.equals(expectNonnullParamsMethod)) {
      int numParameters = Type.getArgumentTypes(method.desc).length;
      if (numParameters == 0
          || method.visibleParameterAnnotations == null
          || method.visibleParameterAnnotations.length < numParameters) {
        return false;
      }
      for (List<AnnotationNode> annotations : method.visibleParameterAnnotations) {
        if (countAnnotations(annotations, BytecodeAnnotator.javaxNonnullDesc) != 1) {
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
      int numAnnotationsFound = countAnnotations(annotations, actualAnnotation);
      if (numAnnotationsFound != 1) {
        System.out.println(
            "Error: Annotation '"
                + actualAnnotation
                + "' was found "
                + numAnnotationsFound
                + " times.");
        return false;
      }
      return true;
    }
    return !containsAnnotation(annotations, actualAnnotation);
  }

  // Returns true iff `annotation` is found in the list `annotations`, false otherwise.
  private static boolean containsAnnotation(List<AnnotationNode> annotations, String annotation) {
    return countAnnotations(annotations, annotation) > 0;
  }

  // Returns the number of times 'annotation' is present in the list 'annotations'.
  private static int countAnnotations(List<AnnotationNode> annotations, String annotation) {
    if (annotations == null) return 0;
    int count = 0;
    for (AnnotationNode annotationNode : annotations) {
      if (annotationNode.desc.equals(annotation)) {
        count++;
      }
    }
    return count;
  }
}
