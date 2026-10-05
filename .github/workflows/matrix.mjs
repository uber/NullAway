// Builds the job matrix for continuous-integration.yml.
//
// The fixed matrix this replaces ran three jobs, one per operating system, and each of them ran
// every test suite on all five test JVMs: fifteen runs of each suite per pull request, all in the
// same locale, with the same JVM defaults. Here the coverage job keeps every test JVM, the other
// jobs run one each, and the configurations below spread over the jobs that run anyway. The axes
// guard against problems NullAway does not have today: a check that passes on every row now is
// what fails the pull request that introduces one.
//
// Preview the rows, and the coverage a budget buys:
//     cd .github/workflows && npm ci && RNG_SEED=1 node matrix.mjs
//     cd .github/workflows && RNG_SEED=1 node matrix.mjs --coverage
import { createGitHubMatrixBuilder, setGitHubOutput } from '@vlsi/github-actions-random-matrix/github';

// The seed comes from RNG_SEED, then from the pull request number, and is written to the job
// summary. continuous-integration.yml passes both, so every push to a pull request draws the
// same rows, and a failing row can be replayed from the Actions UI or on a laptop.
const { matrix } = createGitHubMatrixBuilder();

matrix.failOnUnsatisfiableFilters(true);

matrix.addAxis({
  name: 'os',
  title: x => x.value.replace('-latest', ''),
  values: [
    // Every operating system runs at least once, because the require list below says so.
    // These weights decide where the remaining rows land: GitHub bills Windows minutes at twice
    // the Linux rate and macOS at ten times, and both are slower per job.
    { value: 'ubuntu-latest', weight: 4 },
    { value: 'windows-latest', weight: 1 },
    { value: 'macos-latest', weight: 1 },
  ],
});

// The JDK that runs Gradle and the default `test` task. build.gradle refuses to run on anything
// below 21, so the axis starts there.
matrix.addAxis({
  name: 'java_version',
  title: x => 'Java ' + x,
  values: [
    '21',
    '25',
  ],
});

// The JDK-specific test task a row runs besides `test`, passed to Gradle as -PtestJdks. JDK 17
// also runs testErrorProneOldest, which runs on JDK 17 as well. The coverage row ignores this
// and runs every one of them.
matrix.addAxis({
  name: 'test_jdk',
  title: x => 'tests on ' + x,
  values: [
    '17',
    '21',
    '27',
    '28',
  ],
});

// The vendor of the JDKs that run Gradle and the tests on JDK 17 and 21. Temurin, Zulu, Corretto
// and Liberica are HotSpot builds of the same sources; Semeru runs OpenJ9, a different VM.
// NullAway reaches into javac through --add-opens and the build runs Lombok, which calls
// sun.misc.Unsafe, and that kind of code depends on the VM.
matrix.addAxis({
  name: 'java_distribution',
  values: [
    'temurin',
    'zulu',
    'corretto',
    'liberica',
    'semeru',
  ],
});

// Identity hash codes decide the iteration order of every HashMap and HashSet keyed on a javac
// Symbol. -XX:hashCode=2 makes every identity hash the constant 1, so those maps degenerate to
// insertion order, and a test whose result depends on that order fails on this row. See
// https://github.com/uber/NullAway/issues/1780.
matrix.addAxis({
  name: 'hash',
  values: [
    { value: 'regular', title: '', weight: 4 },
    { value: 'same', title: 'same hashcode', weight: 1 },
  ],
});

// Gradle runs every Test task with -ea, while javac, and so a build that runs NullAway, does not.
// The Checker Framework dataflow library NullAway builds on has assert statements, so this axis
// runs the tests the way a build runs NullAway.
matrix.addAxis({
  name: 'assertions',
  values: [
    { value: 'on', title: '', weight: 2 },
    { value: 'off', title: 'assertions off', weight: 1 },
  ],
});

