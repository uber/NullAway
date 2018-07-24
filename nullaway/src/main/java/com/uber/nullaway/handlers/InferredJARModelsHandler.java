/*
 * Copyright (c) 2018 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.handlers;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;

/** This handler loads inferred nullability model from stubs for methods in unannotated packages. */
public class InferredJARModelsHandler extends BaseNoOpHandler {

  private static final int VERSION_0_FILE_MAGIC_NUMBER = 691458791;
  private static final String DEFAULT_ASTUBX_LOCATION = "META-INF/nullaway/jarinfer.astubx";

  private static final int RETURN = -1; // '-1' indexes Return type in the Annotation Cache

  private static boolean DEBUG = false;
  private static boolean VERBOSE = false;

  private static Map<String, Map<String, Map<Integer, Set<String>>>> argAnnotCache;
  private static Set<String> loadedJars;

  public InferredJARModelsHandler() {
    super();
    argAnnotCache = new LinkedHashMap<>();
    loadedJars = new HashSet<>();
  }

  @Override
  public ImmutableSet<Integer> onUnannotatedInvocationGetNonNullPositions(
      NullAway analysis,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol,
      List<? extends ExpressionTree> actualParams,
      ImmutableSet<Integer> nonNullPositions) {
    Symbol.ClassSymbol classSymbol = methodSymbol.enclClass();
    String className = classSymbol.getQualifiedName().toString();
    if (methodSymbol.getModifiers().contains(Modifier.ABSTRACT)) {
      if (VERBOSE) {
        System.out.println(
            "[JI Warn] Skipping abstract method: "
                + className
                + " : "
                + methodSymbol.getQualifiedName());
      }
      return nonNullPositions;
    }
    if (!lookupAndBuildCache(classSymbol)) return nonNullPositions;
    String methodSign = getMethodSignature(methodSymbol);
    Map<Integer, Set<String>> methodArgAnnotations = lookupMethodInCache(className, methodSign);
    if (methodArgAnnotations == null) return nonNullPositions;
    Set<Integer> jiNonNullParams = new LinkedHashSet<>();
    for (Map.Entry<Integer, Set<String>> annotationEntry : methodArgAnnotations.entrySet()) {
      if (annotationEntry.getKey() != RETURN
          && annotationEntry.getValue().contains("javax.annotation.Nonnull")) {
        // Skip 'this' param for non-static methods
        jiNonNullParams.add(annotationEntry.getKey() - (methodSymbol.isStatic() ? 0 : 1));
      }
    }
    if (DEBUG && !jiNonNullParams.isEmpty()) {
      System.out.println(
          "[JI DEBUG] Nonnull params: " + jiNonNullParams.toString() + " for " + methodSign);
    }
    return Sets.union(nonNullPositions, jiNonNullParams).immutableCopy();
  }

