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

import com.ibm.wala.classLoader.IField;
import com.ibm.wala.dataflow.graph.AbstractMeetOperator;
import com.ibm.wala.dataflow.graph.BitVectorFramework;
import com.ibm.wala.dataflow.graph.BitVectorIdentity;
import com.ibm.wala.dataflow.graph.BitVectorIntersection;
import com.ibm.wala.dataflow.graph.BitVectorKillGen;
import com.ibm.wala.dataflow.graph.BitVectorSolver;
import com.ibm.wala.dataflow.graph.ITransferFunctionProvider;
import com.ibm.wala.fixpoint.BitVectorVariable;
import com.ibm.wala.fixpoint.UnaryOperator;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAPutInstruction;
import com.ibm.wala.ssa.analysis.ExplodedControlFlowGraph;
import com.ibm.wala.ssa.analysis.IExplodedBasicBlock;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.collections.HashMapFactory;
import com.ibm.wala.util.collections.Iterator2Iterable;
import com.ibm.wala.util.collections.ObjectArrayMapping;
import com.ibm.wala.util.intset.BitVector;
import com.ibm.wala.util.intset.OrdinalSetMapping;
import java.util.ArrayList;
import java.util.Map;

/**
 * Compute intra-procedural null data-flows, i.e., the values that are 'definitely null' at each
 * program point.
 */
public class IntraProcNullFlow {
  private static final boolean DEBUG = false;

  private static void LOG(boolean cond, String tag, String msg) {
    if (cond) System.out.println("[JI " + tag + "] " + msg);
  }

  private final ExplodedControlFlowGraph ecfg;
  private final IClassHierarchy cha;

  /**
   * maps the index of a putstatic IR instruction to a more compact numbering for use in bitvectors
   */
  private final OrdinalSetMapping<Integer> putInstrNumbering;

  /**
   * maps each static field to the numbers of the statements (in {@link #putInstrNumbering}) that
   * define it; used for kills in flow functions
   */
  private final Map<IField, BitVector> staticField2DefStatements = HashMapFactory.make();

  public IntraProcNullFlow(ExplodedControlFlowGraph ecfg, IClassHierarchy cha) {
    this.ecfg = ecfg;
    this.cha = cha;
    this.putInstrNumbering = numberPutStatics();
  }

  /** generate a numbering of the putstatic instructions */
  private OrdinalSetMapping<Integer> numberPutStatics() {
    ArrayList<Integer> putInstrs = new ArrayList<>();
    IR ir = ecfg.getIR();
    SSAInstruction[] instructions = ir.getInstructions();
    for (int i = 0; i < instructions.length; i++) {
      SSAInstruction instruction = instructions[i];
      if (instruction instanceof SSAPutInstruction
          && ((SSAPutInstruction) instruction).isStatic()) {
        SSAPutInstruction putInstr = (SSAPutInstruction) instruction;
        // instrNum is the number that will be assigned to this putstatic
        int instrNum = putInstrs.size();
        putInstrs.add(i);
        // also update the mapping of static fields to def'ing statements
        IField field = cha.resolveField(putInstr.getDeclaredField());
        assert field != null;
        BitVector bv = staticField2DefStatements.get(field);
        if (bv == null) {
          bv = new BitVector();
          staticField2DefStatements.put(field, bv);
        }
        bv.set(instrNum);
      }
    }
    return new ObjectArrayMapping<>(putInstrs.toArray(new Integer[putInstrs.size()]));
  }

  private class TransferFunctions
      implements ITransferFunctionProvider<IExplodedBasicBlock, BitVectorVariable> {
    @Override
    public boolean hasEdgeTransferFunctions() {
      return false;
    }

    @Override
    public UnaryOperator<BitVectorVariable> getEdgeTransferFunction(
        IExplodedBasicBlock src, IExplodedBasicBlock dst) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasNodeTransferFunctions() {
      return true;
    }

    @Override
    public UnaryOperator<BitVectorVariable> getNodeTransferFunction(IExplodedBasicBlock node) {
      SSAInstruction instruction = node.getInstruction();
      int instructionIndex = node.getFirstInstructionIndex();
      if (instruction instanceof SSAPutInstruction) {
        final SSAPutInstruction putInstr = (SSAPutInstruction) instruction;
        final IField field = cha.resolveField(putInstr.getDeclaredField());
        assert field != null;
        BitVector gen, kill;
        if (ecfg.getIR().getSymbolTable().isNullConstant(putInstr.getVal())) {
          gen = new BitVector();
          gen.set(putInstrNumbering.getMappedIndex(instructionIndex));
          kill = new BitVector();
        } else {
          gen = new BitVector();
          kill = staticField2DefStatements.get(field);
        }
        return new BitVectorKillGen(kill, gen);
      } else {
        return BitVectorIdentity.instance();
      }
    }

    @Override
    public AbstractMeetOperator<BitVectorVariable> getMeetOperator() {
      return BitVectorIntersection.instance();
    }
  }

  /**
   * run the analysis
   *
   * @return the solver used for the analysis, which contains the analysis result
   */
  public BitVectorSolver<IExplodedBasicBlock> analyze() {
    for (IExplodedBasicBlock ebb : Iterator2Iterable.make(ecfg.iterator())) {
      SSAInstruction inst = ebb.getInstruction();
      if (inst instanceof SSAPutInstruction && ((SSAPutInstruction) inst).isStatic()) {
        SSAPutInstruction putInstr = (SSAPutInstruction) inst;
      }
    }
    BitVectorFramework<IExplodedBasicBlock, Integer> framework =
        new BitVectorFramework<>(ecfg, new TransferFunctions(), putInstrNumbering);
    BitVectorSolver<IExplodedBasicBlock> solver = new BitVectorSolver<>(framework);
    try {
      solver.solve(null);
    } catch (CancelException e) {
      assert false;
    }
    if (DEBUG) {
      for (IExplodedBasicBlock ebb : ecfg) {
        LOG(
            DEBUG,
            "DEBUG",
            ebb
                + "\n"
                + ebb.getInstruction()
                + "\n"
                + solver.getIn(ebb)
                + "\n"
                + solver.getOut(ebb));
      }
    }
    return solver;
  }
}