// NullAway lowercases and formats type names, option values and messages, and tests match on
// messages. Turkish lowercases I to a dotless i, which breaks a case-insensitive comparison
// written against the default locale.
//
// de_DE, ja_JP and zh_CN are left out for now: javac translates its messages into them, and
// CompilationTestHelper recognizes a compiler crash only by the English banner, so in those
// locales four tests in ErrorProneCLIFlagsConfigTest fail and a test in which NullAway fails to
// start passes.
matrix.addAxis({
  name: 'locale',
  title: x => x.language + '_' + x.country,
  values: [
    { language: 'en', country: 'US' },
    { language: 'tr', country: 'TR' },
    { language: 'ru', country: 'RU' },
  ],
});

matrix.setNamePattern([
  'java_version', 'java_distribution', 'os', 'test_jdk', 'hash', 'assertions', 'locale',
]);

// testJdk21 on a row whose Gradle JVM is 21 repeats the default `test` task.
matrix.exclude({ java_version: '21', test_jdk: '21' });
// OpenJ9 accepts -XX:hashCode and ignores it, so that row would check nothing.
matrix.exclude({ java_distribution: 'semeru', hash: { value: 'same' } });
// Semeru runs Gradle on 21 only, the combination checked with the compiler on Temurin 25. With
// Gradle on Semeru 25, Gradle could pick that JDK as the compile toolchain as well.
matrix.exclude({ java_distribution: 'semeru', java_version: '25' });

const include = matrix.generateRows(Number(process.env.MATRIX_JOBS || 4), {
  require: [
    // The configuration the fixed matrix ran on Linux. It is the one job that uploads coverage,
    // the one that runs every test JVM, and the one that writes the Gradle cache, so everything
    // that moves any of those is pinned: the coverage number stays comparable between runs, and
    // the cache key stays the same one from run to run.
    {
      filter: {
        os: { value: 'ubuntu-latest' },
        java_version: '25',
        java_distribution: 'temurin',
        hash: { value: 'regular' },
        assertions: { value: 'on' },
        locale: { language: 'en' },
      },
      tag: row => { row.collectCoverage = true; },
    },
    // Every operating system, as before.
    ...matrix.allAxisValues('os'),
    // Both JDKs Gradle runs on.
    ...matrix.allAxisValues('java_version'),
    // At least one job with degenerate identity hash codes.
    { hash: { value: 'same' } },
    // At least one job without assertions.
    { assertions: { value: 'off' } },
  ],
});

if (include.length === 0) {
  throw new Error('Matrix list is empty');
}

include.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));

include.forEach(row => {
  const jvmArgs = [];
  if (row.hash.value === 'same') {
    jvmArgs.push('-XX:+UnlockExperimentalVMOptions', '-XX:hashCode=2');
  }
  if (row.assertions.value === 'off') {
    // Gradle maps -da to enableAssertions = false.
    jvmArgs.push('-da');
  }
  // Gradle itself does not run in the tr_TR locale (https://github.com/gradle/gradle/issues/17361),
  // so the locale reaches the test JVM only.
  jvmArgs.push(`-Duser.language=${row.locale.language}`, `-Duser.country=${row.locale.country}`);
  row.testExtraJvmArgs = jvmArgs.join(' ');
  // An empty value runs every JDK-specific test task.
  row.testJdks = row.collectCoverage ? '' : row.test_jdk;
  if (row.collectCoverage) {
    row.name = row.name.replace(`tests on ${row.test_jdk}`, 'tests on every JDK');
  }
  // runs-on takes the plain string, and the axis objects have served their purpose.
  row.os = row.os.value;
  delete row.test_jdk;
  delete row.hash;
  delete row.assertions;
  delete row.locale;
});

if (process.argv.includes('--coverage')) {
  const coverage = matrix.pairCoverageReport();
  console.log(
    `Pair coverage: ${coverage.covered}/${coverage.total} (${coverage.percentage}%), weighted ${coverage.weightPercentage}%`);
} else {
  console.log(include);
  setGitHubOutput('matrix', { include });
}