  private static final boolean NULLABLE = true;

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Types types,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol(node.getTree());
    Preconditions.checkNotNull(methodSymbol);
    Symbol.ClassSymbol classSymbol = methodSymbol.enclClass();
    String className = classSymbol.getQualifiedName().toString();
    if (!lookupAndBuildCache(classSymbol)) return NullnessHint.UNKNOWN;
    String methodSign = getMethodSignature(methodSymbol);
    Map<Integer, Set<String>> methodArgAnnotations = lookupMethodInCache(className, methodSign);
    if (methodArgAnnotations == null) return NullnessHint.UNKNOWN;
    Set<String> methodAnnotations = methodArgAnnotations.get(RETURN);
    if (methodAnnotations != null) {
      if (methodAnnotations.contains("javax.annotation.Nullable")) {
        if (DEBUG) {
          System.out.println("[JI DEBUG] Nullable return for method: " + methodSign);
        }
        return NullnessHint.HINT_NULLABLE;
      }
    }
    return NullnessHint.UNKNOWN;
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis, ExpressionTree expr, VisitorState state, boolean exprMayBeNull) {
    if (expr.getKind().equals(Tree.Kind.METHOD_INVOCATION)) {
      Symbol.MethodSymbol methodSymbol = ASTHelpers.getSymbol((MethodInvocationTree) expr);
      Preconditions.checkNotNull(methodSymbol);
      Symbol.ClassSymbol classSymbol = methodSymbol.enclClass();
      String className = classSymbol.getQualifiedName().toString();
      if (!lookupAndBuildCache(classSymbol)) return exprMayBeNull;
      String methodSign = getMethodSignature(methodSymbol);
      Map<Integer, Set<String>> methodArgAnnotations = lookupMethodInCache(className, methodSign);
      if (methodArgAnnotations == null) return exprMayBeNull;
      Set<String> methodAnnotations = methodArgAnnotations.get(RETURN);
      if (methodAnnotations != null) {
        if (methodAnnotations.contains("javax.annotation.Nullable")) {
          if (DEBUG) {
            System.out.println("[JI DEBUG] Nullable return for method: " + methodSign);
          }
          return NULLABLE;
        }
      }
    }
    return exprMayBeNull;
  }

  private boolean lookupAndBuildCache(Symbol.ClassSymbol klass) {
    String className = klass.getQualifiedName().toString();
    try {
      if (DEBUG) {
        System.out.println("[JI DEBUG] Looking for class: " + className);
      }
      if (klass.classfile == null) {
        if (VERBOSE) {
          System.out.println("[JI Warn] Cannot resolve source for class: " + className);
        }
        return false;
      }
      // Annotation cache
      String jarPath = "";
      if (!argAnnotCache.containsKey(className)) {
        // this works for aar !
        JarURLConnection juc =
            ((JarURLConnection) klass.classfile.toUri().toURL().openConnection());
        jarPath = juc.getJarFileURL().getPath();
        if (DEBUG) {
          System.out.println(
              "[JI DEBUG] Found source of class: " + className + ", jar: " + jarPath);
        }
        // Avoid reloading for classes w/o any stubs from already loaded jars.
        if (!loadedJars.contains(jarPath)) {
          JarFile jar = juc.getJarFile();
          if (jar == null) {
            throw new Error("Cannot open jar: " + jarPath);
          }
          loadedJars.add(jarPath);
          JarEntry astubxJE = jar.getJarEntry(DEFAULT_ASTUBX_LOCATION);
          if (astubxJE == null) {
            if (VERBOSE) {
              System.out.println("[JI Warn] Cannot find jarinfer.astubx in jar: " + jarPath);
            }
            return false;
          }
          InputStream astubxIS = jar.getInputStream(astubxJE);
          if (astubxIS == null) {
            if (VERBOSE) {
              System.out.println("[JI Warn] Cannot load jarinfer.astubx in jar: " + jarPath);
            }
            return false;
          }
          parseStubStream(astubxIS, jarPath + ": " + DEFAULT_ASTUBX_LOCATION);
          if (DEBUG) {
            System.out.println(
                "[JI DEBUG] Loaded "
                    + argAnnotCache.keySet().size()
                    + " astubx for class: "
                    + className
                    + " from jar: "
                    + jarPath);
          }
        } else if (DEBUG) {
          System.out.println("[JI DEBUG] Skipping already loaded jar: " + jarPath);
        }
      } else if (DEBUG) {
        System.out.println("[JI DEBUG] Hit annotation cache for class: " + className);
      }
      if (!argAnnotCache.containsKey(className)) {
        if (VERBOSE) {
          System.out.println(
              "[JI Warn] Cannot find Annotation Cache for class: "
                  + className
                  + ", jar: "
                  + jarPath);
        }
        return false;
      }
    } catch (IOException e) {
      throw new Error(e);
    }
    return true;
  }

  private Map<Integer, Set<String>> lookupMethodInCache(String className, String methodSign) {
    if (!argAnnotCache.containsKey(className)) return null;
    Map<Integer, Set<String>> methodArgAnnotations = argAnnotCache.get(className).get(methodSign);
    if (methodArgAnnotations == null) {
      if (VERBOSE) {
        System.out.println(
            "[JI Warn] Cannot find Annotation Cache entry for method: "
                + methodSign
                + " in class: "
                + className);
      }
      return null;
    }
    if (DEBUG) {
      System.out.println(
          "[JI DEBUG] Found Annotation Cache entry for method: "
              + methodSign
              + " in class: "
              + className
              + " -- "
              + methodArgAnnotations.toString());
    }
    return methodArgAnnotations;
  }

  private String getMethodSignature(Symbol.MethodSymbol method) {
    // Generate method signature
    String methodSign =
        method.enclClass().getQualifiedName().toString()
            + ":"
            + (method.isStaticOrInstanceInit()
                ? ""
                : getSimpleTypeName(method.getReturnType()) + " ")
            + method.getSimpleName()
            + "(";
    if (!method.getParameters().isEmpty()) {
      for (Symbol.VarSymbol var : method.getParameters()) {
        methodSign += getSimpleTypeName(var.type) + ", ";
      }
      methodSign = methodSign.substring(0, methodSign.lastIndexOf(','));
    }
    methodSign += ")";
    if (DEBUG) {
      System.out.println("[JI DEBUG] @ method sign: " + methodSign);
    }
    return methodSign;
  }

  private String getSimpleTypeName(Type typ) {
    if (typ.getKind() == TypeKind.TYPEVAR)
      return typ.getUpperBound().tsym.getSimpleName().toString();
    else return typ.tsym.getSimpleName().toString();
  }

  private void parseStubStream(InputStream stubxInputStream, String stubxLocation)
      throws IOException {
    String[] strings;
    DataInputStream in = new DataInputStream(stubxInputStream);
    // Read and check the magic version number
    if (in.readInt() != VERSION_0_FILE_MAGIC_NUMBER) {
      throw new Error("Invalid file version/magic number for stubx file!" + stubxLocation);
    }
    // Read the number of strings in the string dictionary
    int numStrings = in.readInt();
    // Populate the string dictionary {idx => value}, where idx is encoded by the string position
    // inside this section.
    strings = new String[numStrings];
    for (int i = 0; i < numStrings; ++i) {
      strings[i] = in.readUTF();
    }
    // Read the number of (package, annotation) entries
    int numPackages = in.readInt();
    // Read each (package, annotation) entry, where the int values point into the string
    // dictionary loaded before.
    for (int i = 0; i < numPackages; ++i) {
      in.readInt(); // String packageName = strings[in.readInt()];
      in.readInt(); // String annotation = strings[in.readInt()];
    }
    // Read the number of (type, annotation) entries
    int numTypes = in.readInt();
    // Read each (type, annotation) entry, where the int values point into the string
    // dictionary loaded before.
    for (int i = 0; i < numTypes; ++i) {
      in.readInt(); // String typeName = strings[in.readInt()];
      in.readInt(); // String annotation = strings[in.readInt()];
    }
    // Read the number of (method, annotation) entries
    int numMethods = in.readInt();
    // Read each (method, annotation) record
    for (int i = 0; i < numMethods; ++i) {
      String methodSig = strings[in.readInt()];
      String annotation = strings[in.readInt()];
      if (DEBUG) {
        System.out.println(
            "[JI DEBUG] method: " + methodSig + ", return annotation: " + annotation);
      }
      cacheAnnotation(methodSig, RETURN, annotation);
    }
    // Read the number of (method, argument, annotation) entries
    int numArgumentRecords = in.readInt();
    // Read each (method, argument, annotation) record
    for (int i = 0; i < numArgumentRecords; ++i) {
      String methodSig = strings[in.readInt()];
      if (methodSig.lastIndexOf(':') == -1 || methodSig.split(":")[0].lastIndexOf('.') == -1) {
        throw new Error(
            "Invalid method signature " + methodSig + " in stubx file " + stubxLocation);
      }
      int argNum = in.readInt();
      String annotation = strings[in.readInt()];
      if (DEBUG) {
        System.out.println(
            "[JI DEBUG] method: "
                + methodSig
                + ", argNum: "
                + argNum
                + ", annotation: "
                + annotation);
      }
      cacheAnnotation(methodSig, argNum, annotation);
    }
  }

  private void cacheAnnotation(String methodSig, Integer argNum, String annotation) {
    // TODO: handle inner classes properly
    String className = methodSig.split(":")[0].replace('$', '.');
    if (!argAnnotCache.containsKey(className)) argAnnotCache.put(className, new LinkedHashMap<>());
    if (!argAnnotCache.get(className).containsKey(methodSig))
      argAnnotCache.get(className).put(methodSig, new LinkedHashMap<>());
    if (!argAnnotCache.get(className).get(methodSig).containsKey(argNum))
      argAnnotCache.get(className).get(methodSig).put(argNum, new LinkedHashSet<>());
    argAnnotCache.get(className).get(methodSig).get(argNum).add(annotation);
  }
}
