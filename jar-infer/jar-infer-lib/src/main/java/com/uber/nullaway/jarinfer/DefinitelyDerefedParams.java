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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cfg.ExceptionPrunedCFG;
import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.GraphUtil;
import com.ibm.wala.util.graph.dominators.Dominators;
import com.ibm.wala.util.graph.impl.GraphInverter;
import com.ibm.wala.util.graph.traverse.DFS;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * /** Identify definitely-dereferenced function parameters
 *
 * @version v0.1 Basic analysis that identifies function parameter dereferences in BBs that
 *     post-dominate the exit node.
 */
public class DefinitelyDerefedParams {
  private static final boolean DEBUG = false;
  private boolean USE_EXTENDED_APPROACH = true;

  private static void LOG(boolean cond, String tag, String msg) {
    if (cond) {
      System.out.println("[JI " + tag + "] " + msg);
    }
  }

  private final IMethod method;
  private final IR ir;

  // the exploded control-flow graph without exceptional edges
  private final ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg;
  private PrunedCFG<SSAInstruction, ISSABasicBlock> prunedCFG;

  /** List of null test APIs and the parameter position. */
  private static final ImmutableMap<String, Integer> NULL_TEST_APIS =
      new ImmutableMap.Builder<String, Integer>()
          .put(
              "com.google.common.base.Preconditions.checkNotNull(Ljava/lang/Object;)Ljava/lang/Object;",
              0)
          .put("java.util.Objects.requireNonNull(Ljava/lang/Object;)Ljava/lang/Object;", 0)
          .put("org.junit.Assert.assertNotNull(Ljava/lang/Object;)V", 0)
          .put("org.junit.Assert.assertNotNull(Ljava/lang/String;Ljava/lang/Object;)V", 1)
          .put("org.junit.jupiter.api.Assertions.assertNotNull(Ljava/lang/Object;)V", 0)
          .put(
              "org.junit.jupiter.api.Assertions.assertNotNull(Ljava/lang/Object;Ljava/lang/String;)V",
              1)
          .put(
              "org.junit.jupiter.api.Assertions.assertNotNull(Ljava/lang/Object;Ljava/util/function/Supplier<String>;)V",
              1)
          .build();

  /**
   * The constructor for the analysis class.
   *
   * @param method The target method of the analysis.
   * @param ir The IR code for the target method.
   * @param cfg The Control Flow Graph of the target method.
   */
  DefinitelyDerefedParams(
      IMethod method, IR ir, ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg) {
    this.method = method;
    this.ir = ir;
    this.cfg = cfg;
    prunedCFG = null;
  }

  /**
   * This is the core analysis that identifies definitely-dereferenced parameters.
   *
   * @return The ordinal indices of formal parameters that are definitely-dereferenced.
   */
  Set<Integer> analyze() {
    // Get ExceptionPrunedCFG
    LOG(DEBUG, "DEBUG", "@ " + method.getSignature());
    prunedCFG = ExceptionPrunedCFG.make(cfg);
    // In case the only control flows are exceptional, simply return.
    if (prunedCFG.getNumberOfNodes() == 2
        && prunedCFG.containsNode(cfg.entry())
        && prunedCFG.containsNode(cfg.exit())
        && GraphUtil.countEdges(prunedCFG) == 0) {
      return new HashSet<>();
    }

    // Get number of params and value number of first param
    int numParam = ir.getSymbolTable().getNumberOfParameters();
    int firstParamIndex =
        method.isStatic() ? 1 : 2; // 1-indexed; v1 is 'this' for non-static methods

    Set<Integer> derefedParamList;
    if (USE_EXTENDED_APPROACH) {
      derefedParamList = computeDerefParamList(numParam, firstParamIndex);
    } else {
      derefedParamList = computeDerefParamListUsingPDom(numParam, firstParamIndex);
    }
    return derefedParamList;
  }

  private Set<Integer> computeDerefParamList(int numParam, int firstParamIndex) {
    Set<Integer> derefedParamList = new HashSet<>();
    Map<ISSABasicBlock, Set<Integer>> blockToDerefSetMap = new HashMap<>();

    // find which params are treated as being non-null inside each basic block
    prunedCFG.forEach(
        basicBlock -> {
          Set<Integer> derefParamSet = new HashSet<>();
          blockToDerefSetMap.put(basicBlock, derefParamSet);
          checkForUseOfParams(derefParamSet, numParam, firstParamIndex, basicBlock);
        });

    // For each param p, if no path exists from the entry block to the exit block which traverses
    // only basic blocks which do not require p being non-null (e.g. by dereferencing it), then
    // mark p as @NonNull
    for (int i = firstParamIndex; i <= numParam; i++) {
      final Integer param = i - 1;
      if (!DFS.getReachableNodes(
              prunedCFG,
              ImmutableList.of(prunedCFG.entry()),
              basicBlock -> !blockToDerefSetMap.get(basicBlock).contains(param))
          .contains(prunedCFG.exit())) {
        derefedParamList.add(param);
      }
    }
    return derefedParamList;
  }

