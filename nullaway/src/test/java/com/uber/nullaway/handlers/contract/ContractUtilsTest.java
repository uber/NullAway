package com.uber.nullaway.handlers.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.errorprone.VisitorState;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.uber.nullaway.NullAway;
import org.junit.Before;
import org.junit.Test;

public class ContractUtilsTest {

  private Tree tree;
  private NullAway analysis;
  private VisitorState state;
  private Symbol symbol;

  @Before
  public void setUp() {
    tree = mock(Tree.class);
    analysis = mock(NullAway.class, RETURNS_MOCKS);
    state = mock(VisitorState.class);
    symbol = mock(Symbol.class);
  }

  @Test
  public void getEmptyAntecedent() {
    String[] antecedent = ContractUtils.getAntecedent("->_", tree, analysis, state, symbol, 0);

    assertArrayEquals(new String[0], antecedent);
    verifyNoInteractions(tree, state, analysis, symbol);
  }
}
