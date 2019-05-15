package com.uber.nullaway.jarinfer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public final class BytecodeAnnotator extends ClassVisitor implements Opcodes {
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
    System.out.println("ClassName: " + className);
    System.out.println(
        "[JI-MethodVisitor]: visited method - " + name + " desc: " + desc + " sign: " + signature);
    String methodSignature = className + "." + name + desc;
    if (mv != null) {
      if (nullableReturns.contains(methodSignature)) {
        // Add a @Nullable annotation on this method to indicate that the method can return null.
        mv.visitAnnotation(javaxNullableDesc, true);
        System.out.println(
            "[JI-MethodVisitor]: Added nullable return annotation for " + methodSignature);
      }
      Set<Integer> params = nullableParams.get(methodSignature);
      if (params != null) {
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        for (Integer param : params) {
          // Add a @Nonnull annotation on this parameter.
          mv.visitParameterAnnotation(isStatic ? param : param - 1, javaxNonnullDesc, true);
          System.out.println(
              "[JI-MethodVisitor]: Added nullable parameter annotation for #"
                  + param
                  + " in "
                  + methodSignature);
        }
      }
    }
    return mv;
  }

  public static void annotateBytecode(
      InputStream jarIS,
      OutputStream jarOS,
      Map<String, Set<Integer>> map_result,
      Set<String> nullableReturns)
      throws IOException {
    System.out.println("nullableReturns: " + nullableReturns);
    System.out.println("map_result: " + map_result);
    ClassReader cr = new ClassReader(jarIS);
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
    BytecodeAnnotator bytecodeAnnotator = new BytecodeAnnotator(cw, map_result, nullableReturns);
    cr.accept(bytecodeAnnotator, 0);

    jarOS.write(cw.toByteArray());
    jarOS.close();
  }
}
