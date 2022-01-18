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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.IOUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Annotates the given methods and method parameters with the specified annotations using ASM. */
public final class BytecodeAnnotator {
  private static boolean debug = false;

  private static void LOG(boolean cond, String tag, String msg) {
    if (cond) {
      System.out.println("[" + tag + "] " + msg);
    }
  }

  public static final String javaxNullableDesc = "Ljavax/annotation/Nullable;";
  public static final String javaxNonnullDesc = "Ljavax/annotation/Nonnull;";
  // Consider android.support.annotation.* as a configuration option for older code?
  public static final String androidNullableDesc = "Landroidx/annotation/Nullable;";
  public static final String androidNonnullDesc = "Landroidx/annotation/NonNull;";

  public static final ImmutableSet<String> NULLABLE_ANNOTATIONS =
      ImmutableSet.of(
          javaxNullableDesc,
          androidNullableDesc,
          // We don't support adding the annotations below, but they would still be redundant,
          // specially when converted by tools which rewrite these sort of annotation (often
          // to their androidx.* variant)
          "Landroid/support/annotation/Nullable;",
          "Lorg/jetbrains/annotations/Nullable;");

  public static final ImmutableSet<String> NONNULL_ANNOTATIONS =
      ImmutableSet.of(
          javaxNonnullDesc,
          androidNonnullDesc,
          // See above
          "Landroid/support/annotation/NonNull;",
          "Lorg/jetbrains/annotations/NotNull;");

  public static final Sets.SetView<String> NULLABILITY_ANNOTATIONS =
      Sets.union(NULLABLE_ANNOTATIONS, NONNULL_ANNOTATIONS);

  // Constants used for signed jar processing
  private static final String SIGNED_JAR_ERROR_MESSAGE =
      "JarInfer will not process signed jars by default. "
          + "Please take one of the following actions:\n"
          + "\t1) Remove the signature from the original jar before passing it to jarinfer,\n"
          + "\t2) Pass the --strip-jar-signatures flag to JarInfer and the tool will remove signature "
          + "metadata for you, or\n"
          + "\t3) Exclude this jar from those being processed by JarInfer.";
  private static final String BASE64_PATTERN =
      "(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?";
  private static final String DIGEST_ENTRY_PATTERN =
      "Name: [A-Za-z0-9/\\$\\n\\s\\-\\.]+[A-Za-z0-9]\\nSHA-256-Digest: " + BASE64_PATTERN;

  private static boolean annotationsShouldBeVisible(String nullableDesc) {
    if (nullableDesc.equals(javaxNullableDesc)) {
      return true;
    } else if (nullableDesc.equals(androidNullableDesc)) {
      return false;
    } else {
      throw new Error("Unknown nullness annotation visibility");
    }
  }

