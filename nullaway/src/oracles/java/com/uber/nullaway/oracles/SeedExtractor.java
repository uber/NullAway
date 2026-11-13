package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreeScanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Reads seeds out of the NullAway test sources.
 *
 * <p>Each expression statement that calls {@code addSourceLines} one or more times becomes one
 * seed, holding one {@link Source} per call. A call qualifies when every argument is a string
 * literal or a text block: the first names the path, and the rest are joined with newlines into the
 * text, as {@code CompilationTestHelper} joins them. A statement with any other call, such as one
 * that passes a constant, yields no seed.
 *
 * <p>A seed is named after its test file and test method, such as {@code
 * NullAwayCoreTests.java#testFoo}, and the second and later chains in one method get {@code [2]},
 * {@code [3]}, and so on. The name holds no line number, so editing other tests in the file leaves
 * it, and every finding keyed by it, unchanged.
 */
final class SeedExtractor {

  private SeedExtractor() {}

  /**
   * Returns the {@code addSourceLines} seeds found in every {@code .java} file under {@code
   * testRoot}, in path order.
   */
  static ImmutableList<Seed> extract(Path testRoot) throws IOException {
    ImmutableList.Builder<Seed> seeds = ImmutableList.builder();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(testRoot)) {
      files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
    for (Path file : files) {
      String text = Files.readString(file);
      if (!text.contains("addSourceLines")) {
        continue;
      }
      ParsedUnit parsed = ParsedUnit.parse(new Source(file.getFileName().toString(), text));
      new ChainScanner(file.getFileName().toString(), seeds).scan(parsed.unit(), null);
    }
    return seeds.build();
  }

  /** Collects one seed per qualifying {@code addSourceLines} chain. */
  private static final class ChainScanner extends TreeScanner<Void, Void> {
    private final String fileName;
    private final ImmutableList.Builder<Seed> seeds;
    private final Map<String, Integer> chainsPerMethod = new HashMap<>();
    private String methodName = "<init>";

    ChainScanner(String fileName, ImmutableList.Builder<Seed> seeds) {
      this.fileName = fileName;
      this.seeds = seeds;
    }

    @Override
    public Void visitMethod(MethodTree tree, Void unused) {
      String enclosing = methodName;
      methodName = tree.getName().toString();
      try {
        return super.visitMethod(tree, null);
      } finally {
        methodName = enclosing;
      }
    }

    @Override
    public Void visitExpressionStatement(ExpressionStatementTree tree, Void unused) {
      ImmutableList<Source> sources = sourcesOf(tree.getExpression());
      if (sources != null && !sources.isEmpty()) {
        String name = fileName + "#" + methodName;
        int ordinal = chainsPerMethod.merge(name, 1, Integer::sum);
        seeds.add(new Seed(ordinal == 1 ? name : name + "[" + ordinal + "]", sources));
      }
      return super.visitExpressionStatement(tree, null);
    }

    /**
     * Returns the units the chain ending in {@code expression} adds, outermost call last, or null
     * if any {@code addSourceLines} call in it has an argument that is not a string literal.
     */
    private static @Nullable ImmutableList<Source> sourcesOf(ExpressionTree expression) {
      Deque<Source> sources = new ArrayDeque<>();
      ExpressionTree current = expression;
      while (current instanceof MethodInvocationTree invocation
          && invocation.getMethodSelect() instanceof MemberSelectTree select) {
        if (select.getIdentifier().contentEquals("addSourceLines")) {
          List<String> args = new ArrayList<>();
          for (ExpressionTree arg : invocation.getArguments()) {
            if (!(arg instanceof LiteralTree literal && literal.getValue() instanceof String s)) {
              return null;
            }
            args.add(s);
          }
          if (args.size() < 2) {
            return null;
          }
          sources.addFirst(
              new Source(args.get(0), String.join("\n", args.subList(1, args.size()))));
        }
        current = select.getExpression();
      }
      return ImmutableList.copyOf(sources);
    }
  }
}
