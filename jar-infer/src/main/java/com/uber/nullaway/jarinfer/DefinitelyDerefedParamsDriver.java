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
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.strings.Atom;
import com.ibm.wala.util.strings.ImmutableByteArray;
import com.ibm.wala.util.strings.UTF8Convert;
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
  public static Set<String> run(
      String classFileDir, String mainClass, String targetMethod, String targetMethodSignature)
      throws IOException, ClassHierarchyException, IllegalArgumentException {
    long start = System.currentTimeMillis();
    AnalysisScope scope = AnalysisScopeReader.makePrimordialScope(null);
    AnalysisScopeReader.addClassPathToScope(classFileDir, scope, ClassLoaderReference.Application);
    AnalysisOptions options = new AnalysisOptions(scope, null);
    AnalysisCache cache = new AnalysisCacheImpl();
    IClassHierarchy cha = ClassHierarchyFactory.make(scope);
    Warnings.clear();

    MethodReference method =
        scope.findMethod(
            AnalysisScope.APPLICATION,
            mainClass,
            Atom.findOrCreateUnicodeAtom(targetMethod),
            new ImmutableByteArray(UTF8Convert.toUTF8(targetMethodSignature)));
    Preconditions.checkNotNull(method, "method not found");
    IMethod imethod = cha.resolveMethod(method);
    Preconditions.checkNotNull(imethod, "imethod not found");
    IR ir = cache.getIRFactory().makeIR(imethod, Everywhere.EVERYWHERE, options.getSSAOptions());
    System.out.println(ir);

    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
    DefinitelyDerefedParams derefedParamsFinder =
        new DefinitelyDerefedParams(imethod, ir, cfg, cha);
    Set<String> result = derefedParamsFinder.analyze();

    long end = System.currentTimeMillis();
    System.out.println("done");
    System.out.println("took " + (end - start) + "ms");
    System.out.println("definitely-derefereced paramters: " + result.toString());

    return result;
  }

  /*
   * Check set equality of results with expected results
   *
   */
  public boolean verify(Set<String> result, Set<String> expected) {
    for (String var : result) {
      if (!expected.remove(var)) return false;
    }
    return expected.isEmpty();
  }
}
