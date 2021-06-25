package com.uber.nullaway.jmh;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class CaffeineBenchmark {

  /**
   * we use a subset of the source files since many are auto-generated and annotated with
   * {@code @SuppressWarnings("NullAway")}
   */
  private static final ImmutableList<String> SOURCE_FILE_NAMES =
      ImmutableList.of(
          "com/github/benmanes/caffeine/cache/AbstractLinkedDeque.java",
          "com/github/benmanes/caffeine/cache/AccessOrderDeque.java",
          "com/github/benmanes/caffeine/cache/Async.java",
          "com/github/benmanes/caffeine/cache/AsyncCache.java",
          "com/github/benmanes/caffeine/cache/AsyncCacheLoader.java",
          "com/github/benmanes/caffeine/cache/AsyncLoadingCache.java",
          "com/github/benmanes/caffeine/cache/BoundedBuffer.java",
          "com/github/benmanes/caffeine/cache/BoundedLocalCache.java",
          "com/github/benmanes/caffeine/cache/Buffer.java",
          "com/github/benmanes/caffeine/cache/Cache.java",
          "com/github/benmanes/caffeine/cache/CacheLoader.java",
          "com/github/benmanes/caffeine/cache/Caffeine.java",
          "com/github/benmanes/caffeine/cache/CaffeineSpec.java",
          "com/github/benmanes/caffeine/cache/Expiry.java",
          "com/github/benmanes/caffeine/cache/FrequencySketch.java",
          "com/github/benmanes/caffeine/cache/LinkedDeque.java",
          "com/github/benmanes/caffeine/cache/LoadingCache.java",
          "com/github/benmanes/caffeine/cache/LocalAsyncCache.java",
          "com/github/benmanes/caffeine/cache/LocalAsyncLoadingCache.java",
          "com/github/benmanes/caffeine/cache/LocalCache.java",
          "com/github/benmanes/caffeine/cache/LocalCacheFactory.java",
          "com/github/benmanes/caffeine/cache/LocalLoadingCache.java",
          "com/github/benmanes/caffeine/cache/LocalManualCache.java",
          "com/github/benmanes/caffeine/cache/MpscGrowableArrayQueue.java",
          "com/github/benmanes/caffeine/cache/Node.java",
          "com/github/benmanes/caffeine/cache/NodeFactory.java",
          "com/github/benmanes/caffeine/cache/Pacer.java",
          "com/github/benmanes/caffeine/cache/Policy.java",
          "com/github/benmanes/caffeine/cache/References.java",
          "com/github/benmanes/caffeine/cache/RemovalCause.java",
          "com/github/benmanes/caffeine/cache/RemovalListener.java",
          "com/github/benmanes/caffeine/cache/Scheduler.java",
          "com/github/benmanes/caffeine/cache/SerializationProxy.java",
          "com/github/benmanes/caffeine/cache/StripedBuffer.java",
          "com/github/benmanes/caffeine/cache/Ticker.java",
          "com/github/benmanes/caffeine/cache/TimerWheel.java",
          "com/github/benmanes/caffeine/cache/UnboundedLocalCache.java",
          "com/github/benmanes/caffeine/cache/Weigher.java",
          "com/github/benmanes/caffeine/cache/WriteOrderDeque.java",
          "com/github/benmanes/caffeine/cache/WriteThroughEntry.java",
          "com/github/benmanes/caffeine/cache/stats/CacheStats.java",
          "com/github/benmanes/caffeine/cache/stats/ConcurrentStatsCounter.java",
          "com/github/benmanes/caffeine/cache/stats/DisabledStatsCounter.java",
          "com/github/benmanes/caffeine/cache/stats/GuardedStatsCounter.java",
          "com/github/benmanes/caffeine/cache/stats/StatsCounter.java");

  NullawayJavac nullawayJavac;

  @Setup
  public void setup() throws IOException {
    nullawayJavac = new NullawayJavac();
    String benchSourceDir = System.getProperty("nullaway.benchmark.sources");
    List<String> realSourceFileNames =
        SOURCE_FILE_NAMES
            .stream()
            .map(s -> benchSourceDir + File.separator + s.replaceAll("/", File.separator))
            .collect(Collectors.toList());
    nullawayJavac.prepare(realSourceFileNames, "com.github.benmanes.caffeine");
  }

  @Benchmark
  public void compile(Blackhole bh) throws Exception {
    bh.consume(nullawayJavac.testCompile());
  }
}
