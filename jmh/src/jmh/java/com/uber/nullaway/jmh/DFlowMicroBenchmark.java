package com.uber.nullaway.jmh;

import java.io.IOException;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class DFlowMicroBenchmark {

  private DataFlowMicroBenchmarkCompiler compiler;

  @Setup
  public void setup() throws IOException {
    compiler = new DataFlowMicroBenchmarkCompiler();
  }

  @Benchmark
  public void compile(Blackhole bh) {
    bh.consume(compiler.compile());
  }
}
