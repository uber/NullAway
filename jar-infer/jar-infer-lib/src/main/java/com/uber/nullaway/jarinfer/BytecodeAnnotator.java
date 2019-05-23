package com.uber.nullaway.jarinfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.apache.commons.io.IOUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class BytecodeAnnotator extends ClassVisitor implements Opcodes {
  private static boolean DEBUG = false;

  private static void LOG(boolean cond, String tag, String msg) {
    if (cond) System.out.println("[" + tag + "] " + msg);
  }

  public static final String javaxNullableDesc = "Ljavax/annotation/Nullable;";
  public static final String javaxNonnullDesc = "Ljavax/annotation/Nonnull;";

  private final Map<String, Set<Integer>> nullableParams;
  private final Set<String> nullableReturns;

  private String className = "";

  public BytecodeAnnotator(
      ClassVisitor cv, Map<String, Set<Integer>> nullableParams, Set<String> nullableReturns) {
    super(ASM7, cv);
    this.nullableParams = nullableParams;
    this.nullableReturns = nullableReturns;
  }

  @Override
  public void visit(
      final int version,
      final int access,
      final String name,
      final String signature,
      final String superName,
      final String[] interfaces) {
    className = name.replace('/', '.');
    if (cv != null) {
      cv.visit(version, access, name, signature, superName, interfaces);
    }
  }

  @Override
  public MethodVisitor visitMethod(
      final int access,
      final String name,
      final String desc,
      final String signature,
      final String[] exceptions) {
    MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
    LOG(DEBUG, "DEBUG", "ClassName: " + className);
    LOG(DEBUG, "DEBUG", "visited method - " + name + " desc: " + desc + " sign: " + signature);
    String methodSignature = className + "." + name + desc;
    if (mv != null) {
      if (nullableReturns.contains(methodSignature)) {
        // Add a @Nullable annotation on this method to indicate that the method can return null.
        mv.visitAnnotation(javaxNullableDesc, true);
        LOG(DEBUG, "DEBUG", "Added nullable return annotation for " + methodSignature);
      }
      Set<Integer> params = nullableParams.get(methodSignature);
      if (params != null) {
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        for (Integer param : params) {
          // Add a @Nonnull annotation on this parameter.
          mv.visitParameterAnnotation(isStatic ? param : param - 1, javaxNonnullDesc, true);
          LOG(
              DEBUG,
              "DEBUG",
              "Added nullable parameter annotation for #" + param + " in " + methodSignature);
        }
      }
    }
    return mv;
  }

  private static void annotateBytecode(
      InputStream is,
      OutputStream os,
      Map<String, Set<Integer>> map_result,
      Set<String> nullableReturns)
      throws IOException {
    ClassReader cr = new ClassReader(is);
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    BytecodeAnnotator bytecodeAnnotator = new BytecodeAnnotator(cw, map_result, nullableReturns);
    cr.accept(bytecodeAnnotator, 0);
    os.write(cw.toByteArray());
  }

  public static void annotateBytecodeInClass(
      InputStream is,
      OutputStream os,
      Map<String, Set<Integer>> map_result,
      Set<String> nullableReturns,
      boolean DEBUG)
      throws IOException {
    BytecodeAnnotator.DEBUG = DEBUG;
    LOG(DEBUG, "DEBUG", "nullableReturns: " + nullableReturns);
    LOG(DEBUG, "DEBUG", "nonnullParams: " + map_result);
    annotateBytecode(is, os, map_result, nullableReturns);
  }

  public static void annotateBytecodeInJar(
      JarFile inputJar,
      JarOutputStream jarOS,
      Map<String, Set<Integer>> map_result,
      Set<String> nullableReturns,
      boolean DEBUG)
      throws IOException {
    BytecodeAnnotator.DEBUG = DEBUG;
    LOG(DEBUG, "DEBUG", "nullableReturns: " + nullableReturns);
    LOG(DEBUG, "DEBUG", "nonnullParams: " + map_result);
    for (Enumeration<JarEntry> entries = inputJar.entries(); entries.hasMoreElements(); ) {
      JarEntry jarEntry = entries.nextElement();
      InputStream is = inputJar.getInputStream(jarEntry);
      jarOS.putNextEntry(new ZipEntry(jarEntry.getName()));
      if (jarEntry.getName().endsWith(".class")) {
        annotateBytecode(is, jarOS, map_result, nullableReturns);
      } else {
        jarOS.write(IOUtils.toByteArray(is));
      }
      jarOS.closeEntry();
    }
  }
}
