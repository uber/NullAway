package com.uber.nullaway.jarinfer;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.tree.AnnotationNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.Opcodes;

public class BytecodeVerifier {
  public static boolean VerifyClass(
      String file, Map<String, Set<Integer>> nonnullParams, Set<String> nullableReturns)
      throws IOException {
    System.out.println("Verifiying class : " + file);
    if (nonnullParams != null) System.out.println("Non null params: " + nonnullParams.toString());
    if (nullableReturns != null)
      System.out.println("Nullable returns: " + nullableReturns.toString());
    ClassReader cr = new ClassReader(new FileInputStream(file));
    ClassNode classNode = new ClassNode();
    cr.accept(classNode, 0);

    List<MethodNode> methods = classNode.methods;
    int numMethodsWithNonnullParamAnnotations = 0;
    int numMethodsWithNullableReturnAnnotations = 0;
    for (MethodNode method : methods) {
      String methodSignature = classNode.name.replace('/', '.') + "." + method.name + method.desc;
      System.out.println("Checking method: " + methodSignature);
      if (nullableReturns.contains(methodSignature)) {
        if (!IsAnnotationPresentOnMethod(method, BytecodeAnnotator.javaxNullableDesc)) {
          return false;
        }
        ++numMethodsWithNullableReturnAnnotations;
      }
      Set<Integer> params = nonnullParams.get(methodSignature);
      if (params != null && !params.isEmpty()) {
        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        for (Integer param : params) {
          int paramNum = isStatic ? param.intValue() : param.intValue() - 1;
          System.out.println("  For param : " + paramNum);
          if (!IsAnnotationPresentOnParam(method, paramNum, BytecodeAnnotator.javaxNonnullDesc)) {
            return false;
          }
        }
        ++numMethodsWithNonnullParamAnnotations;
      }
    }

    // Checking the number of annotations matched against the number of annotations
    // in the Map / Set ensures that all the expected annotations have been found.
    return numMethodsWithNullableReturnAnnotations == nullableReturns.size()
        && numMethodsWithNonnullParamAnnotations == nonnullParams.size();
  }

  private static boolean IsAnnotationPresentOnMethod(
      MethodNode method, String expectedAnnotationDesc) {
    return IsAnnotationPresent(method.visibleAnnotations, expectedAnnotationDesc);
  }

  private static boolean IsAnnotationPresentOnParam(
      MethodNode method, int paramNum, String expectedAnnotationDesc) {
    List<AnnotationNode>[] paramAnnotations = method.visibleParameterAnnotations;
    if (paramAnnotations != null
        && paramAnnotations.length > paramNum
        && paramAnnotations[paramNum] != null) {
      return IsAnnotationPresent(paramAnnotations[paramNum], expectedAnnotationDesc);
    }
    return false;
  }

  private static boolean IsAnnotationPresent(
      List<AnnotationNode> annotations, String expectedAnnotationDesc) {
    if (annotations == null) return false;
    for (AnnotationNode annotation : annotations) {
      if (annotation.desc.equals(expectedAnnotationDesc)) {
        System.out.println("    Found annotation: " + annotation.desc);
        return true;
      }
    }
    return false;
  }
}
