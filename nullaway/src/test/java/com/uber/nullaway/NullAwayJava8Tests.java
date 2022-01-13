package com.uber.nullaway;

import com.google.errorprone.CompilationTestHelper;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class NullAwayJava8Tests {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private CompilationTestHelper defaultCompilationHelper;

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
