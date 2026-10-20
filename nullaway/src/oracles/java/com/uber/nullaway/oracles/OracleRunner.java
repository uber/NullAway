package com.uber.nullaway.oracles;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multiset;
import com.google.common.collect.Multisets;
import com.google.common.hash.Hashing;
import com.uber.nullaway.oracles.AnnotationMutator.Mutation;
import com.uber.nullaway.oracles.AnnotationMutator.Variant;
import com.uber.nullaway.oracles.Compiler.Outcome;
import com.uber.nullaway.oracles.Compiler.Status;
import com.uber.nullaway.oracles.Compiler.Warning;
import com.uber.nullaway.oracles.KnownFindings.Verdict;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Runs NullAway over seeds taken from its own tests and over variants of them, and reports where
 * one of two oracles fails.
 *
 * <ul>
 *   <li><b>Crash.</b> NullAway must not throw on any source javac accepts. Inputs are the seeds and
 *       {@link AnnotationMutator} variants of them.
 *   <li><b>Metamorphic pairs.</b> Two compilations that differ only in a way Java gives no meaning
 *       must yield the same NullAway warnings. The pairs are a seed against each {@link Relation}
 *       rewrite of it, and one unit compiled with the rest of its seed as source against the same
 *       unit compiled with the rest as class files. Warnings compare by unit, line, and message,
 *       with the parts of the message a rewrite may change masked out.
 * </ul>
 *
 * <p>A crash is deduplicated by its {@link CrashSignature}, and the reproduction kept is the
 * smallest seen. A divergence is grouped by relation, seed, side, and message template, the key
 * {@link KnownFindings} matches. Each crash signature and divergence group is new unless {@value
 * #KNOWN} lists it. The run writes {@code report.md}, a shorter {@code summary.md}, the new
 * findings as {@code new-findings.tsv} lines to triage into {@value #KNOWN}, and one directory per
 * finding with the sources that reproduce it under {@value #OUT}, and exits with status 1 if it saw
 * a new crash signature. A new divergence never fails the run, since telling a NullAway defect from
 * an artifact of the rewrite takes a person.
 *
 * <p>The working directory is the {@code nullaway} module. Arguments, all optional: {@code
 * --configs <comma-separated names from CONFIGS>} (default all), {@code --relations
 * <comma-separated relation names, bytecode, or none>} (default all), {@code --mutants <variants
 * per seed and configuration>} (default 3), {@code --limit <seeds>}, {@code --filter <substring of
 * the seed name>}, {@code --max-minutes <wall-clock budget>} (default 60), and {@code --random-seed
 * <long>} (default 1), which picks the mutants.
 */
public final class OracleRunner {

  /** NullAway options for each configuration a seed runs under, keyed by a name for the report. */
  static final ImmutableMap<String, ImmutableList<String>> CONFIGS =
      ImmutableMap.of(
          "legacy",
          ImmutableList.of(
              "-XepOpt:NullAway:AnnotatedPackages=com.uber",
              "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.[a-zA-Z0-9.]+.unannotated"),
          "jspecify",
          ImmutableList.of(
              "-XepOpt:NullAway:AnnotatedPackages=com.uber",
              "-XepOpt:NullAway:UnannotatedSubPackages=com.uber.[a-zA-Z0-9.]+.unannotated",
              "-XepOpt:NullAway:JSpecifyMode=true"),
          "jspecify-nullmarked",
          ImmutableList.of(
              "-XepOpt:NullAway:OnlyNullMarked=true", "-XepOpt:NullAway:JSpecifyMode=true"));

  /** The test sources seeds come from, relative to the working directory. */
  private static final String TESTS = "src/test/java";

  /** The output directory, relative to the working directory; a run replaces its contents. */
  private static final String OUT = "build/oracles";

  /**
   * The triaged findings, relative to the working directory, in the {@link KnownFindings} format.
   */
  private static final String KNOWN = "src/oracles/known-findings.tsv";

  /** The most mutations one variant carries. */
  private static final int MAX_MUTATIONS = 3;

  private static final ImmutableSet<String> OPTIONS =
      ImmutableSet.of(
          "--configs",
          "--relations",
          "--mutants",
          "--limit",
          "--filter",
          "--max-minutes",
          "--random-seed");

  /** The {@code (line N)} NullAway puts after a field it names. */
  private static final Pattern LINE_REFERENCE = Pattern.compile("\\(line \\d+\\)");

  /**
   * The overridden method a parameter warning names. NullAway calls it a functional interface
   * method for a lambda and a superclass method for a class, and prints its signature differently.
   */
  private static final Pattern OVERRIDDEN =
      Pattern.compile("parameter in (?:functional interface|superclass) method .*? is @");

  /**
   * A span NullAway quotes, such as an expression or a field name. A multi-line expression is cut
   * at the end of the first line, which {@link Warning#message()} keeps, so the closing quote may
   * be missing.
   */
  private static final Pattern QUOTED = Pattern.compile("'[^']*(?:'|$)");

  /** The metamorphic relations, besides compiling one unit against the others' class files. */
  private static final ImmutableList<Relation> RELATIONS =
      ImmutableList.<Relation>builder()
          .addAll(SyntacticRelations.all())
          .addAll(AttributedRelations.all())
          .build();

  /** The name of the relation that compiles one unit against the others' class files. */
  private static final String BYTECODE = "bytecode";

  private static final String SEPARATOR = " | ";

  /** The counter suffix for a relation that threw while rewriting a seed. */
  private static final String REWRITE_FAILED = "rewrite failed";

  private final Compiler compiler;
  private final KnownFindings known;
  private final Map<String, Crash> crashes = new TreeMap<>();
  private final List<Divergence> divergences = new ArrayList<>();
  private final Map<GroupKey, Group> groups = new TreeMap<>();
  private final Map<String, Integer> counters = new TreeMap<>();

  /** Wall-clock milliseconds spent in NullAway runs and rewrites, by the kind of input. */
  private final Map<String, Long> millis = new TreeMap<>();

  /**
   * The smallest reproduction seen for one crash signature.
   *
   * @param signature the {@link CrashSignature}
   * @param config the configuration the reproduction ran under
   * @param origin the seed name, plus the mutations or the relation that produced the input
   * @param sources the input
   * @param detail the stack trace
   * @param occurrences how many inputs crashed with this signature
   */
  private record Crash(
      String signature,
      String config,
      String origin,
      ImmutableList<Source> sources,
      String detail,
      int occurrences) {}

  /**
   * Two compilations of one metamorphic pair that NullAway reported differently.
   *
   * @param directory the name of the directory the report writes it to, such as {@code cast-12}
   * @param relation which pair
   * @param config the configuration both ran under
   * @param seed the seed name
   * @param left the first compilation's sources: the seed
   * @param right the second compilation's sources: the rewrite, or for {@code bytecode} the one
   *     unit that was compiled against the class files of the others in {@code left}
   * @param onlyLeft warning keys only the first compilation reported
   * @param onlyRight warning keys only the second compilation reported
   */
  private record Divergence(
      String directory,
      String relation,
      String config,
      String seed,
      ImmutableList<Source> left,
      ImmutableList<Source> right,
      ImmutableList<String> onlyLeft,
      ImmutableList<String> onlyRight) {}

  /**
   * The key {@link KnownFindings#divergence} matches.
   *
   * @param relation the relation name
   * @param seed the seed name
   * @param side {@code left} if only the seed reported the warning, {@code right} if only the
   *     rewrite did
   * @param template the warning message, masked as {@link #keys} masks it
   */
  private record GroupKey(String relation, String seed, String side, String template)
      implements Comparable<GroupKey> {
    private static final Comparator<GroupKey> ORDER =
        Comparator.comparing(GroupKey::relation)
            .thenComparing(GroupKey::seed)
            .thenComparing(GroupKey::side)
            .thenComparing(GroupKey::template);

    @Override
    public int compareTo(GroupKey other) {
      return ORDER.compare(this, other);
    }
  }

  /**
   * The divergences that share one {@link GroupKey}.
   *
   * @param verdict the known verdict, or null for a new group
   * @param configs the configurations it diverged under
   * @param example the directory of the first divergence in the group
   */
  private record Group(@Nullable Verdict verdict, Set<String> configs, String example) {}

  /**
   * Creates a runner that compares its findings with {@code known} and compiles with {@code
   * compiler}.
   */
  OracleRunner(KnownFindings known, Compiler compiler) {
    this.known = known;
    this.compiler = compiler;
  }

  /** Runs the oracles with the arguments the class comment lists. */
  public static void main(String[] argv) throws IOException {
    Map<String, String> args = parseArguments(argv);
    String filter = args.getOrDefault("--filter", "");
    int limit = Integer.parseInt(args.getOrDefault("--limit", "" + Integer.MAX_VALUE));
    int mutants = Integer.parseInt(args.getOrDefault("--mutants", "3"));
    long randomSeed = Long.parseLong(args.getOrDefault("--random-seed", "1"));
    List<String> configs =
        List.of(args.getOrDefault("--configs", String.join(",", CONFIGS.keySet())).split(","));
    for (String config : configs) {
      if (!CONFIGS.containsKey(config)) {
        throw new IllegalArgumentException(
            "Unknown configuration '" + config + "'; known: " + CONFIGS.keySet());
      }
    }
    String relationNames = args.get("--relations");
    ImmutableList<Relation> relations = relations(relationNames);
    boolean bytecode =
        relationNames == null || List.of(relationNames.split(",")).contains(BYTECODE);
    Duration budget = Duration.ofMinutes(Long.parseLong(args.getOrDefault("--max-minutes", "60")));

    ImmutableList<Seed> allSeeds = SeedExtractor.extract(Path.of(TESTS));
    ImmutableList<Seed> seeds =
        allSeeds.stream()
            .filter(s -> s.name().contains(filter))
            .limit(limit)
            .collect(ImmutableList.toImmutableList());
    System.out.println(
        "Seeds: " + seeds.size() + ", configurations: " + configs + ", JDK " + Runtime.version());

    OracleRunner runner = new OracleRunner(KnownFindings.read(Path.of(KNOWN)), new Compiler());
    Instant started = Instant.now();
    Instant deadline = started.plus(budget);
    int done = 0;
    for (Seed seed : seeds) {
      if (Instant.now().isAfter(deadline)) {
        System.out.println("Stopped at the time budget after " + done + " seeds");
        break;
      }
      runner.check(seed, configs, relations, bytecode, randomSeed, mutants);
      if (++done % 25 == 0) {
        System.out.println(
            done
                + "/"
                + seeds.size()
                + ", crash signatures: "
                + runner.crashes.size()
                + ", divergences: "
                + runner.divergences.size());
      }
    }
    Duration elapsed = Duration.between(started, Instant.now());
    Path out = Path.of(OUT);
    String summary = runner.writeReport(out, done, seeds.size(), elapsed);
    System.out.println(summary);
    System.out.println("Report: " + out.toAbsolutePath().resolve("report.md"));
    if (runner.foundNewCrash()) {
      System.exit(1);
    }
  }

  /**
   * Returns the options in {@code argv}, which alternates option names from {@link #OPTIONS} and
   * values.
   */
  private static Map<String, String> parseArguments(String[] argv) {
    if (argv.length % 2 != 0) {
      throw new IllegalArgumentException("Every option takes a value: " + List.of(argv));
    }
    Map<String, String> args = new LinkedHashMap<>();
    for (int i = 0; i < argv.length; i += 2) {
      if (!OPTIONS.contains(argv[i])) {
        throw new IllegalArgumentException("Unknown option '" + argv[i] + "'; known: " + OPTIONS);
      }
      args.put(argv[i], argv[i + 1]);
    }
    return args;
  }

  /**
   * Returns the relations {@code names} lists, comma-separated, or all of them for null. The names
   * {@code bytecode}, which {@link #checkBytecode} handles, and {@code none}, which selects
   * nothing, are accepted and add no relation.
   */
  private static ImmutableList<Relation> relations(@Nullable String names) {
    if (names == null) {
      return RELATIONS;
    }
    ImmutableList.Builder<Relation> result = ImmutableList.builder();
    for (String name : names.split(",")) {
      if (name.equals("none") || name.equals(BYTECODE)) {
        continue;
      }
      result.add(
          RELATIONS.stream()
              .filter(r -> r.name().equals(name))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "Unknown relation '"
                              + name
                              + "'; known: "
                              + RELATIONS.stream().map(Relation::name).toList()
                              + ", "
                              + BYTECODE
                              + ", none")));
    }
    return result.build();
  }

  /**
   * Returns {@code count} mutants of {@code seed} for {@code config}, or fewer if {@code seed} has
   * no candidate mutation. The mutants depend only on the arguments, so adding, removing, or
   * filtering out other seeds leaves them unchanged.
   */
  static ImmutableList<Variant> mutants(
      Seed seed, List<Mutation> candidates, String config, long randomSeed, int count) {
    Random random =
        new Random(
            Hashing.farmHashFingerprint64()
                .newHasher()
                .putLong(randomSeed)
                .putString(seed.name(), UTF_8)
                .putString(config, UTF_8)
                .hash()
                .asLong());
    ImmutableList.Builder<Variant> result = ImmutableList.builder();
    for (int i = 0; i < count; i++) {
      Variant variant = AnnotationMutator.mutate(seed, candidates, random, MAX_MUTATIONS);
      if (variant == null) {
        break;
      }
      result.add(variant);
    }
    return result.build();
  }

  /**
   * Runs every oracle over {@code seed} under each of {@code configs}.
   *
   * @param bytecode whether to compare each unit against the others compiled to class files
   */
  void check(
      Seed seed,
      List<String> configs,
      List<Relation> relations,
      boolean bytecode,
      long randomSeed,
      int mutants) {
    ImmutableList<Mutation> candidates;
    try {
      candidates = AnnotationMutator.candidates(seed);
    } catch (RuntimeException | AssertionError | StackOverflowError e) {
      count("mutant: " + REWRITE_FAILED);
      System.err.println("Finding mutations of " + seed.name() + " failed:");
      e.printStackTrace();
      candidates = ImmutableList.of();
    }
    Map<Relation, ImmutableList<ImmutableList<Source>>> variants = new LinkedHashMap<>();
    for (Relation relation : relations) {
      long started = System.nanoTime();
      try {
        variants.put(relation, relation.variants(seed));
      } catch (RuntimeException | AssertionError | StackOverflowError e) {
        // A defect of the harness, not of NullAway: the run goes on without this relation's pair.
        count(relation.name() + ": " + REWRITE_FAILED);
        System.err.println("Rewriting " + seed.name() + " by " + relation.name() + " failed:");
        e.printStackTrace();
      }
      millis.merge(
          relation.name() + " rewrite", (System.nanoTime() - started) / 1_000_000, Long::sum);
    }
    for (String config : configs) {
      ImmutableList<String> options = CONFIGS.get(config);
      Outcome baseline = run("seed", seed.name(), config, options, seed.sources(), null);
      if (baseline.status() == Status.OK) {
        variants.forEach(
            (relation, list) -> checkRelation(relation, list, seed, config, options, baseline));
        if (bytecode) {
          checkBytecode(seed, config, options, baseline);
        }
      }
      for (Variant variant : mutants(seed, candidates, config, randomSeed, mutants)) {
        String origin =
            seed.name()
                + " with "
                + String.join(
                    "; ", variant.mutations().stream().map(Mutation::description).toList());
        run("mutant", origin, config, options, variant.sources(), null);
      }
    }
  }

  /**
   * Compares {@code baseline} with a run over the first variant of {@code relation} that javac
   * accepts, trying the variants in order.
   */
  private void checkRelation(
      Relation relation,
      ImmutableList<ImmutableList<Source>> variants,
      Seed seed,
      String config,
      List<String> options,
      Outcome baseline) {
    if (variants.isEmpty()) {
      count(relation.name() + ": not applicable");
      return;
    }
    for (ImmutableList<Source> variant : variants) {
      Outcome outcome =
          run(
              relation.name(),
              seed.name() + " rewritten by " + relation.name(),
              config,
              options,
              variant,
              null);
      if (outcome.status() == Status.OK) {
        compare(
            relation.name(),
            config,
            seed.name(),
            seed.sources(),
            variant,
            keys(baseline.warnings()),
            keys(outcome.warnings()));
        return;
      }
      if (outcome.status() == Status.CRASH) {
        return;
      }
    }
  }

  /**
   * For each unit of a seed with several, compares its warnings in {@code baseline} with a run over
   * that unit alone, against the other units compiled to class files.
   */
  private void checkBytecode(Seed seed, String config, List<String> options, Outcome baseline) {
    if (seed.sources().size() < 2) {
      return;
    }
    for (int client = 0; client < seed.sources().size(); client++) {
      Source clientSource = seed.sources().get(client);
      List<Source> library = new ArrayList<>(seed.sources());
      library.remove(client);
      Path classes = Compiler.createTempDirectory();
      try {
        long started = System.nanoTime();
        boolean libraryCompiled = compiler.compileWithoutNullAway(library, classes);
        millis.merge(BYTECODE + " library", (System.nanoTime() - started) / 1_000_000, Long::sum);
        if (!libraryCompiled) {
          count(BYTECODE + ": library does not compile alone");
          continue;
        }
        ImmutableList<Source> clientOnly = ImmutableList.of(clientSource);
        Outcome outcome =
            run(
                BYTECODE,
                seed.name()
                    + " with "
                    + clientSource.path()
                    + " compiled against the others' class files",
                config,
                options,
                clientOnly,
                classes,
                seed.sources());
        if (outcome.status() == Status.OK) {
          compare(
              BYTECODE,
              config,
              seed.name(),
              seed.sources(),
              clientOnly,
              keys(onlyIn(clientSource.path(), baseline.warnings())),
              keys(outcome.warnings()));
        }
      } finally {
        Compiler.deleteRecursively(classes);
      }
    }
  }

  /** Runs NullAway once, counts the outcome, and records it if NullAway crashed. */
  private Outcome run(
      String kind,
      String origin,
      String config,
      List<String> options,
      ImmutableList<Source> sources,
      @Nullable Path classes) {
    return run(kind, origin, config, options, sources, classes, sources);
  }

  /**
   * Runs NullAway once, counts the outcome, and records it if NullAway crashed.
   *
   * @param classes a directory of class files {@code sources} compile against, or null for none
   * @param reproduction the sources a crash report keeps: {@code sources}, plus the sources of the
   *     class files in {@code classes}, which the run deletes afterwards
   */
  private Outcome run(
      String kind,
      String origin,
      String config,
      List<String> options,
      ImmutableList<Source> sources,
      @Nullable Path classes,
      ImmutableList<Source> reproduction) {
    long started = System.nanoTime();
    Outcome outcome = compiler.runNullAway(sources, options, classes);
    millis.merge(kind, (System.nanoTime() - started) / 1_000_000, Long::sum);
    count(kind + ": " + outcome.status());
    if (outcome.status() == Status.CRASH) {
      recordCrash(config, origin, reproduction, outcome.detail());
    }
    return outcome;
  }

  private void recordCrash(
      String config, String origin, ImmutableList<Source> sources, String detail) {
    String signature = CrashSignature.of(detail);
    Crash previous = crashes.get(signature);
    if (previous == null || size(sources) < size(previous.sources())) {
      int occurrences = previous == null ? 1 : previous.occurrences() + 1;
      crashes.put(signature, new Crash(signature, config, origin, sources, detail, occurrences));
    } else {
      crashes.put(
          signature,
          new Crash(
              previous.signature(),
              previous.config(),
              previous.origin(),
              previous.sources(),
              previous.detail(),
              previous.occurrences() + 1));
    }
  }

  private void compare(
      String relation,
      String config,
      String seed,
      ImmutableList<Source> left,
      ImmutableList<Source> right,
      Multiset<String> leftWarnings,
      Multiset<String> rightWarnings) {
    if (leftWarnings.equals(rightWarnings)) {
      count(relation + ": agree");
      return;
    }
    count(relation + ": DIVERGE");
    Divergence divergence =
        new Divergence(
            relation + "-" + (divergences.size() + 1),
            relation,
            config,
            seed,
            left,
            right,
            ImmutableList.copyOf(Multisets.difference(leftWarnings, rightWarnings)),
            ImmutableList.copyOf(Multisets.difference(rightWarnings, leftWarnings)));
    divergences.add(divergence);
    group(divergence, "left", divergence.onlyLeft());
    group(divergence, "right", divergence.onlyRight());
  }

  /** Adds each of {@code warningKeys}, reported only on {@code side}, to its divergence group. */
  private void group(Divergence divergence, String side, List<String> warningKeys) {
    for (String warningKey : warningKeys) {
      String template = warningKey.substring(warningKey.indexOf(SEPARATOR) + SEPARATOR.length());
      GroupKey key = new GroupKey(divergence.relation(), divergence.seed(), side, template);
      groups
          .computeIfAbsent(
              key,
              k ->
                  new Group(
                      known.divergence(k.relation(), k.seed(), k.side(), k.template()).orElse(null),
                      new TreeSet<>(),
                      divergence.directory()))
          .configs()
          .add(divergence.config());
    }
  }

  /**
   * Keys each warning by its unit, its line, and its message with every quoted span replaced by
   * {@code '_'}, every {@code (line N)} by {@code (line _)}, and the name of an overridden method
   * by {@code _}. A rewrite may change an expression NullAway quotes, such as {@code 'x'} becoming
   * {@code '(x)'}, without changing the warning.
   */
  private static Multiset<String> keys(List<Warning> warnings) {
    Multiset<String> result = HashMultiset.create();
    for (Warning w : warnings) {
      String message =
          OVERRIDDEN.matcher(w.message()).replaceAll("parameter in overridden method _ is @");
      message = QUOTED.matcher(message).replaceAll("'_'");
      message = LINE_REFERENCE.matcher(message).replaceAll("(line _)");
      result.add(w.path() + ":" + w.line() + SEPARATOR + message);
    }
    return result;
  }

  private static ImmutableList<Warning> onlyIn(String path, List<Warning> warnings) {
    return warnings.stream()
        .filter(w -> w.path().equals(path))
        .collect(ImmutableList.toImmutableList());
  }

  private static int size(List<Source> sources) {
    return sources.stream().mapToInt(s -> s.text().length()).sum();
  }

  private void count(String key) {
    counters.merge(key, 1, Integer::sum);
  }

  /** Returns whether a crash signature the known findings do not list was seen. */
  boolean foundNewCrash() {
    return newCrashes().findAny().isPresent();
  }

  private Stream<Crash> newCrashes() {
    return crashes.values().stream().filter(c -> known.crash(c.signature()).isEmpty());
  }

  /**
   * Writes {@code report.md}, {@code summary.md}, {@code new-findings.tsv}, and one directory per
   * finding under {@code out}, replacing what was there.
   *
   * @param done how many seeds ran
   * @param selected how many seeds the arguments selected
   * @param elapsed the wall-clock time of the run
   * @return the text of {@code summary.md}
   */
  String writeReport(Path out, int done, int selected, Duration elapsed) throws IOException {
    if (Files.exists(out)) {
      Compiler.deleteRecursively(out);
    }
    Files.createDirectories(out);
    for (Crash crash : crashes.values()) {
      Path dir = out.resolve(crashDirectory(crash));
      writeSources(dir.resolve("src"), crash.sources());
      Files.writeString(
          dir.resolve("README.md"),
          "# "
              + crash.signature()
              + "\n\nKnown: "
              + known.crash(crash.signature()).map(Verdict::toString).orElse("no, new")
              + "\n\nConfiguration: `"
              + crash.config()
              + "` "
              + CONFIGS.get(crash.config())
              + "\n\nInputs with this signature: "
              + crash.occurrences()
              + "\n\nSmallest input: "
              + crash.origin()
              + "\n\n```text\n"
              + crash.detail()
              + "\n```\n");
    }
    for (Divergence d : divergences) {
      Path dir = out.resolve(d.directory());
      writeSources(dir.resolve("left"), d.left());
      writeSources(dir.resolve("right"), d.right());
      Files.writeString(
          dir.resolve("README.md"),
          "# "
              + d.relation()
              + ": "
              + d.seed()
              + "\n\nConfiguration: `"
              + d.config()
              + "` "
              + CONFIGS.get(d.config())
              + "\n\n`left` is the seed, `right` "
              + (d.relation().equals(BYTECODE)
                  ? "the one unit compiled against the class files of the others in `left`"
                  : "the rewrite")
              + ".\n\nOnly in `left`:\n\n"
              + bullets(d.onlyLeft())
              + "\nOnly in `right`:\n\n"
              + bullets(d.onlyRight()));
    }
    String summary = summary(done, selected, elapsed);
    Files.writeString(out.resolve("summary.md"), summary);
    Files.writeString(out.resolve("report.md"), report(done, selected, elapsed));
    Files.writeString(out.resolve("new-findings.tsv"), newFindings());
    return summary;
  }

  /**
   * Returns every new crash signature and divergence group as a line of the {@link KnownFindings}
   * format, with {@code TODO} for the verdict, which a person replaces before copying the line into
   * {@value #KNOWN}.
   */
  private String newFindings() {
    StringBuilder lines = new StringBuilder();
    newCrashes().forEach(c -> lines.append("crash\t").append(c.signature()).append("\tTODO\n"));
    groups.forEach(
        (k, g) -> {
          if (g.verdict() == null) {
            lines
                .append(
                    String.join("\t", "divergence", k.relation(), k.seed(), k.side(), k.template()))
                .append("\tTODO\n");
          }
        });
    return lines.toString();
  }

  private String crashDirectory(Crash crash) {
    return "crash-" + (new ArrayList<>(crashes.keySet()).indexOf(crash.signature()) + 1);
  }

  /** Returns the short summary, with links that work only next to the report. */
  private String summary(int done, int selected, Duration elapsed) {
    StringBuilder s = new StringBuilder("## NullAway oracles\n\n");
    s.append(header(done, selected, elapsed));
    List<Crash> newCrashes = newCrashes().toList();
    long newGroups = groups.values().stream().filter(g -> g.verdict() == null).count();
    s.append("| | New | Known |\n| --- | --- | --- |\n");
    s.append("| Crash signatures | ")
        .append(newCrashes.size())
        .append(" | ")
        .append(crashes.size() - newCrashes.size())
        .append(" |\n");
    s.append("| Divergence groups | ")
        .append(newGroups)
        .append(" | ")
        .append(groups.size() - newGroups)
        .append(" |\n\n");
    s.append(compilations()).append('\n');
    int rewriteFailures =
        counters.entrySet().stream()
            .filter(e -> e.getKey().endsWith(": " + REWRITE_FAILED))
            .mapToInt(Map.Entry::getValue)
            .sum();
    if (rewriteFailures > 0) {
      s.append("A relation or the mutator threw ")
          .append(rewriteFailures)
          .append(" times; the job log has the stack traces.\n\n");
    }
    s.append("### New crashes\n\n");
    if (newCrashes.isEmpty()) {
      s.append("None.\n");
    }
    for (Crash crash : newCrashes) {
      s.append("- ")
          .append(code(crash.signature()))
          .append(", ")
          .append(crash.occurrences())
          .append(" inputs, smallest under `")
          .append(crash.config())
          .append("`: ")
          .append(crash.origin())
          .append(" (")
          .append(crashDirectory(crash))
          .append(")\n");
    }
    s.append("\n### New divergence groups\n\n");
    int shown = 0;
    for (Map.Entry<GroupKey, Group> e : groups.entrySet()) {
      if (e.getValue().verdict() != null) {
        continue;
      }
      if (++shown > 30) {
        s.append("- and ").append(newGroups - 30).append(" more in report.md\n");
        break;
      }
      GroupKey k = e.getKey();
      s.append("- ")
          .append(k.relation())
          .append(", ")
          .append(code(k.seed()))
          .append(", only ")
          .append(k.side())
          .append(": ")
          .append(code(k.template()))
          .append(" (")
          .append(e.getValue().example())
          .append(")\n");
    }
    if (shown == 0) {
      s.append("None.\n");
    }
    s.append(
        "\nThe report artifact holds report.md, the sources of every finding, and new-findings.tsv,"
            + " the new findings as lines for known-findings.tsv.\n");
    return s.toString();
  }

  /** Returns the full report. */
  private String report(int done, int selected, Duration elapsed) {
    StringBuilder r = new StringBuilder("# NullAway oracles\n\n");
    r.append(header(done, selected, elapsed));
    r.append(compilations());

    r.append("\n## New crashes\n\n");
    crashList(r, newCrashes().toList());
    r.append("\n## Known crashes\n\n");
    crashList(
        r, crashes.values().stream().filter(c -> known.crash(c.signature()).isPresent()).toList());

    r.append("\n## New divergence groups\n\n");
    groupTable(r, false);
    r.append("\n## Known divergence groups\n\n");
    groupTable(r, true);

    r.append("\n## Known findings not seen in this run\n\n");
    ImmutableList<KnownFindings.Entry> unmatched = known.unmatched();
    if (unmatched.isEmpty()) {
      r.append("None.\n");
    }
    for (KnownFindings.Entry entry : unmatched) {
      r.append("- line ")
          .append(entry.line())
          .append(": ")
          .append(code(entry.key().replace('\t', ' ')))
          .append(", ")
          .append(entry.verdict())
          .append('\n');
    }
    r.append(
        "\nA finding goes unseen when it is fixed, when no mutant of this run reproduces it, when"
            + " the run stopped at its time budget, or when the arguments left out its seed,"
            + " configuration, or relation.\n");

    r.append("\n## Counters\n\n");
    counters.forEach((k, v) -> r.append("- ").append(k).append(": ").append(v).append('\n'));
    r.append("\n## Seconds by kind of run\n\n");
    millis.forEach((k, v) -> r.append("- ").append(k).append(": ").append(v / 1000).append('\n'));
    return r.toString();
  }

  private String header(int done, int selected, Duration elapsed) {
    return "JDK "
        + Runtime.version()
        + ", "
        + done
        + " of "
        + selected
        + " seeds"
        + (done < selected ? " (stopped at the time budget)" : "")
        + ", "
        + elapsed.toMinutes()
        + " min "
        + elapsed.toSecondsPart()
        + " s.\n\n";
  }

  /** Returns a table of NullAway runs by kind and outcome, and of comparisons by relation. */
  private String compilations() {
    StringBuilder t =
        new StringBuilder("| Kind | OK | INVALID | CRASH | Agree | Diverge | Seconds |\n");
    t.append("| --- | --- | --- | --- | --- | --- | --- |\n");
    Set<String> kinds = new TreeSet<>();
    counters.keySet().forEach(k -> kinds.add(k.substring(0, k.indexOf(':'))));
    for (String kind : kinds) {
      t.append("| ").append(kind);
      for (String column : List.of("OK", "INVALID", "CRASH", "agree", "DIVERGE")) {
        t.append(" | ").append(counters.getOrDefault(kind + ": " + column, 0));
      }
      t.append(" | ").append(millis.getOrDefault(kind, 0L) / 1000).append(" |\n");
    }
    return t.toString();
  }

  private void crashList(StringBuilder r, List<Crash> list) {
    if (list.isEmpty()) {
      r.append("None.\n");
    }
    for (Crash crash : list) {
      Optional<Verdict> verdict = known.crash(crash.signature());
      r.append("- [")
          .append(crash.signature())
          .append("](")
          .append(crashDirectory(crash))
          .append("/README.md)")
          .append(verdict.map(v -> ", " + v).orElse(""))
          .append(", ")
          .append(crash.occurrences())
          .append(" inputs, config `")
          .append(crash.config())
          .append("`\n");
    }
  }

  private void groupTable(StringBuilder r, boolean knownGroups) {
    StringBuilder rows = new StringBuilder();
    groups.forEach(
        (k, g) -> {
          if ((g.verdict() != null) == knownGroups) {
            rows.append("| ")
                .append(k.relation())
                .append(" | ")
                .append(code(k.seed()))
                .append(" | ")
                .append(k.side())
                .append(" | ")
                .append(code(k.template()))
                .append(" | ")
                .append(String.join(", ", g.configs()))
                .append(" | [")
                .append(g.example())
                .append("](")
                .append(g.example())
                .append("/README.md) |")
                .append(knownGroups ? " " + g.verdict() + " |" : "")
                .append('\n');
          }
        });
    if (rows.length() == 0) {
      r.append("None.\n");
      return;
    }
    r.append("| Relation | Seed | Only in | Template | Configurations | Example |")
        .append(knownGroups ? " Verdict |\n" : "\n")
        .append("| --- | --- | --- | --- | --- | --- |")
        .append(knownGroups ? " --- |\n" : "\n")
        .append(rows);
  }

  /** Returns {@code text} as Markdown inline code that is safe in a table cell. */
  private static String code(String text) {
    return "`" + text.replace('`', '\'').replace("|", "\\|") + "`";
  }

  private static String bullets(List<String> lines) {
    StringBuilder result = new StringBuilder();
    lines.forEach(l -> result.append("- `").append(l).append("`\n"));
    return result.length() == 0 ? "- nothing\n" : result.toString();
  }

  private static void writeSources(Path dir, List<Source> sources) throws IOException {
    for (Source s : sources) {
      Path file = dir.resolve(s.path());
      Files.createDirectories(file.getParent());
      Files.writeString(file, s.text());
    }
  }
}
