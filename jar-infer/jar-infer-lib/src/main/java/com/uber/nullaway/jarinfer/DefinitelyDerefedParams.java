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
import com.ibm.wala.cfg.ControlFlowGraph;
import com.ibm.wala.cfg.Util;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.ipa.cfg.ExceptionPrunedCFG;
import com.ibm.wala.ipa.cfg.PrunedCFG;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeBT.IConditionalBranchInstruction.Operator;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.ISSABasicBlock;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAAbstractThrowInstruction;
import com.ibm.wala.ssa.SSAConditionalBranchInstruction;
import com.ibm.wala.ssa.SSAGetInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.SSAReturnInstruction;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.GraphUtil;
import com.ibm.wala.util.graph.dominators.Dominators;
import com.ibm.wala.util.graph.impl.GraphInverter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * /** Identify definitely-dereferenced function parameters
 *
 * @version v0.1 Basic analysis that identifies function parameter dereferences in BBs that
 *     post-dominate the exit node.
 */
public class DefinitelyDerefedParams {
  private static final boolean DEBUG = false;

  // Flags to enable different analysis reasoning
  private static final boolean ENABLE_B2 = true;
  private static final boolean ENABLE_B3 = true;

  private static void LOG(boolean cond, String tag, String msg) {
    if (cond) System.out.println("[JI " + tag + "] " + msg);
  }

  private final IMethod method;
  private final IR ir;

  // the exploded control-flow graph without exceptional edges
  private final ControlFlowGraph<SSAInstruction, ISSABasicBlock> cfg;
  private PrunedCFG<SSAInstruction, ISSABasicBlock> prunedCFG;

