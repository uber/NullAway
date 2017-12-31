package com.uber.nullaway.benchmark;

import com.google.errorprone.ErrorProneCompiler;
import com.uber.nullaway.NullAway;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** harness for benchmarking NullAway for some javac task */
public class NullAwayBenchmarkHarness {

  /**
   * NOTE: requires Error Prone javac in the bootclasspath via -Xbootclasspath/p: JVM arg
   *
   * @param args First argument should be the annotated packages for NullAway. Remaining arguments
   *     are passed directly to javac.
   */
  public static void main(String[] args) {
    String nullawayJar = getJarFileForClass(NullAway.class).getFile();
    String annotPackages = args[0];
    String[] javacArgs = Arrays.copyOfRange(args, 1, args.length);
    // run NullAway first
    List<String> nullawayArgs =
        Arrays.asList(
            "-Xmaxwarns",
            "1",
            "-XepDisableAllChecks",
            "-Xep:NullAway:WARN",
            "-XepOpt:NullAway:AnnotatedPackages=" + annotPackages,
            "-processorpath",
            nullawayJar);
    List<String> fixedArgs = new ArrayList<>();
    fixedArgs.addAll(nullawayArgs);
    fixedArgs.addAll(Arrays.asList(javacArgs));
    System.out.println("With NullAway");
    runCompile(fixedArgs, 7, 10);
    // run without NullAway
    fixedArgs = new ArrayList<>();
    fixedArgs.add("-XepDisableAllChecks");
    fixedArgs.addAll(Arrays.asList(javacArgs));
    System.out.println("No NullAway");
    runCompile(fixedArgs, 7, 10);
  }

  private static void runCompile(List<String> fixedArgs, int warmupRuns, int realRuns) {
    String[] finalArgs = fixedArgs.toArray(new String[fixedArgs.size()]);
    for (int i = 0; i < warmupRuns; i++) {
      System.out.println("Warmup Run " + (i + 1));
      long startTime = System.nanoTime();
      ErrorProneCompiler.compile(finalArgs);
      long endTime = System.nanoTime();
      System.out.println("Running time " + (((double) endTime - startTime) / 1000000000.0));
    }
    long totalRunningTime = 0;
    for (int i = 0; i < realRuns; i++) {
      System.out.println("Real Run " + (i + 1));
      long startTime = System.nanoTime();
      ErrorProneCompiler.compile(finalArgs);
      long endTime = System.nanoTime();
      long runTime = endTime - startTime;
      System.out.println("Running time " + (((double) runTime) / 1000000000.0));
      totalRunningTime += runTime;
    }
    System.out.println("Average running time " + ((double) totalRunningTime) / realRuns);
  }

  private static URL getJarFileForClass(Class<?> klass) {
    return klass.getProtectionDomain().getCodeSource().getLocation();
  }
}
