/*
 * Copyright (C) 2018. Uber Technologies
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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IClassLoader;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.warnings.Warnings;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

/*
 * Driver for running {@link DefinitelyDerefedParams}
 */
public class DefinitelyDerefedParamsDriver {
  private static String aStubXPath = "./build/reports/tests/test.astubx";
  /*
   * Usage: DefinitelyDerefedParamsDriver ( path, package_name, [jar_flag])
   * path: jar file OR directory containing class files
   * @throws IOException
   * @throws ClassHierarchyException
   * @throws IllegalArgumentException
   */
  public static HashMap<String, Set<Integer>> run(String path, String pkgName, boolean isJar)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    if (isJar) {
      // Extract jar contents
      // link: https://www.javaworld.com/article/2077548
      Preconditions.checkArgument(path.endsWith(".jar"), "invalid jar path!");
      String jarDir = path.substring(0, path.lastIndexOf('.'));
      System.out.println("extracting " + path + "...");
      java.util.jar.JarFile jar = new java.util.jar.JarFile(path);
      java.util.Enumeration enumEntries = jar.entries();
      while (enumEntries.hasMoreElements()) {
        java.util.jar.JarEntry file = (java.util.jar.JarEntry) enumEntries.nextElement();
        java.io.File f = new java.io.File(jarDir + java.io.File.separator + file.getName());
        if (file.isDirectory()) {
          continue;
        }
        f.getParentFile().mkdirs();
        java.io.InputStream is = jar.getInputStream(file);
        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
        while (is.available() > 0) {
          fos.write(is.read());
        }
        fos.close();
        is.close();
      }
      jar.close();
      path = jarDir;
      aStubXPath =
          jarDir
              + java.io.File.separator
              + "META-INF"
              + java.io.File.separator
              + pkgName
              + ".astubx";
    }
    return run(path, pkgName);
  }

  public static HashMap<String, Set<Integer>> run(String path, String pkgName)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    long start = System.currentTimeMillis();
    AnalysisScope scope = AnalysisScopeReader.makePrimordialScope(null);
    AnalysisScopeReader.addClassPathToScope(path, scope, ClassLoaderReference.Application);
    AnalysisOptions options = new AnalysisOptions(scope, null);
    AnalysisCache cache = new AnalysisCacheImpl();
    IClassHierarchy cha = ClassHierarchyFactory.make(scope);
    Warnings.clear();
    HashMap<IMethod, Set<Integer>> map_mtd_result = new HashMap<IMethod, Set<Integer>>();
    HashMap<String, Set<Integer>> map_str_result = new HashMap<String, Set<Integer>>();

    // Iterate over all classes:methods in the 'Application' and 'Extension' class loaders
    for (IClassLoader cldr : cha.getLoaders()) {
      if (!cldr.getName().toString().equals("Primordial")) {
        for (IClass cls : Iterator2Iterable.make(cldr.iterateAllClasses())) {
          // Only process classes in specified classpath
          if (cls.getName().toString().startsWith(pkgName)) {
            for (IMethod mtd : Iterator2Iterable.make(cls.getAllMethods().iterator())) {
              if (!mtd.getDeclaringClass()
                  .getClassLoader()
                  .getName()
                  .toString()
                  .equals("Primordial")) {
                // some Application classes are Primordial (why?)
                Preconditions.checkNotNull(mtd, "method not found");
                IR ir =
                    cache
                        .getIRFactory()
                        .makeIR(mtd, Everywhere.EVERYWHERE, options.getSSAOptions());
                ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
                Set<Integer> result = new DefinitelyDerefedParams(mtd, ir, cfg, cha).analyze();
                if (!result.isEmpty()) {
                  map_mtd_result.put(mtd, result);
                  map_str_result.put(mtd.getSignature(), result);
                }
              }
            }
          }
        }
      }
    }
    long end = System.currentTimeMillis();
    System.out.println("-----\ndone\ntook " + (end - start) + "ms");
    System.out.println("definitely-derefereced paramters: " + map_str_result.toString());

    writeJarModel(cha, map_mtd_result);
    return map_str_result;
  }

  /*
   * Write inferred Jar model in astubx format
   *
   */
  private static void writeJarModel(
      IClassHierarchy cha, HashMap<IMethod, Set<Integer>> map_mtd_result) {
    try {
      File aStubXFile = new File(aStubXPath);
      aStubXFile.createNewFile();
      DataOutputStream out = new DataOutputStream(new FileOutputStream(aStubXFile, false));
      Map<String, String> importedAnnotations =
          new HashMap<String, String>() {
            {
              put("@Nonnull", "javax.annotation.Nonnull");
            }
          };
      Map<String, Set<String>> packageAnnotations = new HashMap<>();
      Map<String, Set<String>> typeAnnotations = new HashMap<>();
      Map<String, MethodAnnotationsRecord> methodRecords = new LinkedHashMap<>();

      for (Map.Entry<IMethod, Set<Integer>> entry : map_mtd_result.entrySet()) {
        IMethod mtd = entry.getKey();
        Set<Integer> ddParams = entry.getValue();
        if (ddParams.isEmpty()) continue;
        Map<Integer, ImmutableSet<String>> argAnnotation =
            new HashMap<Integer, ImmutableSet<String>>();
        for (Integer param : ddParams) {
          argAnnotation.put(param, ImmutableSet.of("@Nonnull"));
        }
        methodRecords.put(
            getSignature(mtd),
            new MethodAnnotationsRecord(ImmutableSet.of(), ImmutableMap.copyOf(argAnnotation)));
      }
      StubxWriter.write(
          out, importedAnnotations, packageAnnotations, typeAnnotations, methodRecords);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /*
   * Get astubx style method signature
   * {FullyQualifiedEnclosingType}: {UnqualifiedMethodReturnType} {methodName} ([{UnqualifiedArgumentType}*])
   * TODO: handle generics and inner classes
   */
  private static String getSignature(IMethod mtd) {
    final Map<String, String> mapFullTypeName =
        new HashMap<String, String>() {
          {
            put("B", "byte");
            put("C", "char");
            put("D", "double");
            put("F", "float");
            put("I", "int");
            put("J", "long");
            put("S", "short");
            put("Z", "boolean");
          }
        };
    String classType =
        mtd.getDeclaringClass().getName().toString().replaceAll("/", "\\.").substring(1);
    String returnType = mtd.isInit() ? "" : mtd.getReturnType().getName().toString().split("<")[0];
    if (returnType.startsWith("L")) {
      returnType = returnType.replaceAll("/", "\\.").substring(1);
    } else {
      returnType = mapFullTypeName.get(returnType);
    }
    String argTypes = "";
    for (int argi = 0; argi < mtd.getNumberOfParameters(); argi++) {
      if (mtd.getParameterType(argi).isArrayType()) {
        argTypes += "Array";
      } else {
        String argType = mtd.getParameterType(argi).getName().toString().split("<")[0].substring(1);
        argTypes += argType.substring(argType.lastIndexOf('/') + 1);
      }
      if (argi < mtd.getNumberOfParameters() - 1) {
        argTypes += ", ";
      }
    }
    return classType
        + ":"
        + (returnType == null ? "" : returnType + " ")
        + mtd.getName().toString()
        + "("
        + argTypes
        + ")";
  }
}