  private Set<Integer> computeDerefParamListUsingPDom(int numParam, int firstParamIndex) {
    Set<Integer> derefedParamList = new HashSet<>();
    // Get Dominator Tree
    LOG(DEBUG, "DEBUG", "\tbuilding dominator tree...");
    Graph<ISSABasicBlock> domTree = Dominators.make(prunedCFG, prunedCFG.entry()).dominatorTree();
    // Get Post-dominator Tree
    Graph<ISSABasicBlock> pdomTree = GraphInverter.invert(domTree);
    LOG(DEBUG, "DEBUG", "post-dominator tree:" + pdomTree.toString());
    // Note: WALA creates a single dummy exit node. Multiple exits points will never post-dominate
    // this exit node. (?)
    // TODO: [v0.2] Need data-flow analysis for dereferences on all paths
    // Walk from exit node in post-dominator tree and check for use of params
    LOG(DEBUG, "DEBUG", "\tfinding dereferenced params...");
    ArrayList<ISSABasicBlock> nodeQueue = new ArrayList<ISSABasicBlock>();
    nodeQueue.add(prunedCFG.exit());
    LOG(DEBUG, "DEBUG", "param value numbers : " + firstParamIndex + " ... " + numParam);
    while (!nodeQueue.isEmpty()) {
      ISSABasicBlock node = nodeQueue.get(0);
      nodeQueue.remove(node);
      // check for use of params
      checkForUseOfParams(derefedParamList, numParam, firstParamIndex, node);
      for (ISSABasicBlock succ : Iterator2Iterable.make(pdomTree.getSuccNodes(node))) {
        nodeQueue.add(succ);
      }
    }
    LOG(DEBUG, "DEBUG", "\tdone...");
    return derefedParamList;
  }

  private void checkForUseOfParams(
      Set<Integer> derefedParamList, int numParam, int firstParamIndex, ISSABasicBlock node) {
    if (!node.isEntryBlock() && !node.isExitBlock()) { // entry and exit are dummy basic blocks
      LOG(DEBUG, "DEBUG", ">> bb: " + node.getNumber());
      // Iterate over all instructions in BB
      for (int i = node.getFirstInstructionIndex(); i <= node.getLastInstructionIndex(); i++) {
        SSAInstruction instr = ir.getInstructions()[i];
        if (instr == null) { // Some instructions are null (padding NoOps)
          continue;
        }
        LOG(DEBUG, "DEBUG", "\tinst: " + instr.toString());
        int derefValueNumber = -1;
        if (instr instanceof SSAGetInstruction && !((SSAGetInstruction) instr).isStatic()) {
          derefValueNumber = ((SSAGetInstruction) instr).getRef();
        } else if (instr instanceof SSAPutInstruction && !((SSAPutInstruction) instr).isStatic()) {
          derefValueNumber = ((SSAPutInstruction) instr).getRef();
        } else if (instr instanceof SSAAbstractInvokeInstruction) {
          SSAAbstractInvokeInstruction callInst = (SSAAbstractInvokeInstruction) instr;
          String sign = callInst.getDeclaredTarget().getSignature();
          if (((SSAAbstractInvokeInstruction) instr).isStatic()) {
            // All supported Null testing APIs are static methods
            if (NULL_TEST_APIS.containsKey(sign)) {
              derefValueNumber = callInst.getUse(NULL_TEST_APIS.get(sign));
            }
          } else {
            Preconditions.checkArgument(
                !NULL_TEST_APIS.containsKey(sign),
                "Add support for non-static NULL_TEST_APIS : " + sign);
            derefValueNumber = ((SSAAbstractInvokeInstruction) instr).getReceiver();
          }
        }
        if (derefValueNumber >= firstParamIndex && derefValueNumber <= numParam) {
          LOG(DEBUG, "DEBUG", "\t\tderefed param : " + derefValueNumber);
          // Translate from WALA 1-indexed params, to 0-indexed
          derefedParamList.add(derefValueNumber - 1);
        }
      }
    }
  }

  public enum NullnessHint {
    UNKNOWN,
    NULLABLE,
    NONNULL
  }

  /**
   * This is the nullability analysis for the method return value.
   *
   * @return NullnessHint The inferred nullness type for the method return value.
   */
  NullnessHint analyzeReturnType() {
    if (method.getReturnType().isPrimitiveType()) {
      LOG(DEBUG, "DEBUG", "Skipping method with primitive return type: " + method.getSignature());
      return NullnessHint.UNKNOWN;
    }
    LOG(DEBUG, "DEBUG", "@ Return type analysis for: " + method.getSignature());
    // Get ExceptionPrunedCFG
    if (prunedCFG == null) {
      prunedCFG = ExceptionPrunedCFG.make(cfg);
    }
    // In case the only control flows are exceptional, simply return.
    if (prunedCFG.getNumberOfNodes() == 2
        && prunedCFG.containsNode(cfg.entry())
        && prunedCFG.containsNode(cfg.exit())
        && GraphUtil.countEdges(prunedCFG) == 0) {
      return NullnessHint.UNKNOWN;
    }
    for (ISSABasicBlock bb : prunedCFG.getNormalPredecessors(prunedCFG.exit())) {
      for (int i = bb.getFirstInstructionIndex(); i <= bb.getLastInstructionIndex(); i++) {
        SSAInstruction instr = ir.getInstructions()[i];
        if (instr instanceof SSAReturnInstruction) {
          SSAReturnInstruction retInstr = (SSAReturnInstruction) instr;
          if (ir.getSymbolTable().isNullConstant(retInstr.getResult())) {
            LOG(DEBUG, "DEBUG", "Nullable return in method: " + method.getSignature());
            return NullnessHint.NULLABLE;
          }
        }
      }
    }
    return NullnessHint.UNKNOWN;
  }
}
