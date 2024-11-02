package com.uber.nullaway.handlers;

/*
 * Copyright (c) 2024 Uber Technologies, Inc.
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

import com.uber.nullaway.jarinfer.JarInferStubxProvider;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * A class responsible for caching annotation information extracted from stubx files.
 *
 * <p>This class provides mechanisms to cache annotations and retrieve them efficiently when needed.
 * It uses a nested map structure to store annotations, which are indexed by class name, method
 * signature, and argument index. It also stores a Map containing the indices for Nullable upper
 * bounds for generic type parameters.
 */
public class StubxCacheUtil {

  /**
   * The file magic number for version 1 .astubx files. It should be the first four bytes of any
   * compatible .astubx file.
   */
  private static final int VERSION_1_FILE_MAGIC_NUMBER = 481874642;

  private boolean DEBUG = false;
  private String logCaller = "";

  private void LOG(boolean cond, String tag, String msg) {
    if (cond) {
      System.out.println("[" + logCaller + " " + tag + "] " + msg);
    }
  }

  private static final int RETURN = -1;

  private final Map<String, Map<String, Map<Integer, Set<String>>>> argAnnotCache;

  private final Map<String, Integer> upperBoundCache;

  private final Set<String> nullMarkedClassesCache;

  /**
   * Initializes a new {@code StubxCacheUtil} instance.
   *
   * <p>This sets up the caches for argument annotations and upper bounds, sets the log caller, and
   * loads the stubx files.
   *
   * @param logCaller Identifier for logging purposes.
   */
  public StubxCacheUtil(String logCaller) {
    argAnnotCache = new LinkedHashMap<>();
    upperBoundCache = new HashMap<>();
    nullMarkedClassesCache = new HashSet<>();
    this.logCaller = logCaller;
    loadStubxFiles();
  }

  public Map<String, Integer> getUpperBoundCache() {
    return upperBoundCache;
  }

  public Set<String> getNullMarkedClassesCache() {
    return nullMarkedClassesCache;
  }

  public Map<String, Map<String, Map<Integer, Set<String>>>> getArgAnnotCache() {
    return argAnnotCache;
  }

  /**
   * Loads all stubx files discovered in the classpath. Stubx files are discovered via
   * implementations of {@link JarInferStubxProvider} loaded using a {@link ServiceLoader}
   */
  private void loadStubxFiles() {
    Iterable<JarInferStubxProvider> astubxProviders =
        ServiceLoader.load(JarInferStubxProvider.class, StubxCacheUtil.class.getClassLoader());
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

  public void parseStubStream(InputStream stubxInputStream, String stubxLocation)
      throws IOException {
    String[] strings;
    DataInputStream in = new DataInputStream(stubxInputStream);
    // Read and check the magic version number
    if (in.readInt() != VERSION_1_FILE_MAGIC_NUMBER) {
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
    // reading the NullMarked classes
    int numNullMarkedClasses = in.readInt();
    for (int i = 0; i < numNullMarkedClasses; i++) {
      this.nullMarkedClassesCache.add(strings[in.readInt()]);
    }
    // read the number of nullable upper bound entries
    int numClassesWithNullableUpperBounds = in.readInt();
    for (int i = 0; i < numClassesWithNullableUpperBounds; i++) {
      int numParams = in.readInt();
      for (int j = 0; j < numParams; j++) {
        cacheUpperBounds(strings[in.readInt()], in.readInt());
      }
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

  private void cacheUpperBounds(String className, Integer paramIndex) {
    upperBoundCache.put(className, paramIndex);
  }
}
