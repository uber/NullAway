package com.uber.nullaway.jmh;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class DataFlowMicroBenchmarkCompiler {

  private final NullawayJavac nullawayJavac;

  public DataFlowMicroBenchmarkCompiler() throws IOException {
    nullawayJavac = NullawayJavac.createFromSourceString("DFlowBench", SOURCE, "com.uber");
  }

  public boolean compile() {
    return nullawayJavac.compile();
  }

  private static final String SOURCE;

  static {
    ClassLoader classLoader = DataFlowMicroBenchmarkCompiler.class.getClassLoader();
    try (InputStream inputStream = classLoader.getResourceAsStream("DFlowBench.java")) {
      SOURCE = readFromInputStream(inputStream);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static String readFromInputStream(InputStream inputStream) throws IOException {
    StringBuilder result = new StringBuilder();
    try (BufferedReader br =
        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        result.append(line).append("\n");
      }
    }
    return result.toString();
  }
}
