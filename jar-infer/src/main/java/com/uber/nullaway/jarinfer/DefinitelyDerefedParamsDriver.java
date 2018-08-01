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
import java.io.IOException;
import java.util.*;

/*
 * Driver for running {@link DefinitelyDerefedParams}
 */
public class DefinitelyDerefedParamsDriver {
  /*
   * Usage: DefinitelyDerefedParamsDriver ( class_file _dir, class_name, method_name, method_signature)
   *
   * @throws IOException
   * @throws ClassHierarchyException
   * @throws IllegalArgumentException
   */
  public static HashMap<String, Set<String>> run(String classFileDir)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    long start = System.currentTimeMillis();
    AnalysisScope scope = AnalysisScopeReader.makePrimordialScope(null);
    AnalysisScopeReader.addClassPathToScope(classFileDir, scope, ClassLoaderReference.Application);
    AnalysisOptions options = new AnalysisOptions(scope, null);
    AnalysisCache cache = new AnalysisCacheImpl();
    IClassHierarchy cha = ClassHierarchyFactory.make(scope);
    Warnings.clear();
    HashMap<String, Set<String>> result = new HashMap<String, Set<String>>();

    // Iterate over all classes:methods in the 'Application' and 'Extension' class loaders
    for (IClassLoader cldr : cha.getLoaders()) {
      if (!cldr.getName().toString().equals("Primordial")) {
        for (IClass cls : Iterator2Iterable.make(cldr.iterateAllClasses())) {
          for (IMethod mtd : Iterator2Iterable.make(cls.getAllMethods().iterator())) {
            if (!mtd.getDeclaringClass()
                .getClassLoader()
                .getName()
                .toString()
                .equals("Primordial")) {
              // some Application classes are Primordial (why?)
              Preconditions.checkNotNull(mtd, "method not found");
              System.out.println("@ " + mtd.toString());
              IR ir =
                  cache.getIRFactory().makeIR(mtd, Everywhere.EVERYWHERE, options.getSSAOptions());
              ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
              DefinitelyDerefedParams derefedParamsFinder =
                  new DefinitelyDerefedParams(mtd, ir, cfg, cha);
              result.put(mtd.toString(), derefedParamsFinder.analyze());
            }
          }
        }
      }
    }
    long end = System.currentTimeMillis();
    System.out.println("-----\ndone\ntook " + (end - start) + "ms");
    System.out.println("definitely-derefereced paramters: " + result.toString());

    return result;
  }

  /*
   * Check set equality of results with expected results
   *
   */
  public boolean verify(
      HashMap<String, Set<String>> result, HashMap<String, Set<String>> expected) {
    for (Map.Entry<String, Set<String>> entry : result.entrySet()) {
      String mtd_sign = entry.getKey();
      Set<String> ddParams = entry.getValue();
      if (ddParams.isEmpty()) continue;
      Set<String> xddParams = expected.get(mtd_sign);
      if (xddParams == null) return false;
      for (String var : ddParams) {
        if (!xddParams.remove(var)) return false;
      }
      if (!xddParams.isEmpty()) return false;
      expected.remove(mtd_sign);
    }
    return expected.isEmpty();
  }
}
