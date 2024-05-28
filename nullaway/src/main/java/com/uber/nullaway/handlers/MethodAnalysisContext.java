package com.uber.nullaway.handlers;

import com.google.errorprone.VisitorState;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.uber.nullaway.NullAway;

public class MethodAnalysisContext {
  private NullAway analysis;

  public NullAway getAnalysis() {
    return analysis;
  }

  public void setAnalysis(NullAway analysis) {
    this.analysis = analysis;
  }

  private VisitorState state;

  public VisitorState getState() {
    return state;
  }

  public void setState(VisitorState state) {
    this.state = state;
  }

  private MethodSymbol methodSymbol;

  public MethodSymbol getMethodSymbol() {
    return methodSymbol;
  }

  public void setMethodSymbol(MethodSymbol methodSymbol) {
    this.methodSymbol = methodSymbol;
  }

  public MethodAnalysisContext(NullAway analysis, VisitorState state, MethodSymbol methodSymbol) {
    this.analysis = analysis;
    this.state = state;
    this.methodSymbol = methodSymbol;
  }
}
