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
  /*
   * Usage: DefinitelyDerefedParamsDriver ( class_file _dir, package_name)
   *
   * @throws IOException
   * @throws ClassHierarchyException
   * @throws IllegalArgumentException
   */
  public static HashMap<String, Set<Integer>> run(String classFileDir, String pkgName)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    long start = System.currentTimeMillis();
    AnalysisScope scope = AnalysisScopeReader.makePrimordialScope(null);
    AnalysisScopeReader.addClassPathToScope(classFileDir, scope, ClassLoaderReference.Application);
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
                map_mtd_result.put(mtd, result);
                map_str_result.put(mtd.getSignature(), result);
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
    String astubxPath = "/Users/subarno/src/NullAway/jar-infer/build/reports/tests/test.astubx";
    try {
      File astubxFile = new File(astubxPath);
      astubxFile.createNewFile();
      DataOutputStream out = new DataOutputStream(new FileOutputStream(astubxFile, false));
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
    String mtd_sign =
        mtd.getDeclaringClass().getName().toString()
            + ": "
            + mtd.getReturnType().getName().toString()
            + " "
            + mtd.getName().toString()
            + "(";
    for (int argi = 0; argi < mtd.getNumberOfParameters(); argi++) {
      mtd_sign += mtd.getParameterType(argi).getName().toString() + ",";
    }
    mtd_sign = mtd_sign.substring(0, mtd_sign.length() - 1) + ")";
    System.out.println("@ mtd_sign: " + mtd_sign);
    return mtd_sign;
  }
}
