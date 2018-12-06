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
   * If true, we just add NullAway to the processorpath but otherwise leave the javac args
   * unmodified (see {@link #justRun(String[])}). If false, we use the logic of {@link
   * #addNullAwayArgsAndRun(String[])}
   */
  private static final boolean JUST_RUN = true;

  public static void main(String[] args) {
    if (JUST_RUN) {
      justRun(args);
    } else {
      addNullAwayArgsAndRun(args);
    }
  }

  /**
   * Some recommendations for this mode.
   *
   * <ul>
   *   <li>Disable all other checks but NullAway by passing {@code -XepDisableAllChecks
   *       -Xep:NullAway:WARN} after other EP options
   *   <li>If you want to just benchmark the baseline without NullAway, only pass {@code
   *       -XepDisableAllChecks} (you'll have to do this in a different run)
   * </ul>
   */
  private static void justRun(String[] args) {
    List<String> javacArgs = new ArrayList<>(Arrays.asList(args));
    String nullawayJar = getJarFileForClass(NullAway.class).getFile();

    // add NullAway jar to existing processor path if found
    boolean foundProcessorPath = false;
    for (int i = 0; i < javacArgs.size(); i++) {
      if (javacArgs.get(i).equals("-processorpath")) {
        foundProcessorPath = true;
        String procPath = javacArgs.get(i + 1);
        procPath = procPath + System.getProperties().getProperty("path.separator") + nullawayJar;
        javacArgs.set(i + 1, procPath);
        break;
      }
    }
    if (!foundProcessorPath) {
      javacArgs.add("-processorpath");
      javacArgs.add(nullawayJar);
    }
    System.out.println("Running");
    runCompile(javacArgs, 3, 8);
  }

  /**
   * Here we assume that the javac command has no existing processorpath and no other error prone
   * flags are being passed. In this case, we assume the annotated packages are passed as the first
   * argument and the remaining javac args as the rest. We run two configs, one with NullAway added
   * in a warning-only mode and one with no NullAway.
   *
   * @param args
   */
  private static void addNullAwayArgsAndRun(String[] args) {
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
    System.out.println(
        "Average running time "
            + String.format("%.2f", ((double) totalRunningTime / 1000000000.0) / realRuns));
  }

  private static URL getJarFileForClass(Class<?> klass) {
    return klass.getProtectionDomain().getCodeSource().getLocation();
  }
}
