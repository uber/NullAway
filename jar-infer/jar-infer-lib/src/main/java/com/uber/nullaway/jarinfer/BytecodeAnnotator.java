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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Enumeration;
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
    if (cond) System.out.println("[" + tag + "] " + msg);
  }

  public static final String javaxNullableDesc = "Ljavax/annotation/Nullable;";
  public static final String javaxNonnullDesc = "Ljavax/annotation/Nonnull;";

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

  private static void addAnnotationIfNotPresent(
      List<AnnotationNode> annotationList, String annotation) {
    for (AnnotationNode node : annotationList) {
      if (node.desc.equals(annotation)) {
        return;
      }
    }
    annotationList.add(new AnnotationNode(Opcodes.ASM7, annotation));
  }

  private static void annotateBytecode(
      InputStream is,
      OutputStream os,
      MethodParamAnnotations nonnullParams,
      MethodReturnAnnotations nullableReturns)
      throws IOException {
    ClassReader cr = new ClassReader(is);
    ClassWriter cw = new ClassWriter(0);
    ClassNode cn = new ClassNode(Opcodes.ASM7);
    cr.accept(cn, 0);

    String className = cn.name.replace('/', '.');
    List<MethodNode> methods = cn.methods;
    for (MethodNode method : methods) {
      String methodSignature = className + "." + method.name + method.desc;
      if (nullableReturns.contains(methodSignature)) {
        // Add a @Nullable annotation on this method to indicate that the method can return null.
        if (method.visibleAnnotations == null) {
          method.visitAnnotation(javaxNullableDesc, true);
        } else {
          addAnnotationIfNotPresent(method.visibleAnnotations, javaxNullableDesc);
        }
        LOG(debug, "DEBUG", "Added nullable return annotation for " + methodSignature);
      }
      Set<Integer> params = nonnullParams.get(methodSignature);
      if (params != null) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        for (Integer param : params) {
          int paramNum = isStatic ? param : param - 1;
          // Add a @Nonnull annotation on this parameter.
          if (method.visibleParameterAnnotations == null
              || method.visibleParameterAnnotations.length < paramNum
              || method.visibleParameterAnnotations[paramNum] == null) {
            method.visitParameterAnnotation(paramNum, javaxNonnullDesc, true);
          } else {
            addAnnotationIfNotPresent(
                method.visibleParameterAnnotations[paramNum], javaxNonnullDesc);
          }
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
   * @throws IOException
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
    annotateBytecode(is, os, nonnullParams, nullableReturns);
  }

  private static void copyAndAnnotateJarEntry(
      JarEntry jarEntry,
      InputStream is,
      JarOutputStream jarOS,
      MethodParamAnnotations nonnullParams,
      MethodReturnAnnotations nullableReturns,
      boolean stripJarSignatures)
      throws IOException {
    String entryName = jarEntry.getName();
    if (entryName.endsWith(".class")) {
      jarOS.putNextEntry(new ZipEntry(jarEntry.getName()));
      annotateBytecode(is, jarOS, nonnullParams, nullableReturns);
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
   * @throws IOException
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
    for (Enumeration<JarEntry> entries = inputJar.entries(); entries.hasMoreElements(); ) {
      JarEntry jarEntry = entries.nextElement();
      InputStream is = inputJar.getInputStream(jarEntry);
      copyAndAnnotateJarEntry(
          jarEntry, is, jarOS, nonnullParams, nullableReturns, stripJarSignatures);
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
   * @throws IOException
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
    for (Enumeration<? extends ZipEntry> entries = inputZip.entries();
        entries.hasMoreElements(); ) {
      ZipEntry zipEntry = entries.nextElement();
      InputStream is = inputZip.getInputStream(zipEntry);
      zipOS.putNextEntry(new ZipEntry(zipEntry.getName()));
      if (zipEntry.getName().equals("classes.jar")) {
        JarInputStream jarIS = new JarInputStream(is);
        JarEntry inputJarEntry = jarIS.getNextJarEntry();

        ByteArrayOutputStream byteArrayOS = new ByteArrayOutputStream();
        JarOutputStream jarOS = new JarOutputStream(byteArrayOS);
        while (inputJarEntry != null) {
          copyAndAnnotateJarEntry(
              inputJarEntry, jarIS, jarOS, nonnullParams, nullableReturns, stripJarSignatures);
          inputJarEntry = jarIS.getNextJarEntry();
        }
        zipOS.write(byteArrayOS.toByteArray());
      } else {
        zipOS.write(IOUtils.toByteArray(is));
      }
      zipOS.closeEntry();
    }
  }
}
