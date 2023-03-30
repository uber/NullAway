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
import com.google.errorprone.VisitorState;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.Context;
import com.uber.nullaway.Config;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import com.uber.nullaway.jarinfer.JarInferStubxProvider;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;
import javax.lang.model.type.TypeKind;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;

/** This handler loads inferred nullability model from stubs for methods in unannotated packages. */
public class InferredJARModelsHandler extends BaseNoOpHandler {
  private static boolean DEBUG = false;
  private static boolean VERBOSE = false;

  private static void LOG(boolean cond, String tag, String msg) {
    if (cond) {
      System.out.println("[JI " + tag + "] " + msg);
    }
  }

  private static final int VERSION_0_FILE_MAGIC_NUMBER = 691458791;
  private static final String ANDROID_ASTUBX_LOCATION = "jarinfer.astubx";
  private static final String ANDROID_MODEL_CLASS =
      "com.uber.nullaway.jarinfer.AndroidJarInferModels";

  private static final int RETURN = -1; // '-1' indexes Return type in the Annotation Cache

  private final Map<String, Map<String, Map<Integer, Set<String>>>> argAnnotCache;

  private final Config config;

  public InferredJARModelsHandler(Config config) {
    super();
    this.config = config;
    argAnnotCache = new LinkedHashMap<>();
    loadStubxFiles();
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
      LOG(
          DEBUG,
          "DEBUG",
          "Cannot find Android RT models locator class."
              + " This is expected if not in an Android project, or the Android SDK JarInfer models Jar has not been set up for this build.");

    } catch (Exception e) {
      LOG(DEBUG, "DEBUG", "Cannot load Android RT models.");
    }
  }

  /**
   * Loads all stubx files discovered in the classpath. Stubx files are discovered via
   * implementations of {@link JarInferStubxProvider} loaded using a {@link ServiceLoader}
   */
  private void loadStubxFiles() {
    Iterable<JarInferStubxProvider> astubxProviders =
        ServiceLoader.load(
            JarInferStubxProvider.class, InferredJARModelsHandler.class.getClassLoader());
    for (JarInferStubxProvider provider : astubxProviders) {
      for (String astubxPath : provider.pathsToStubxFiles()) {
        Class<? extends JarInferStubxProvider> providerClass = provider.getClass();
        InputStream stubxInputStream = providerClass.getResourceAsStream(astubxPath);
        String stubxLocation = providerClass + ":" + astubxPath;
        try {
          parseStubStream(stubxInputStream, stubxLocation);
          LOG(DEBUG, "DEBUG", "loaded stubx file " + stubxLocation);
        } catch (IOException e) {
          throw new RuntimeException("could not parse stubx file " + stubxLocation, e);
        }
      }
    }
  }

  @Override
  public Nullness[] onOverrideMethodInvocationParametersNullability(
      Context context,
      Symbol.MethodSymbol methodSymbol,
      boolean isAnnotated,
      Nullness[] argumentPositionNullness) {
    if (isAnnotated) {
      // We currently do not load JarInfer models for code marked as annotated.
      // This is unlikely to change, as the behavior of JarInfer on arguments is to explicitly mark
      // as @NonNull those arguments that are shallowly dereferenced within the analyzed method. By
      // convention, annotated code has no use for explicit @NonNull annotations, since `T` already
      // means `@NonNull T` within annotated code. The only case where we would want to enable this
      // for annotated code is if we expect/want JarInfer results to override the results of another
      // handler, such as restrictive annotations, but a library models is a safer place to perform
      // such an override.
      // Additionally, by default, InferredJARModelsHandler is used only to load our Android SDK
      // JarInfer models (i.e. `com.uber.nullaway:JarInferAndroidModelsSDK##`), since the default
      // model of JarInfer on a normal jar/aar is to add bytecode annotations.
      return argumentPositionNullness;
    }
    Symbol.ClassSymbol classSymbol = methodSymbol.enclClass();
    String className = classSymbol.getQualifiedName().toString();
    if (methodSymbol.getModifiers().contains(Modifier.ABSTRACT)) {
      LOG(
          VERBOSE,
          "Warn",
          "Skipping abstract method: " + className + " : " + methodSymbol.getQualifiedName());
      return argumentPositionNullness;
    }
    if (!argAnnotCache.containsKey(className)) {
      return argumentPositionNullness;
    }
    String methodSign = getMethodSignature(methodSymbol);
    Map<Integer, Set<String>> methodArgAnnotations = lookupMethodInCache(className, methodSign);
    if (methodArgAnnotations == null) {
      return argumentPositionNullness;
    }
    Set<Integer> jiNonNullParams = new LinkedHashSet<>();
    for (Map.Entry<Integer, Set<String>> annotationEntry : methodArgAnnotations.entrySet()) {
      if (annotationEntry.getKey() != RETURN
          && annotationEntry.getValue().contains("javax.annotation.Nonnull")) {
        // Skip 'this' param for non-static methods
        int nonNullPosition = annotationEntry.getKey() - (methodSymbol.isStatic() ? 0 : 1);
        jiNonNullParams.add(nonNullPosition);
        argumentPositionNullness[nonNullPosition] = Nullness.NONNULL;
      }
    }
    if (!jiNonNullParams.isEmpty()) {
      LOG(DEBUG, "DEBUG", "Nonnull params: " + jiNonNullParams.toString() + " for " + methodSign);
    }
    return argumentPositionNullness;
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Symbol.MethodSymbol symbol,
      VisitorState state,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    if (isReturnAnnotatedNullable(symbol)) {
      return NullnessHint.HINT_NULLABLE;
    }
    return NullnessHint.UNKNOWN;
  }

  @Override
  public boolean onOverrideMayBeNullExpr(
      NullAway analysis,
      ExpressionTree expr,
      @Nullable Symbol exprSymbol,
      VisitorState state,
      boolean exprMayBeNull) {
    if (exprMayBeNull) {
      return true;
    }
    if (expr.getKind() == Tree.Kind.METHOD_INVOCATION
        && exprSymbol instanceof Symbol.MethodSymbol
        && isReturnAnnotatedNullable((Symbol.MethodSymbol) exprSymbol)) {
      return true;
    }
    return false;
  }

  private boolean isReturnAnnotatedNullable(Symbol.MethodSymbol methodSymbol) {
    if (config.isJarInferUseReturnAnnotations()) {
      Preconditions.checkNotNull(methodSymbol);
      Symbol.ClassSymbol classSymbol = methodSymbol.enclClass();
      String className = classSymbol.getQualifiedName().toString();
      if (argAnnotCache.containsKey(className)) {
        String methodSign = getMethodSignature(methodSymbol);
        Map<Integer, Set<String>> methodArgAnnotations = lookupMethodInCache(className, methodSign);
        if (methodArgAnnotations != null) {
          Set<String> methodAnnotations = methodArgAnnotations.get(RETURN);
          if (methodAnnotations != null) {
            if (methodAnnotations.contains("javax.annotation.Nullable")) {
              LOG(DEBUG, "DEBUG", "Nullable return for method: " + methodSign);
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  @Nullable
  private Map<Integer, Set<String>> lookupMethodInCache(String className, String methodSign) {
    if (!argAnnotCache.containsKey(className)) {
      return null;
    }
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
    if (typ.getKind() == TypeKind.TYPEVAR) {
      return typ.getUpperBound().tsym.getSimpleName().toString();
    } else {
      return typ.tsym.getSimpleName().toString();
    }
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
    Map<String, Map<Integer, Set<String>>> cacheForClass =
        argAnnotCache.computeIfAbsent(className, s -> new LinkedHashMap<>());
    Map<Integer, Set<String>> cacheForMethod =
        cacheForClass.computeIfAbsent(methodSig, s -> new LinkedHashMap<>());
    Set<String> cacheForArgument =
        cacheForMethod.computeIfAbsent(argNum, s -> new LinkedHashSet<>());
    cacheForArgument.add(annotation);
  }
}
