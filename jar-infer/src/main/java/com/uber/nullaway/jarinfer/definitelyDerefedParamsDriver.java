package com.uber.nullaway.jarinfer;

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.examples.util.ExampleUtil;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.io.CommandLine;
import com.ibm.wala.util.strings.Atom;
import com.ibm.wala.util.strings.ImmutableByteArray;
import com.ibm.wala.util.strings.UTF8Convert;
import com.ibm.wala.util.warnings.Warnings;
import java.io.IOException;
import java.util.*;
import org.junit.Assert;

/*
 * Driver for running {@link definitelyDerefedParams}
 */
public class definitelyDerefedParamsDriver {

  public static final String SCOPE_FILE_PATH = "./tests/tests.scope.txt";
  public static final String SCOPE_MAIN_CLASS = "Ltoys/Main";
  public static final String TARGET_METHOD = "test";
  public static final String TARGET_METHOD_SIGN = "(Ljava/lang/String;Ltoys/Foo;Ltoys/Bar;)V";

  /*
   * Usage: definitelyDerefedParamsDriver -scopeFile file_path -mainClass class_name
   *
   * Uses main() method of class_name as entrypoint.
   *
   * @throws IOException
   * @throws ClassHierarchyException
   * @throws CallGraphBuilderCancelException
   * @throws IllegalArgumentException
   */

  public static void main(String[] args)
      throws IOException, ClassHierarchyException, IllegalArgumentException,
          CallGraphBuilderCancelException {
    long start = System.currentTimeMillis();
    Properties p = CommandLine.parse(args);
    String scopeFile = p.getProperty("scopeFile");
    if (scopeFile == null) {
      scopeFile = SCOPE_FILE_PATH;
      // throw new IllegalArgumentException("must specify scope file");
    }
    String mainClass = p.getProperty("mainClass");
    if (mainClass == null) {
      mainClass = SCOPE_MAIN_CLASS;
      // throw new IllegalArgumentException("must specify main class");
    }

    AnalysisScope scope =
        AnalysisScopeReader.readJavaScope(
            scopeFile, null, definitelyDerefedParamsDriver.class.getClassLoader());
    AnalysisOptions options = new AnalysisOptions(scope, null);
    AnalysisCache cache = new AnalysisCacheImpl();
    ExampleUtil.addDefaultExclusions(scope);
    IClassHierarchy cha = ClassHierarchyFactory.make(scope);
    System.out.println(cha.getNumberOfClasses() + " classes");
    //	    System.out.println(Warnings.asString());
    Warnings.clear();

    MethodReference method =
        scope.findMethod(
            AnalysisScope.APPLICATION,
            SCOPE_MAIN_CLASS,
            Atom.findOrCreateUnicodeAtom(TARGET_METHOD),
            new ImmutableByteArray(UTF8Convert.toUTF8(TARGET_METHOD_SIGN)));
    Assert.assertNotNull("method not found", method);
    IMethod imethod = cha.resolveMethod(method);
    Assert.assertNotNull("imethod not found", imethod);
    IR ir = cache.getIRFactory().makeIR(imethod, Everywhere.EVERYWHERE, options.getSSAOptions());
    System.out.println(ir);

    ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg = ir.getControlFlowGraph();
    //          ExplodedControlFlowGraph ecfg = ExplodedControlFlowGraph.make(ir);

    //
    //	    Iterable<Entrypoint> entrypoints = Util.makeMainEntrypoints(scope, cha, mainClass);
    //	    options.setEntrypoints(entrypoints);
    //	    options.setReflectionOptions(ReflectionOptions.NONE);
    //
    //	    AnalysisCache cache = new AnalysisCacheImpl();
    //	    // other builders can be constructed with different Util methods
    //		  CallGraphBuilder builder = Util.makeZeroOneContainerCFABuilder(options, cache, cha,
    // scope);
    //		  System.out.println("building call graph...");
    //		  CallGraph cg = builder.makeCallGraph(options, null);
    //
    //          ExplodedInterproceduralCFG icfg = ExplodedInterproceduralCFG.make(cg);
    definitelyDerefedParams derefedParamsFinder =
        new definitelyDerefedParams(imethod, ir, cfg, cha);
    Set<String> result = derefedParamsFinder.analyze();

    long end = System.currentTimeMillis();
    System.out.println("done");
    System.out.println("took " + (end - start) + "ms");
    System.out.println("definitely-derefereced paramters: " + result.toString());
    //		  System.out.println(CallGraphStats.getStats(cg));
  }
}
