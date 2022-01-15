package com.uber.nullaway;

import org.junit.Test;

public class NullAwayJava8Tests extends NullAwayTestsBase {
  @Test
  public void java8PositiveCases() {
    defaultCompilationHelper.addSourceFile("NullAwayJava8PositiveCases.java").doTest();
  }

  @Test
  public void java8NegativeCases() {
    defaultCompilationHelper.addSourceFile("NullAwayJava8NegativeCases.java").doTest();
  }

  @Test
  public void functionalMethodSuperInterface() {
    defaultCompilationHelper.addSourceFile("NullAwaySuperFunctionalInterface.java").doTest();
  }

  @Test
  public void functionalMethodOverrideSuperInterface() {
    defaultCompilationHelper.addSourceFile("NullAwayOverrideFunctionalInterfaces.java").doTest();
  }
}
