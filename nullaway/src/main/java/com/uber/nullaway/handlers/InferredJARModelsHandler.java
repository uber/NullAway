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
import com.uber.nullaway.NullAway;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** This handler loads inferred nullability model from stubs for methods in unannotated packages. */
public class InferredJARModelsHandler extends BaseNoOpHandler {

  private static final int VERSION_0_FILE_MAGIC_NUMBER = 691458791;
  private static final String DEFAULT_ASTUBX_LOCATION = "META-INF/nullaway/jarinfer.astubx";

  private static final boolean VERBOSE = false;
  private static final boolean DEBUG = false;

  private static Map<String, Map<String, Map<Integer, Set<String>>>> argAnnotCache;

  public InferredJARModelsHandler() {
    super();
    argAnnotCache = new LinkedHashMap<>();
  }

  @Override
  public ImmutableSet<Integer> onUnannotatedInvocationGetNonNullPositions(
      NullAway analysis,
      VisitorState state,
      Symbol.MethodSymbol methodSymbol,
      List<? extends ExpressionTree> actualParams,
      ImmutableSet<Integer> nonNullPositions) {

    String className = methodSymbol.enclClass().getQualifiedName().toString();
    if (DEBUG) {
      System.out.println("[JI DEBUG] class: " + className);
    }
    try {
      ClassLoader cl = Class.forName(className).getClassLoader();
      InputStream astubxIS;
      if (cl == null) {
        if (VERBOSE) {
          System.out.println("[JI Warn] Cannnot find ClassLoader for " + className);
        }
        return nonNullPositions;
      }
      // TODO: Does this work for aar ?
      astubxIS = cl.getResourceAsStream(DEFAULT_ASTUBX_LOCATION);
      if (astubxIS == null) {
        if (VERBOSE) {
          System.out.println("[JI Warn] Cannnot find jarinfer.astubx inside JAR for " + className);
        }
        return nonNullPositions;
      }
      // Annotation cache
      if (!argAnnotCache.containsKey(className)) {
        argAnnotCache.put(className, new LinkedHashMap<>());
        if (DEBUG) {
          System.out.println("[JI DEBUG] Parsing " + className + ": " + DEFAULT_ASTUBX_LOCATION);
        }
        parseStubStream(
            astubxIS, className + ": " + DEFAULT_ASTUBX_LOCATION, argAnnotCache.get(className));
      }
      // Generate method signature
      // TODO handle Arrays
      String methodSign =
          methodSymbol.owner.getQualifiedName().toString()
              + ":"
              + methodSymbol.getSimpleName()
              + "(";
      for (Symbol.VarSymbol var : methodSymbol.getParameters()) {
        String argType = var.type.toString().split("<")[0];
        methodSign += argType.substring(argType.lastIndexOf('.') + 1) + ", ";
      }
      methodSign = methodSign.substring(0, methodSign.length() - 2) + ")";

      if (!argAnnotCache.containsKey(className)) {
        if (VERBOSE) {
          System.out.println("[JI Warn] Cannnot find Annotation Cache for class " + className);
        }
        return nonNullPositions;
      }
      Map<Integer, Set<String>> methodArgAnnotations = argAnnotCache.get(className).get(methodSign);
      if (methodArgAnnotations == null) {
        if (VERBOSE) {
          System.out.println(
              "[JI Warn] Cannnot find Annotation Cache entry for method "
                  + methodSign
                  + " in "
                  + className);
        }
        return nonNullPositions;
      }
      if (DEBUG) {
        System.out.println(
            "[JI Debug] Found Annotation Cache entry for method "
                + methodSign
                + " in "
                + className
                + " -- "
                + methodArgAnnotations.toString());
      }
      Set<Integer> jiNonNullParams = new LinkedHashSet<>();
      for (Map.Entry<Integer, Set<String>> annotationEntry : methodArgAnnotations.entrySet()) {
        if (annotationEntry.getValue().contains("javax.annotation.Nonnull")) {
          // Parameters are 0-indexed, vs. 1-indexed in WALA
          jiNonNullParams.add(annotationEntry.getKey() - 1);
        }
      }
      if (DEBUG) {
        System.out.println("[JI DEBUG] Nonnull params  : " + jiNonNullParams.toString());
      }
      return Sets.union(nonNullPositions, jiNonNullParams).immutableCopy();
    } catch (ClassNotFoundException cnfe) {
      if (VERBOSE) {
        System.out.println("[JI Warn] Cannnot find Class " + className);
      }
      return nonNullPositions;
    } catch (IOException e) {
      throw new Error(e);
    }
  }

  private void parseStubStream(
      InputStream stubxInputStream,
      String stubxLocation,
      Map<String, Map<Integer, Set<String>>> argAnnotations)
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
      in.readInt(); // String methodSig = strings[in.readInt()];
      in.readInt(); // String annotation = strings[in.readInt()];
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
            "[JI DEBUG] methodSign: "
                + methodSig
                + ", argNum: "
                + argNum
                + ", annotation: "
                + annotation);
      }
      if (!argAnnotations.containsKey(methodSig)) {
        argAnnotations.put(methodSig, new LinkedHashMap<>());
      }
      if (!argAnnotations.get(methodSig).containsKey(argNum)) {
        argAnnotations.get(methodSig).put(argNum, new LinkedHashSet<>());
      }
      argAnnotations.get(methodSig).get(argNum).add(annotation);
    }
  }
}