  private static boolean listHasNullnessAnnotations(List<AnnotationNode> annotationList) {
    if (annotationList != null) {
      for (AnnotationNode node : annotationList) {
        if (NULLABILITY_ANNOTATIONS.contains(node.desc)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Returns true if any part of this method already has @Nullable/@NonNull annotations, in which
   * case we skip it, assuming that the developer already captured the desired spec.
   *
   * @param method The method node.
   * @return true iff either the return or any parameter formal has a nullness annotation.
   */
  private static boolean hasNullnessAnnotations(MethodNode method) {
    if (listHasNullnessAnnotations(method.visibleAnnotations)
        || listHasNullnessAnnotations(method.invisibleAnnotations)) {
      return true;
    }
    if (method.visibleParameterAnnotations != null) {
      for (List<AnnotationNode> annotationList : method.visibleParameterAnnotations) {
        if (listHasNullnessAnnotations(annotationList)) {
          return true;
        }
      }
    }
    if (method.invisibleParameterAnnotations != null) {
      for (List<AnnotationNode> annotationList : method.invisibleParameterAnnotations) {
        if (listHasNullnessAnnotations(annotationList)) {
          return true;
        }
      }
    }
    return false;
  }

  private static void annotateBytecode(
      InputStream is,
      OutputStream os,
      MethodParamAnnotations nonnullParams,
      MethodReturnAnnotations nullableReturns,
      String nullableDesc,
      String nonnullDesc)
      throws IOException {
    ClassReader cr = new ClassReader(is);
    ClassWriter cw = new ClassWriter(0);
    ClassNode cn = new ClassNode(Opcodes.ASM7);
    cr.accept(cn, 0);

    String className = cn.name.replace('/', '.');
    List<MethodNode> methods = cn.methods;
    for (MethodNode method : methods) {
      // Skip methods that already have nullability annotations anywhere in their signature
      if (hasNullnessAnnotations(method)) {
        continue;
      }
      boolean visible = annotationsShouldBeVisible(nullableDesc);
      String methodSignature = className + "." + method.name + method.desc;
      if (nullableReturns.contains(methodSignature)) {
        // Add a @Nullable annotation on this method to indicate that the method can return null.
        method.visitAnnotation(nullableDesc, visible);
        LOG(debug, "DEBUG", "Added nullable return annotation for " + methodSignature);
      }
      Set<Integer> params = nonnullParams.get(methodSignature);
      if (params != null) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        for (Integer param : params) {
          int paramNum = isStatic ? param : param - 1;
          // Add a @Nonnull annotation on this parameter.
          method.visitParameterAnnotation(paramNum, nonnullDesc, visible);
          LOG(
              debug,
              "DEBUG",
              "Added nonnull parameter annotation for #" + param + " in " + methodSignature);
        }
      }
    }

    cn.accept(cw);
    os.write(cw.toByteArray());
  }

  /**
   * Annotates the methods and method parameters in the given class with the specified annotations.
   *
   * @param is InputStream for the input class.
   * @param os OutputStream for the output class.
   * @param nonnullParams Map from methods to their nonnull params.
   * @param nullableReturns List of methods that return nullable.
   * @param debug flag to output debug logs.
   * @throws IOException if an error happens when reading or writing to class streams.
   */
  public static void annotateBytecodeInClass(
      InputStream is,
      OutputStream os,
      MethodParamAnnotations nonnullParams,
      MethodReturnAnnotations nullableReturns,
      boolean debug)
      throws IOException {
    BytecodeAnnotator.debug = debug;
    LOG(debug, "DEBUG", "nullableReturns: " + nullableReturns);
    LOG(debug, "DEBUG", "nonnullParams: " + nonnullParams);
    annotateBytecode(is, os, nonnullParams, nullableReturns, javaxNullableDesc, javaxNonnullDesc);
  }

  private static void copyAndAnnotateJarEntry(
      JarEntry jarEntry,
      InputStream is,
      JarOutputStream jarOS,
      MethodParamAnnotations nonnullParams,
      MethodReturnAnnotations nullableReturns,
      String nullableDesc,
      String nonnullDesc,
      boolean stripJarSignatures)
      throws IOException {
    String entryName = jarEntry.getName();
    if (entryName.endsWith(".class")) {
      jarOS.putNextEntry(new ZipEntry(jarEntry.getName()));
      annotateBytecode(is, jarOS, nonnullParams, nullableReturns, nullableDesc, nonnullDesc);
    } else if (entryName.equals("META-INF/MANIFEST.MF")) {
      // Read full file
      StringBuilder stringBuilder = new StringBuilder();
      BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
      String currentLine;
      while ((currentLine = br.readLine()) != null) {
        stringBuilder.append(currentLine + "\n");
      }
      String manifestText = stringBuilder.toString();
      // Check for evidence of jar signing, note that lines can be split if too long so regex
      // matching line by line will have false negatives.
      String manifestMinusDigests = manifestText.replaceAll(DIGEST_ENTRY_PATTERN, "");
      if (!manifestText.equals(manifestMinusDigests) && !stripJarSignatures) {
        throw new SignedJarException(SIGNED_JAR_ERROR_MESSAGE);
      }
      jarOS.putNextEntry(new ZipEntry(jarEntry.getName()));
      jarOS.write(manifestMinusDigests.getBytes("UTF-8"));
    } else if (entryName.startsWith("META-INF/")
        && (entryName.endsWith(".DSA")
            || entryName.endsWith(".RSA")
            || entryName.endsWith(".SF"))) {
      if (!stripJarSignatures) {
        throw new SignedJarException(SIGNED_JAR_ERROR_MESSAGE);
      } // the case where stripJarSignatures==true is handled by default by skipping these files
    } else {
      jarOS.putNextEntry(new ZipEntry(jarEntry.getName()));
      jarOS.write(IOUtils.toByteArray(is));
    }
    jarOS.closeEntry();
  }

  /**
   * Annotates the methods and method parameters in the classes in the given jar with the specified
   * annotations.
   *
   * @param inputJar JarFile to annotate.
   * @param jarOS OutputStream of the output jar file.
   * @param nonnullParams Map from methods to their nonnull params.
   * @param nullableReturns List of methods that return nullable.
   * @param debug flag to output debug logs.
   * @throws IOException if an error happens when reading or writing to jar or class streams.
   */
  public static void annotateBytecodeInJar(
      JarFile inputJar,
      JarOutputStream jarOS,
      MethodParamAnnotations nonnullParams,
      MethodReturnAnnotations nullableReturns,
      boolean stripJarSignatures,
      boolean debug)
      throws IOException {
    BytecodeAnnotator.debug = debug;
    LOG(debug, "DEBUG", "nullableReturns: " + nullableReturns);
    LOG(debug, "DEBUG", "nonnullParams: " + nonnullParams);
    // Do not use JarInputStream in place of JarFile/JarEntry. JarInputStream misses MANIFEST.MF
    // while iterating over the entries in the stream.
    // Reference: https://bugs.openjdk.java.net/browse/JDK-8215788
    // Note: we can't just put the code below inside stream().forach(), because it can throw
    // IOException.
    for (JarEntry jarEntry : (Iterable<JarEntry>) inputJar.stream()::iterator) {
      InputStream is = inputJar.getInputStream(jarEntry);
      copyAndAnnotateJarEntry(
          jarEntry,
          is,
          jarOS,
          nonnullParams,
          nullableReturns,
          javaxNullableDesc,
          javaxNonnullDesc,
          stripJarSignatures);
    }
  }

  /**
   * Annotates the methods and method parameters in the classes in "classes.jar" in the given aar
   * file with the specified annotations.
   *
   * @param inputZip AarFile to annotate.
   * @param zipOS OutputStream of the output aar file.
   * @param nonnullParams Map from methods to their nonnull params.
   * @param nullableReturns List of methods that return nullable.
   * @param debug flag to output debug logs.
   * @throws IOException if an error happens when reading or writing to AAR/JAR/class streams.
   */
  public static void annotateBytecodeInAar(
      ZipFile inputZip,
      ZipOutputStream zipOS,
      MethodParamAnnotations nonnullParams,
      MethodReturnAnnotations nullableReturns,
      boolean stripJarSignatures,
      boolean debug)
      throws IOException {
    BytecodeAnnotator.debug = debug;
    LOG(debug, "DEBUG", "nullableReturns: " + nullableReturns);
    LOG(debug, "DEBUG", "nonnullParams: " + nonnullParams);
    // Error Prone doesn't like usages of the old Java Enumerator APIs. ZipFile does not implement
    // Iterable, and likely never will (see  https://bugs.openjdk.java.net/browse/JDK-6581715).
    // Additionally, inputZip.stream() returns a Stream<? extends ZipEntry>, and a for-each loop
    // has trouble handling the corresponding ::iterator  method reference. So this seems like the
    // best remaining way:
    Iterator<? extends ZipEntry> zipIterator = inputZip.stream().iterator();
    while (zipIterator.hasNext()) {
      ZipEntry zipEntry = zipIterator.next();
      InputStream is = inputZip.getInputStream(zipEntry);
      zipOS.putNextEntry(new ZipEntry(zipEntry.getName()));
      if (zipEntry.getName().equals("classes.jar")) {
        JarInputStream jarIS = new JarInputStream(is);
        JarEntry inputJarEntry = jarIS.getNextJarEntry();

        ByteArrayOutputStream byteArrayOS = new ByteArrayOutputStream();
        JarOutputStream jarOS = new JarOutputStream(byteArrayOS);
        while (inputJarEntry != null) {
          copyAndAnnotateJarEntry(
              inputJarEntry,
              jarIS,
              jarOS,
              nonnullParams,
              nullableReturns,
              androidNullableDesc,
              androidNonnullDesc,
              stripJarSignatures);
          inputJarEntry = jarIS.getNextJarEntry();
        }
        jarOS.flush();
        jarOS.close();
        zipOS.write(byteArrayOS.toByteArray());
      } else {
        zipOS.write(IOUtils.toByteArray(is));
      }
      zipOS.closeEntry();
    }
  }
}
