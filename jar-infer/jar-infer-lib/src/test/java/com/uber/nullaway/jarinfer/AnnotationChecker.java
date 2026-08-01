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
import com.google.common.base.Verify;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Class to check if the methods in the given class / jar files have the expected annotations. */
public class AnnotationChecker {
  private static final String expectNullableMethod = "expectNullable";
  private static final String expectNonnullParamsMethod = "expectNonnull";

  /**
   * Checks if the given aar file contains the expected annotations. The annotations that are
   * expected are specified in the form of a map. For example: map = {"ExpectNullable;",
   * "Ljavax/annotation/Nullable;"} will check that methods and parameters contain
   * "Ljavax/annotation/Nullable;" iff "ExpectNullable;" is present.
   *
   * @param aarFile Path to the input aar file.
   * @param expectedToActualAnnotations Map from 'Expect*' annotations to the actual annotations
   *     that are expected to be present.
   * @return True when the actual annotations are present iff their corresponding 'Expect*'
   *     annotations are present.
   * @throws IOException if an error happens when reading the AAR file.
   */
  public static boolean checkMethodAnnotationsInAar(
      String aarFile, Map<String, String> expectedToActualAnnotations) throws IOException {
    Preconditions.checkArgument(aarFile.endsWith(".aar"), "invalid aar file: %s", aarFile);
    ZipFile zip = new ZipFile(aarFile);
    Iterator<? extends ZipEntry> zipIterator = zip.stream().iterator();
    while (zipIterator.hasNext()) {
      ZipEntry zipEntry = zipIterator.next();
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
   * "Ljavax/annotation/Nullable;"} will check that methods and parameters contain
   * "Ljavax/annotation/Nullable;" iff "ExpectNullable;" is present.
   *
   * @param jarFile Path to the input jar file.
   * @param expectedToActualAnnotations Map from 'Expect*' annotations to the actual annotations
   *     that are expected to be present.
   * @return True when the actual annotations are present iff their corresponding 'Expect*'
   *     annotations are present.
   * @throws IOException if an error happens when reading the jar file.
   */
  public static boolean checkMethodAnnotationsInJar(
      String jarFile, Map<String, String> expectedToActualAnnotations) throws IOException {
    Preconditions.checkArgument(jarFile.endsWith(".jar"), "invalid jar file: %s", jarFile);
    JarFile jar = new JarFile(jarFile);
    for (JarEntry entry : (Iterable<JarEntry>) jar.stream()::iterator) {
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
      boolean methodAnnotationsValid;
      methodAnnotationsValid =
          method.name.equals(expectNullableMethod)
              ? hasOneJavaxNullableAnnotation(method)
              : checkExpectedAnnotations(
                  method.visibleAnnotations,
                  method.invisibleAnnotations,
                  expectedToActualAnnotations);
      if (!methodAnnotationsValid) {
        System.out.println(
            "Error: Invalid / Unexpected annotations found on method '" + method.name + "'");
        return false;
      }
      if (method.name.equals(expectNonnullParamsMethod)) {
        if (checkTestMethodParamAnnotationByName(method)) {
          continue;
        }
        System.out.println(
            "Error: Invalid / Unexpected annotations found in a parameter of method '"
                + method.name
                + "'.");
        return false;
      }
      int numParameters = Type.getArgumentTypes(method.desc).length;
      for (int param = 0; param < numParameters; param++) {
        List<AnnotationNode> visibleAnnotations =
            method.visibleParameterAnnotations != null
                    && param < method.visibleParameterAnnotations.length
                ? method.visibleParameterAnnotations[param]
                : null;
        List<AnnotationNode> invisibleAnnotations =
            method.invisibleParameterAnnotations != null
                    && param < method.invisibleParameterAnnotations.length
                ? method.invisibleParameterAnnotations[param]
                : null;
        if (!checkExpectedAnnotations(
            visibleAnnotations, invisibleAnnotations, expectedToActualAnnotations)) {
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

  private static boolean hasOneJavaxNullableAnnotation(MethodNode method) {
    return countAnnotations(method.visibleAnnotations, BytecodeAnnotator.javaxNullableDesc) == 1;
  }

  /**
   * Check if all the parameters of the method have the 'javax.annotation.Nonnull' annotation on it
   * exactly once. All such methods are also expected to have at least one parameter with this
   * annotation.
   *
   * @param method method to be checked. Must have the name {@link #expectNonnullParamsMethod}
   * @return True if 'javax.annotation.Nonnull' is present exactly once on all the method's
   *     parameters.
   */
  private static boolean checkTestMethodParamAnnotationByName(MethodNode method) {
    Verify.verify(method.name.equals(expectNonnullParamsMethod));
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
    return true;
  }

  private static boolean checkExpectedAnnotations(
      @Nullable List<AnnotationNode> visibleAnnotations,
      @Nullable List<AnnotationNode> invisibleAnnotations,
      Map<String, String> expectedToActualAnnotations) {
    for (Map.Entry<String, String> item : expectedToActualAnnotations.entrySet()) {
      if (!checkExpectedAnnotation(
          visibleAnnotations, invisibleAnnotations, item.getKey(), item.getValue())) {
        return false;
      }
    }
    return true;
  }

  // If either annotation list contains `expectAnnotation`, returns true iff the lists contain
  // exactly one `actualAnnotation` in total. Otherwise, returns true iff they do not contain
  // `actualAnnotation`.
  private static boolean checkExpectedAnnotation(
      @Nullable List<AnnotationNode> visibleAnnotations,
      @Nullable List<AnnotationNode> invisibleAnnotations,
      String expectAnnotation,
      String actualAnnotation) {
    if (containsAnnotation(visibleAnnotations, expectAnnotation)
        || containsAnnotation(invisibleAnnotations, expectAnnotation)) {
      int numAnnotationsFound =
          countAnnotations(visibleAnnotations, actualAnnotation)
              + countAnnotations(invisibleAnnotations, actualAnnotation);
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
    return !containsAnnotation(visibleAnnotations, actualAnnotation)
        && !containsAnnotation(invisibleAnnotations, actualAnnotation);
  }

  // Returns true iff `annotation` is found in the list `annotations`, false otherwise.
  private static boolean containsAnnotation(
      @Nullable List<AnnotationNode> annotations, String annotation) {
    return countAnnotations(annotations, annotation) > 0;
  }

  // Returns the number of times 'annotation' is present in the list 'annotations'.
  private static int countAnnotations(
      @Nullable List<AnnotationNode> annotations, String annotation) {
    if (annotations == null) {
      return 0;
    }
    int count = 0;
    for (AnnotationNode annotationNode : annotations) {
      if (annotationNode.desc.equals(annotation)) {
        count++;
      }
    }
    return count;
  }
}
