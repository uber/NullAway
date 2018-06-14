/**
 * **************************************************************************** Copyright (c) 2018
 * Uber Technologies Inc.
 *
 * <p>Contributors: Uber Technologies Inc.
 * ****************************************************************************
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

/*
 * Identify definitely-dereferenced function parameters
 * v0.1
 * Basic analysis that identifies function parameter dereferences in BBs that post-dominate the exit node.
 *
 * @author subarno
 */

public class definitelyDerefedParams {

  private final IMethod method;
  private final IR ir;

  // the exploded control-flow graph without exceptional edges
  private final ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg;

  // used to resolve references to fields in putstatic instructions
  private final IClassHierarchy cha;

  private static final boolean VERBOSE = true;

  public definitelyDerefedParams(
      IMethod method,
      IR ir,
      ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg,
      IClassHierarchy cha) {
    this.method = method;
    this.ir = ir;
    this.cfg = cfg;
    this.cha = cha;
  }

  /*
   * run the analysis
   *
   * @return the list of definitely-dereferenced function parameters
   */

  public Set<String> analyze() {
    // Get ExceptionPrunedCFG
    System.out.println("pruning exceptional edges in CFG...");
    PrunedCFG<SSAInstruction, ISSABasicBlock> prunedCFG = ExceptionPrunedCFG.make(cfg);
    if (prunedCFG.getNumberOfNodes() == 2
        && prunedCFG.containsNode(cfg.entry())
        && prunedCFG.containsNode(cfg.exit())
        && GraphUtil.countEdges(prunedCFG) == 0) {
      return null;
    }
    // Get Dominator Tree
    System.out.println("building dominator tree...");
    Graph<ISSABasicBlock> domTree =
        new DominanceFrontiers<>(prunedCFG, prunedCFG.entry()).dominatorTree();
    // Get Post-dominator Tree
    Graph<ISSABasicBlock> pdomTree = GraphInverter.invert(domTree);
    if (VERBOSE) {
      System.out.println("pdom: " + pdomTree.toString());
    }
    // Note: WALA creates a single 'dummy' exit node. Multiple exits points will never post-dominate
    // this exit node. (?)
    // TODO: [v0.2] Need data-flow analysis for dereferences on all paths
    // Walk from exit node in post-dominator tree and check for use of params
    ArrayList<ISSABasicBlock> nodeQueue = new ArrayList<ISSABasicBlock>();
    nodeQueue.add(prunedCFG.exit());
    // Get number of params and value number of first param
    // v1 is 'this' only for non-static methods
    Set<String> derefedParamList = new HashSet<String>();
    int numParam = ir.getSymbolTable().getNumberOfParameters();
    int firstParamIndex = (method.isStatic()) ? 1 : 2;
    if (VERBOSE) {
      System.out.println("param value numbers : " + firstParamIndex + " ... " + numParam);
    }
    while (!nodeQueue.isEmpty()) {
      ISSABasicBlock node = nodeQueue.get(0);
      nodeQueue.remove(node);
      // check for use of params
      if (!node.isExitBlock() && !node.isEntryBlock()) {
        if (VERBOSE) {
          System.out.println(">> bb: " + node.getNumber());
        }
        // Iterate over all instructions in BB
        for (int i = node.getFirstInstructionIndex(); i <= node.getLastInstructionIndex(); i++) {
          SSAInstruction instr = ir.getInstructions()[i];
          if (instr == null) continue; // Some instructions are null (?)
          if (VERBOSE) {
            System.out.println("\tinst: " + instr.toString());
          }
          if ((instr instanceof SSAGetInstruction && !((SSAGetInstruction) instr).isStatic())
              || (instr instanceof SSAPutInstruction && !((SSAPutInstruction) instr).isStatic())
              || instr instanceof SSAAbstractInvokeInstruction) {
            int derefValueNumber = -1;
            if (instr instanceof SSAGetInstruction) {
              derefValueNumber = ((SSAGetInstruction) instr).getRef();
            } else if (instr instanceof SSAPutInstruction) {
              derefValueNumber = ((SSAPutInstruction) instr).getRef();
            } else if (instr instanceof SSAAbstractInvokeInstruction) {
              derefValueNumber = ((SSAAbstractInvokeInstruction) instr).getReceiver();
            }
            if (derefValueNumber >= firstParamIndex && derefValueNumber <= numParam) {
              if (VERBOSE) {
                System.out.println("\t\tderefed param : " + derefValueNumber);
              }
              derefedParamList.add(ir.getSymbolTable().getValueString(derefValueNumber));
            }
          }
        }
      }
      for (ISSABasicBlock succ : Iterator2Iterable.make(pdomTree.getSuccNodes(node))) {
        nodeQueue.add(succ);
      }
    }
    return derefedParamList;
  }
}
