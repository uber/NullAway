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

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.errorprone.VisitorState;
import com.sun.source.tree.ExpressionTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullAway;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URLConnection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;

/** This handler loads inferred nullability model from stubs for methods in unannotated packages. */
public class InferredJARModelsHandler extends BaseNoOpHandler {
  private static boolean DEBUG = false;
  private static boolean VERBOSE = false;

  private static void LOG(boolean cond, String tag, String msg) {
    if (cond) System.out.println("[JI " + tag + "] " + msg);
  }

  private static final int VERSION_0_FILE_MAGIC_NUMBER = 691458791;
  private static final String DEFAULT_ASTUBX_LOCATION = "META-INF/nullaway/jarinfer.astubx";
  private static final String ANDROID_ASTUBX_LOCATION = "resources/jarinfer.astubx";
  private static final String ANDROID_MODEL_CLASS =
      "com.uber.nullaway.jarinfer.AndroidJarInferModels";

  private static final int RETURN = -1; // '-1' indexes Return type in the Annotation Cache

  private static Map<String, Map<String, Map<Integer, Set<String>>>> argAnnotCache;
  private static Set<String> loadedJars;

  public InferredJARModelsHandler() {
    super();
    argAnnotCache = new LinkedHashMap<>();
    loadedJars = new HashSet<>();
    // Load Android SDK JarInfer models
    try {
      InputStream androidStubxIS =
          Class.forName(ANDROID_MODEL_CLASS)
              .getClassLoader()
              .getResourceAsStream(ANDROID_ASTUBX_LOCATION);
      if (androidStubxIS != null) {
        parseStubStream(androidStubxIS, "android.jar: " + ANDROID_ASTUBX_LOCATION);
        LOG(DEBUG, "DEBUG", "Loaded Android RT models.");
      }
    } catch (ClassNotFoundException e) {
      LOG(DEBUG, "DEBUG", "Cannot find Android RT models locator class.");
    } catch (Exception e) {
      LOG(DEBUG, "DEBUG", "Cannot load Android RT models.");
    }
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
      LOG(
          VERBOSE,
          "Warn",
          "Skipping abstract method: " + className + " : " + methodSymbol.getQualifiedName());
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
    if (!jiNonNullParams.isEmpty())
      LOG(DEBUG, "DEBUG", "Nonnull params: " + jiNonNullParams.toString() + " for " + methodSign);
    return Sets.union(nonNullPositions, jiNonNullParams).immutableCopy();
  }

  private boolean lookupAndBuildCache(Symbol.ClassSymbol klass) {
    String className = klass.getQualifiedName().toString();
    try {
      LOG(DEBUG, "DEBUG", "Looking for class: " + className);
      if (klass.classfile == null) {
        LOG(VERBOSE, "Warn", "Cannot resolve source for class: " + className);
        return false;
      }
      // Annotation cache
      String jarPath = "";
      if (!argAnnotCache.containsKey(className)) {
        // this works for aar !
        URLConnection uc = klass.classfile.toUri().toURL().openConnection();
        if (!(uc instanceof JarURLConnection)) return false;
        JarURLConnection juc = (JarURLConnection) uc;
        jarPath = juc.getJarFileURL().getPath();
        LOG(DEBUG, "DEBUG", "Found source of class: " + className + ", jar: " + jarPath);
        // Avoid reloading for classes w/o any stubs from already loaded jars.
        if (!loadedJars.contains(jarPath)) {
          JarFile jar = juc.getJarFile();
          if (jar == null) {
            throw new Error("Cannot open jar: " + jarPath);
          }
          loadedJars.add(jarPath);
          JarEntry astubxJE = jar.getJarEntry(DEFAULT_ASTUBX_LOCATION);
          if (astubxJE == null) {
            LOG(VERBOSE, "Warn", "Cannot find jarinfer.astubx in jar: " + jarPath);
            return false;
          }
          InputStream astubxIS = jar.getInputStream(astubxJE);
          if (astubxIS == null) {
            LOG(VERBOSE, "Warn", "Cannot load jarinfer.astubx in jar: " + jarPath);
            return false;
          }
          parseStubStream(astubxIS, jarPath + ": " + DEFAULT_ASTUBX_LOCATION);
          LOG(
              DEBUG,
              "DEBUG",
              "Loaded "
                  + argAnnotCache.keySet().size()
                  + " astubx for class: "
                  + className
                  + " from jar: "
                  + jarPath);
        } else {
          LOG(DEBUG, "DEBUG", "Skipping already loaded jar: " + jarPath);
        }
      } else {
        LOG(DEBUG, "DEBUG", "Hit annotation cache for class: " + className);
      }
      if (!argAnnotCache.containsKey(className)) {
        LOG(
            VERBOSE,
            "Warn",
            "Cannot find Annotation Cache for class: " + className + ", jar: " + jarPath);
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
      LOG(
          VERBOSE,
          "Warn",
          "Cannot find Annotation Cache entry for method: "
              + methodSign
              + " in class: "
              + className);
      return null;
    }
    LOG(
        DEBUG,
        "DEBUG",
        "Found Annotation Cache entry for method: "
            + methodSign
            + " in class: "
            + className
            + " -- "
            + methodArgAnnotations.toString());
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
    LOG(DEBUG, "DEBUG", "@ method sign: " + methodSign);
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
      LOG(DEBUG, "DEBUG", "method: " + methodSig + ", return annotation: " + annotation);
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
      LOG(
          DEBUG,
          "DEBUG",
          "method: " + methodSig + ", argNum: " + argNum + ", arg annotation: " + annotation);
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
