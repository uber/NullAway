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

import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cfg.ExceptionPrunedCFG;
import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.GraphUtil;
import com.ibm.wala.util.graph.dominators.DominanceFrontiers;
import com.ibm.wala.util.graph.impl.GraphInverter;
import java.util.*;

/**
 * /** Identify definitely-dereferenced function parameters
 *
 * @version v0.1 Basic analysis that identifies function parameter dereferences in BBs that
 *     post-dominate the exit node.
 */
public class DefinitelyDerefedParams {
  private final IMethod method;
  private final IR ir;

  // the exploded control-flow graph without exceptional edges
  private final ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg;

  // used to resolve references to fields in putstatic instructions
  private final IClassHierarchy cha;

  private static final boolean VERBOSE = false;

  /**
   * The constructor for the analysis class.
   *
   * @param method The target method of the analysis.
   * @param ir The IR code for the target method.
   * @param cfg The Control Flow Graph of the target method.
   * @param cha The Class Hierarchy
   */
  public DefinitelyDerefedParams(
      IMethod method,
      IR ir,
      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
      IClassHierarchy cha) {
    this.method = method;
    this.ir = ir;
    this.cfg = cfg;
    this.cha = cha;
  }

  /**
   * This method runs the core analysis that identifies definitely-dereferenced parameters.
   *
   * @return Set<Integer> The ordinal indices of formal parameters that are definitely-dereferenced.
   */
  public Set<Integer> analyze() {
    // Get ExceptionPrunedCFG
    System.out.println("@ " + method.getSignature());
    System.out.println("   exception pruning CFG...");
    Set<Integer> derefedParamList = new HashSet<Integer>();
    PrunedCFG<SSAInstruction, ISSABasicBlock> prunedCFG = ExceptionPrunedCFG.make(cfg);
    // In case the only control flows are exceptional, simply return.
    if (prunedCFG.getNumberOfNodes() == 2
        && prunedCFG.containsNode(cfg.entry())
        && prunedCFG.containsNode(cfg.exit())
        && GraphUtil.countEdges(prunedCFG) == 0) {
      return derefedParamList;
    }
    // Get Dominator Tree
    System.out.println("   building dominator tree...");
    Graph<ISSABasicBlock> domTree =
        new DominanceFrontiers<>(prunedCFG, prunedCFG.entry()).dominatorTree();
    // Get Post-dominator Tree
    Graph<ISSABasicBlock> pdomTree = GraphInverter.invert(domTree);
    if (VERBOSE) {
      System.out.println("pdom: " + pdomTree.toString());
    }
    // Note: WALA creates a single dummy exit node. Multiple exits points will never post-dominate
    // this exit node. (?)
    // TODO: [v0.2] Need data-flow analysis for dereferences on all paths
    // Walk from exit node in post-dominator tree and check for use of params
    System.out.println("   finding dereferenced params...");
    ArrayList<ISSABasicBlock> nodeQueue = new ArrayList<ISSABasicBlock>();
    nodeQueue.add(prunedCFG.exit());
    // Get number of params and value number of first param
    int numParam = ir.getSymbolTable().getNumberOfParameters();
    int firstParamIndex =
        (method.isStatic()) ? 1 : 2; // 1-indexed; v1 is 'this' only for non-static methods
    if (VERBOSE) {
      System.out.println("param value numbers : " + firstParamIndex + " ... " + numParam);
    }
    while (!nodeQueue.isEmpty()) {
      ISSABasicBlock node = nodeQueue.get(0);
      nodeQueue.remove(node);
      // check for use of params
      if (!node.isEntryBlock() && !node.isExitBlock()) { // entry and exit are dummy basic blocks
        if (VERBOSE) {
          System.out.println(">> bb: " + node.getNumber());
        }
        // Iterate over all instructions in BB
        for (int i = node.getFirstInstructionIndex(); i <= node.getLastInstructionIndex(); i++) {
          SSAInstruction instr = ir.getInstructions()[i];
          if (instr == null) continue; // Some instructions are null (padding NoOps)
          if (VERBOSE) {
            System.out.println("\tinst: " + instr.toString());
          }
          int derefValueNumber = -1;
          if (instr instanceof SSAGetInstruction && !((SSAGetInstruction) instr).isStatic()) {
            derefValueNumber = ((SSAGetInstruction) instr).getRef();
          } else if (instr instanceof SSAPutInstruction
              && !((SSAPutInstruction) instr).isStatic()) {
            derefValueNumber = ((SSAPutInstruction) instr).getRef();
          } else if (instr instanceof SSAAbstractInvokeInstruction
              && !((SSAAbstractInvokeInstruction) instr).isStatic()) {
            derefValueNumber = ((SSAAbstractInvokeInstruction) instr).getReceiver();
          }
          if (derefValueNumber >= firstParamIndex && derefValueNumber <= numParam) {
            if (VERBOSE) {
              System.out.println("\t\tderefed param : " + derefValueNumber);
            }
            // Translate from WALA 1-indexed params, to 0-indexed
            derefedParamList.add(derefValueNumber - 1);
          }
        }
      }
      for (ISSABasicBlock succ : Iterator2Iterable.make(pdomTree.getSuccNodes(node))) {
        nodeQueue.add(succ);
      }
    }
    System.out.println("   done...");
    return derefedParamList;
  }
}
