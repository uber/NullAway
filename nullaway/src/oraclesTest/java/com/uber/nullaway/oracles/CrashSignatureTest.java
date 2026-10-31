package com.uber.nullaway.oracles;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * A crash signature names the class of the last exception in the {@code Caused by} chain and the
 * first NullAway method on that exception's stack, so that crashes the dataflow analysis wraps in
 * one outer exception keep apart.
 */
public class CrashSignatureTest {

  /** The start of the message of Error Prone's crash diagnostic, which indents the trace. */
  private static final String DIAGNOSTIC =
      """
      An unhandled exception was thrown by the Error Prone static analysis plugin.
           error-prone version: 2.50.0
           BugPattern: NullAway
           Stack Trace:
      """;

  @Test
  public void aWrappedCauseIsSignedByItsOwnClassAndNullAwayFrame() {
    String detail =
        DIAGNOSTIC
            + """
                 org.checkerframework.nullaway.javacutil.BugInCF: Problem with initial store for CFGMethod(
              class LocalTypeB {
              }
              ), parameters=[]
              \tat org.checkerframework.nullaway.dataflow.analysis.ForwardAnalysisImpl.initInitialInputs(ForwardAnalysisImpl.java:339)
              \tat com.uber.nullaway.dataflow.RunOnceForwardAnalysisImpl.performAnalysis(RunOnceForwardAnalysisImpl.java:31)
              \tat com.uber.nullaway.dataflow.DataFlow.dataflow(DataFlow.java:160)
              \tat jdk.compiler/com.sun.tools.javac.api.JavacTaskImpl.call(JavacTaskImpl.java:101)
              \tat com.uber.nullaway.oracles.Compiler.runNullAway(Compiler.java:121)
              Caused by: java.lang.NullPointerException
              \tat com.uber.nullaway.dataflow.NullnessStoreInitializer.getEnvNullnessStoreForClass(NullnessStoreInitializer.java:59)
              \tat com.uber.nullaway.dataflow.CoreNullnessStoreInitializer.methodInitialStore(CoreNullnessStoreInitializer.java:75)
              \tat org.checkerframework.nullaway.dataflow.analysis.ForwardAnalysisImpl.initInitialInputs(ForwardAnalysisImpl.java:337)
              \t... 4 more
            """;
    assertThat(CrashSignature.of(detail))
        .isEqualTo(
            "java.lang.NullPointerException at"
                + " com.uber.nullaway.dataflow.NullnessStoreInitializer.getEnvNullnessStoreForClass");
  }

  @Test
  public void aCauseWithNoNullAwayFrameOfItsOwnTakesOneFromTheFramesItShares() {
    String detail =
        """
        java.lang.RuntimeException: wrapped
        \tat com.uber.nullaway.dataflow.RunOnceForwardAnalysisImpl.performAnalysis(RunOnceForwardAnalysisImpl.java:31)
        \tat com.uber.nullaway.NullAway.matchMethod(NullAway.java:600)
        \tat com.google.errorprone.scanner.ErrorProneScanner.visitMethod(ErrorProneScanner.java:900)
        Caused by: java.lang.IndexOutOfBoundsException: Index 1 out of bounds for length 1
        \tat java.base/java.util.ImmutableCollections$ListN.get(ImmutableCollections.java:687)
        \t... 2 more
        """;
    assertThat(CrashSignature.of(detail))
        .isEqualTo("java.lang.IndexOutOfBoundsException at com.uber.nullaway.NullAway.matchMethod");
  }

  @Test
  public void anExceptionWithNoCauseIsSignedByItsOwnClassAndFirstNullAwayFrame() {
    String detail =
        """
        java.lang.RuntimeException: whoops, better handle ANNOTATED_TYPE
        \tat com.uber.nullaway.NullAway.mayBeNullExpr(NullAway.java:2998)
        \tat com.uber.nullaway.NullAway.matchMemberReference(NullAway.java:1100)
        """;
    assertThat(CrashSignature.of(detail))
        .isEqualTo("java.lang.RuntimeException at com.uber.nullaway.NullAway.mayBeNullExpr");
  }

  @Test
  public void framesOfTheHarnessAreNotNullAwayFrames() {
    String detail =
        """
        java.lang.IllegalStateException: thrown by ThrowingChecker
        \tat com.uber.nullaway.oracles.CompilerTest$ThrowingChecker.matchMethod(CompilerTest.java:31)
        \tat com.uber.nullaway.oracles.Compiler.runNullAway(Compiler.java:121)
        """;
    assertThat(CrashSignature.of(detail))
        .isEqualTo("java.lang.IllegalStateException at " + CrashSignature.NO_NULLAWAY_FRAME);
  }

  @Test
  public void theCauseOfASuppressedExceptionIsNotTheRootCause() {
    String detail =
        """
        java.lang.IllegalStateException: outer
        \tat com.uber.nullaway.NullAway.matchClass(NullAway.java:1980)
        \tSuppressed: java.io.UncheckedIOException: while closing
        \t\tat com.uber.nullaway.NullAway.close(NullAway.java:10)
        \tCaused by: java.io.IOException: disk full
        \t\tat com.uber.nullaway.NullAway.flush(NullAway.java:20)
        Caused by: java.lang.NullPointerException
        \tat com.uber.nullaway.handlers.StreamNullabilityPropagator.onDataflowVisitReturn(StreamNullabilityPropagator.java:700)
        \t... 1 more
        """;
    assertThat(CrashSignature.of(detail))
        .isEqualTo(
            "java.lang.NullPointerException at"
                + " com.uber.nullaway.handlers.StreamNullabilityPropagator.onDataflowVisitReturn");
  }

  @Test
  public void theCauseOfASuppressedExceptionIsNotACauseWhenTheChainHasNone() {
    String detail =
        """
        java.lang.IllegalStateException: outer
        \tat com.uber.nullaway.NullAway.matchClass(NullAway.java:1980)
        \tSuppressed: java.io.UncheckedIOException: while closing
        \t\tat com.uber.nullaway.NullAway.close(NullAway.java:10)
        \tCaused by: java.io.IOException: disk full
        \t\tat com.uber.nullaway.NullAway.flush(NullAway.java:20)
        """;
    assertThat(CrashSignature.of(detail))
        .isEqualTo("java.lang.IllegalStateException at com.uber.nullaway.NullAway.matchClass");
  }

  @Test
  public void theOrdinalsJavacGivesLambdasAndAnonymousClassesAreMasked() {
    String detail =
        """
        java.lang.NullPointerException
        \tat com.uber.nullaway.NullAway$2.visitIdentifier(NullAway.java:400)
        \tat com.uber.nullaway.NullAway.lambda$matchClass$13(NullAway.java:1990)
        """;
    assertThat(CrashSignature.of(detail))
        .isEqualTo(
            "java.lang.NullPointerException at com.uber.nullaway.NullAway$_.visitIdentifier");
  }

  @Test
  public void aLambdaBodyKeepsTheNameOfItsEnclosingMethod() {
    String detail =
        """
        java.lang.NullPointerException
        \tat com.uber.nullaway.NullAway.lambda$matchClass$13(NullAway.java:1990)
        """;
    assertThat(CrashSignature.of(detail))
        .isEqualTo(
            "java.lang.NullPointerException at com.uber.nullaway.NullAway.lambda$matchClass$_");
  }
}
