package com.uber.nullaway.jmh;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class NullawayBenchmark {

  NullawayJavac nullawayJavac;

  @Setup
  public void setup() {
    nullawayJavac = new NullawayJavac();
    nullawayJavac.prepare();
  }

  @Benchmark
  public void compile(Blackhole bh) throws Exception {
    bh.consume(nullawayJavac.testCompile());
  }
}