  // used to resolve references to fields in putstatic instructions
  private final IClassHierarchy cha;

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
    prunedCFG = null;
  }

  /**
   * This is the core analysis that identifies definitely-dereferenced parameters.
   *
   * @return Set<Integer> The ordinal indices of formal parameters that are definitely-dereferenced.
   */
  public Set<Integer> analyze() {
    // Get ExceptionPrunedCFG
    LOG(DEBUG, "DEBUG", "@ " + method.getSignature());
    Set<Integer> derefedParamList = new HashSet<Integer>();
    Set<Integer> nullTestedThrowsList = new HashSet<Integer>();
    Set<Integer> nullUntestedDerefsList = new HashSet<Integer>();
    prunedCFG = ExceptionPrunedCFG.make(cfg);
    // In case the only control flows are exceptional, simply return.
    if (prunedCFG.getNumberOfNodes() == 2
        && prunedCFG.containsNode(cfg.entry())
        && prunedCFG.containsNode(cfg.exit())
        && GraphUtil.countEdges(prunedCFG) == 0) {
      return derefedParamList;
    }
    // Get Dominator Tree
    LOG(DEBUG, "DEBUG", "\tbuilding dominator tree...");
    Graph<ISSABasicBlock> fullDomTree = Dominators.make(cfg, cfg.entry()).dominatorTree();
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
    // Get number of params and value number of first param
    int numParam = ir.getSymbolTable().getNumberOfParameters();
    int firstParamIndex =
        (method.isStatic()) ? 1 : 2; // 1-indexed; v1 is 'this' for non-static methods
    LOG(DEBUG, "DEBUG", "param value numbers : " + firstParamIndex + " ... " + numParam);
    while (!nodeQueue.isEmpty()) {
      ISSABasicBlock node = nodeQueue.get(0);
      nodeQueue.remove(node);
      // check for use of params
      if (!node.isEntryBlock() && !node.isExitBlock()) { // entry and exit are dummy basic blocks
        LOG(DEBUG, "DEBUG", ">> bb: " + node.getNumber());
        // Iterate over all instructions in BB
        for (int i = node.getFirstInstructionIndex(); i <= node.getLastInstructionIndex(); i++) {
          SSAInstruction instr = ir.getInstructions()[i];
          if (instr == null) continue; // Some instructions are null (padding NoOps)
          LOG(DEBUG, "DEBUG", "\tinst: " + instr.toString());
          int derefValueNumber = -1;
          // A1 Analysis: Definitely dereferenced parameters
          if (instr instanceof SSAGetInstruction && !((SSAGetInstruction) instr).isStatic()) {
            derefValueNumber = ((SSAGetInstruction) instr).getRef();
          } else if (instr instanceof SSAPutInstruction
              && !((SSAPutInstruction) instr).isStatic()) {
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
            LOG(DEBUG, "DEBUG", "\t\tdefinitely derefed param : " + derefValueNumber);
            // Translate from WALA 1-indexed params, to 0-indexed
            derefedParamList.add(derefValueNumber - 1);
          }
          // ==================================================
          // B2 Analysis: Exception under parameter null test
          if (ENABLE_B2) {
            if (instr instanceof SSAConditionalBranchInstruction
                && ((SSAConditionalBranchInstruction) instr).isObjectComparison()) {
              SSAConditionalBranchInstruction cond = (SSAConditionalBranchInstruction) instr;
              int paramValueNumber = -1;
              if (cond.getNumberOfUses() == 2) {
                if (ir.getSymbolTable().isNullConstant(cond.getUse(0)))
                  paramValueNumber = cond.getUse(1);
                else if (ir.getSymbolTable().isNullConstant(cond.getUse(1)))
                  paramValueNumber = cond.getUse(0);
              }
              if (paramValueNumber >= firstParamIndex && paramValueNumber <= numParam) {
                // Found parameter null check
                LOG(
                    DEBUG,
                    "DEBUG",
                    "\t\tnull testing param: " + paramValueNumber + "\t at: " + cond.toString());
                ISSABasicBlock nullTestBB = cfg.getBlockForInstruction(i);
                ISSABasicBlock nullTestedBodyEntry = null;
                ISSABasicBlock nullTestedBodyExit = null;
                ArrayList<SSAInstruction> throwInsts = new ArrayList<SSAInstruction>();
                if (cond.getOperator() == Operator.EQ) {
                  nullTestedBodyEntry = Util.getTakenSuccessor(cfg, nullTestBB);
                } else if (cond.getOperator() == Operator.NE) {
                  nullTestedBodyEntry = Util.getNotTakenSuccessor(cfg, nullTestBB);
                }
                // Find X s.t, cond dom X, nullTestedBodyEntry dom pre(X)
                for (ISSABasicBlock idom :
                    Iterator2Iterable.make(fullDomTree.getSuccNodes(nullTestBB))) {
                  if (cfg.hasEdge(nullTestBB, idom)) continue;
                  ArrayList<ISSABasicBlock> domTraverseQueue = new ArrayList<ISSABasicBlock>();
                  domTraverseQueue.add(nullTestedBodyEntry);
                  while (!domTraverseQueue.isEmpty()) {
                    ISSABasicBlock bb = domTraverseQueue.get(0);
                    domTraverseQueue.remove(bb);
                    for (int in = bb.getFirstInstructionIndex();
                        in <= bb.getLastInstructionIndex();
                        in++) {
                      SSAInstruction inst = ir.getInstructions()[in];
                      if (inst == null) continue;
                      if (inst instanceof SSAAbstractThrowInstruction) throwInsts.add(inst);
                    }
                    for (ISSABasicBlock succ : Iterator2Iterable.make(cfg.getSuccNodes(bb))) {
                      if (succ == idom) nullTestedBodyExit = bb;
                    }
                    for (ISSABasicBlock domsucc :
                        Iterator2Iterable.make(fullDomTree.getSuccNodes(bb))) {
                      domTraverseQueue.add(domsucc);
                    }
                  }
                  break;
                }
                for (SSAInstruction throwInst : throwInsts) {
                  ISSABasicBlock throwBB = cfg.getBlockForInstruction(throwInst.iindex);
                  if (hasPath(fullDomTree, throwBB, nullTestedBodyExit)) {
                    LOG(
                        DEBUG,
                        "DEBUG",
                        "\t\tthrown under null checked param: "
                            + paramValueNumber
                            + "\t at: "
                            + throwInst.toString());
                    // Translate from WALA 1-indexed params, to 0-indexed
                    nullTestedThrowsList.add(paramValueNumber - 1);
                  }
                }
              }
            }
          }
          // ==================================================
        }
      }
      for (ISSABasicBlock succ : Iterator2Iterable.make(pdomTree.getSuccNodes(node))) {
        nodeQueue.add(succ);
      }
    }
    // ==================================================
    // B3 Analysis: Parameter dereference without null test
    if (ENABLE_B3) {
      for (ISSABasicBlock bb : Iterator2Iterable.make(cfg.iterator())) {
        if (!bb.isEntryBlock() && !bb.isExitBlock()) {
          for (int i = bb.getFirstInstructionIndex(); i <= bb.getLastInstructionIndex(); i++) {
            int derefValueNumber = -1;
            SSAInstruction instr = ir.getInstructions()[i];
            if (instr == null) continue;
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
              SSAInstruction guardInst = null;
              ArrayList<ISSABasicBlock> pdomTraverseQueue = new ArrayList<ISSABasicBlock>();
              pdomTraverseQueue.add(bb);
              while (!pdomTraverseQueue.isEmpty()) {
                ISSABasicBlock pbb = pdomTraverseQueue.get(0);
                pdomTraverseQueue.remove(pbb);
                for (int in = pbb.getFirstInstructionIndex();
                    in <= pbb.getLastInstructionIndex();
                    in++) {
                  SSAInstruction inst = ir.getInstructions()[in];
                  if (inst == null) continue;
                  if (inst instanceof SSAConditionalBranchInstruction
                      && ((SSAConditionalBranchInstruction) inst).isObjectComparison()) {
                    SSAConditionalBranchInstruction cond = (SSAConditionalBranchInstruction) inst;
                    if (cond.getNumberOfUses() == 2) {
                      int paramValueNumber = -1;
                      if (ir.getSymbolTable().isNullConstant(cond.getUse(0)))
                        paramValueNumber = cond.getUse(1);
                      else if (ir.getSymbolTable().isNullConstant(cond.getUse(1)))
                        paramValueNumber = cond.getUse(0);
                      if (paramValueNumber == derefValueNumber) {
                        guardInst = inst;
                        break;
                      }
                    }
                  }
                }
                for (ISSABasicBlock pdomsucc : Iterator2Iterable.make(pdomTree.getSuccNodes(pbb))) {
                  pdomTraverseQueue.add(pdomsucc);
                }
              }
              if (guardInst == null) {
                LOG(
                    DEBUG,
                    "DEBUG",
                    "\t\tderefed w/o null check param: "
                        + derefValueNumber
                        + "\t at: "
                        + instr.toString());
                // Translate from WALA 1-indexed params, to 0-indexed
                nullUntestedDerefsList.add(derefValueNumber - 1);
              }
            }
          }
        }
      }
    }
    // ==================================================
    LOG(DEBUG, "DEBUG", "\tdone...");
    Set<Integer> nonnullParams = new HashSet<Integer>();
    nonnullParams.addAll(derefedParamList);
    nonnullParams.addAll(nullTestedThrowsList);
    nonnullParams.addAll(nullUntestedDerefsList);
    LOG(
        DEBUG,
        "DEBUG",
        "> method: "
            + method.getSignature()
            + "\n @Nonnull Parameters: "
            + nonnullParams.toString());
    return nonnullParams;
  }

  private static boolean hasPath(Graph<ISSABasicBlock> G, ISSABasicBlock S, ISSABasicBlock D) {
    ArrayList<ISSABasicBlock> queue = new ArrayList<ISSABasicBlock>();
    queue.add(S);
    while (!queue.isEmpty()) {
      ISSABasicBlock bb = queue.get(0);
      queue.remove(bb);
      if (bb == D) return true;
      for (ISSABasicBlock succ : Iterator2Iterable.make(G.getSuccNodes(bb))) queue.add(succ);
    }
    return false;
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
  public NullnessHint analyzeReturnType() {
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
    // A2 Analysis: Nullable return
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
