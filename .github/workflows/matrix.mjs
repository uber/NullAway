// Builds the job matrix for continuous-integration.yml.
//
// The fixed matrix this replaces ran three jobs: one per operating system, all on the same JDK,
// in the same locale, with the same JVM defaults. The axes below are the ones NullAway has
// already been bitten by or is plausibly sensitive to; pairwise selection keeps the job count
// near where it was while covering their product rather than their sum.
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
    // These weights decide how many of the remaining rows land on each: GitHub bills Windows
    // minutes at twice the Linux rate and macOS at ten times, and both are slower per job.
    { value: 'ubuntu-latest', weight: 4 },
    { value: 'windows-latest', weight: 1 },
    { value: 'macos-latest', weight: 1 },
  ],
});

// The JDK that runs Gradle and the default `test` task. build.gradle refuses to run on
// anything below 21, so the axis starts there. Nothing is lost by leaving 17 out: `check`
// depends on testJdk17, testJdk21, testJdk26 and testJdk28, so every row runs the suite on
// all four through toolchains whatever this value is.
matrix.addAxis({
  name: 'java_version',
  title: x => 'Java ' + x,
  values: [
    '21',
    '25',
  ],
});

// The vendor of every JDK the job installs, toolchains included, so the axis reaches the
// process that runs the tests rather than only the one that launches it. Distributions of the
// same release differ in their build and their backport level, and NullAway reads javac
// through its internal API, where that difference is visible.
matrix.addAxis({
  name: 'java_distribution',
  values: [
    'temurin',
    'zulu',
    'corretto',
    'liberica',
  ],
});

// Identity hash codes decide the iteration order of every HashMap and HashSet keyed on a javac
// Symbol, and NullAway keys several on one. -XX:hashCode=2 makes every identity hash the constant
// 1, so those maps degenerate to insertion order; a result that changes under it does not depend
// on the program being checked. See https://github.com/uber/NullAway/issues/1780.
matrix.addAxis({
  name: 'hash',
  values: [
    { value: 'regular', title: '', weight: 4 },
    { value: 'same', title: 'same hashcode', weight: 1 },
  ],
});

// -ea turns on the assertions in javac, in the Checker Framework dataflow library NullAway
// builds on, and in NullAway itself. It costs only the jobs that carry it.
matrix.addAxis({
  name: 'assertions',
  title: x => x.value === 'yes' ? 'assertions' : '',
  values: [
    { value: 'yes', weight: 2 },
    { value: 'no', weight: 4 },
  ],
});

// Reverses the compilation units of each multi-file test and requires the same diagnostics.
// The order a build system hands source files to javac is not part of the program, and NullAway
// has depended on it before: https://github.com/uber/NullAway/issues/1725 and
// https://github.com/uber/NullAway/issues/1733. Costs one extra compilation per multi-file test
// in the rows that carry it.
matrix.addAxis({
  name: 'permute_sources',
  values: [
    { value: 'no', title: '', weight: 3 },
    { value: 'yes', title: 'permuted sources', weight: 1 },
  ],
});

// NullAway lowercases and formats type names, option values and messages.
matrix.addAxis({
  name: 'locale',
  title: x => x.language + '_' + x.country,
  values: [
    { language: 'en', country: 'US' },
    // Turkish lowercases I to a dotless i, which breaks a case-insensitive comparison written
    // against the default locale.
    { language: 'tr', country: 'TR' },
    { language: 'de', country: 'DE' },
    { language: 'ru', country: 'RU' },
  ],
});

matrix.setNamePattern([
  'java_version', 'java_distribution', 'os', 'hash', 'assertions', 'permute_sources', 'locale',
]);

const include = matrix.generateRows(Number(process.env.MATRIX_JOBS || 8), {
  require: [
    // The configuration the fixed matrix ran. It is the one job that uploads coverage and the
    // one that writes the Gradle cache, so everything that moves either is pinned: the number
    // stays comparable between runs, and the cache key stays the same one from run to run.
    {
      filter: {
        os: { value: 'ubuntu-latest' },
        java_version: '25',
        java_distribution: 'temurin',
        hash: { value: 'regular' },
        assertions: { value: 'no' },
        permute_sources: { value: 'no' },
        locale: { language: 'en' },
      },
      tag: row => { row.collectCoverage = true; },
    },
    // Every operating system, as before.
    ...matrix.allAxisValues('os'),
    // Both JDKs the build runs on.
    ...matrix.allAxisValues('java_version'),
    // At least one job with degenerate identity hash codes.
    { hash: { value: 'same' } },
    // At least one job with assertions on.
    { assertions: { value: 'yes' } },
    // At least one job that reverses the compilation units.
    { permute_sources: { value: 'yes' } },
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
  if (row.assertions.value === 'yes') {
    jvmArgs.push('-ea');
  }
  // Gradle itself does not run in the tr_TR locale (https://github.com/gradle/gradle/issues/17361),
  // so the locale reaches the test JVM only.
  jvmArgs.push(`-Duser.language=${row.locale.language}`, `-Duser.country=${row.locale.country}`);
  row.testExtraJvmArgs = jvmArgs.join(' ');
  row.permuteSources = row.permute_sources.value === 'yes';
  // runs-on takes the plain string, and the axis objects have served their purpose.
  row.os = row.os.value;
  delete row.hash;
  delete row.assertions;
  delete row.locale;
  delete row.permute_sources;
});

if (process.argv.includes('--coverage')) {
  const coverage = matrix.pairCoverageReport();
  console.log(
    `Pair coverage: ${coverage.covered}/${coverage.total} (${coverage.percentage}%), weighted ${coverage.weightPercentage}%`);
} else {
  console.log(include);
  setGitHubOutput('matrix', { include });
}
