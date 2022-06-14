package com.uber.nullaway.jmh;

import java.io.IOException;
import java.util.Collections;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class DFlowMicroBenchmark {

  private NullawayJavac compiler;

  @Setup
  public void setup() throws IOException {
    compiler =
        NullawayJavac.create(
            Collections.singletonList(
                "/Users/msridhar/git-repos/NullAway/nullaway/src/test/resources/com/uber/nullaway/testdata/DFlowBench.java"),
            "com.uber",
            null,
            Collections.emptyList(),
            "");
  }

  @Benchmark
  public void compile(Blackhole bh) {
    boolean compile = compiler.compile();
    bh.consume(compile);
  }
}
