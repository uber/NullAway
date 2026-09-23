# NullAway suppression remover

`suppression-remover` removes unnecessary checker entries from Java
`@SuppressWarnings` annotations. It temporarily removes every matching suppression,
compiles the target module, and restores the suppressions required to make the build
pass. The remaining source changes are the suppressions that the tool determined were
unnecessary.  The technique is a heuristic, and is not guaranteed to remove all
unnecessary suppressions.

Although it was written for NullAway, the tool could be used with another Error
Prone checker whose diagnostics use the standard `[CheckerName]` format (this has
not been tested).

## Prerequisites

- Python 3.10 or newer
- A working build for the project being processed
- For the default build behavior, a Gradle project with either `gradlew` at its
  root or `gradle` on `PATH`

Install the command from this directory. Using a virtual environment keeps its
Python dependencies isolated:

```bash
cd scripts/nullaway-suppression-remover
python3 -m venv /tmp/nullaway-suppression-remover-venv
source /tmp/nullaway-suppression-remover-venv/bin/activate
python -m pip install -e .
```

The `suppression-remover` command is then available whenever that virtual
environment is active.

## Before running it

The tool edits Java source files in place and does not have a dry-run mode. Run it
from a clean Git worktree so that you can inspect or discard its changes. Also run
the relevant build once before using the tool; pre-existing build failures can
prevent it from determining which suppressions are needed.

Avoid interrupting the process while a build is running. The tool normally restores
files when it cannot produce a passing build, but an external interruption may leave
the temporary edits in place.

## Usage

From anywhere inside the project to process, run:

```text
suppression-remover MODULE CHECKER [OPTIONS]
```

`MODULE` is the module directory relative to the project root, and `CHECKER` is
the name printed in compiler diagnostics. For example, from the root of this
repository:

```bash
suppression-remover nullaway NullAway --alt-suppression NullAway.Init
```

This scans every `.java` file below `nullaway/src`, treats both `NullAway` and
`NullAway.Init` as NullAway suppressions, and updates unnecessary annotations.

To process a whole project, including all of its submodules, use `.` for the module
and provide a build command that compiles everything:

```bash
suppression-remover . NullAway --build-cmd "./gradlew build --continue"
```

In this mode, the tool scans for Java files beneath every directory named `src`
under the project root. It trusts that the supplied build command compiles all of
those sources and reports checker errors for them. This mode also works for a
single-module project. A custom build command is required when the module is `.`.

After the command succeeds, inspect the resulting changes and run the project's
normal test or verification command before committing them.

### Options

`--alt-suppression ALT`
: Treat `ALT` as another suppression name for the same checker. The option can be
  repeated, for example:

  ```bash
  suppression-remover app NullAway \
    --alt-suppression NullAway.Init \
    --alt-suppression NullAway.Optional
  ```

`--project-root DIR`
: Use `DIR` as the project root. Without this option, the tool walks upward from
  the current directory and selects the first directory containing one of
  `settings.gradle`, `settings.gradle.kts`, `build.gradle`, `build.gradle.kts`, or
  `pom.xml`.

  ```bash
  suppression-remover subprojects/service NullAway --project-root /path/to/project
  ```

`--build-cmd CMD`
: Run `CMD` instead of the generated Gradle command. The command must compile all
  source sets that should be checked, emit standard Java/Error Prone diagnostics,
  and exit nonzero when checker errors occur.

  This option is required for Maven and other non-Gradle builds, for processing a
  whole project with `MODULE` set to `.`, and for unusual Gradle layouts:

  ```bash
  suppression-remover service NullAway \
    --build-cmd "mvn -pl service test-compile"
  ```

  The value is split into arguments using shell-like quoting, but it is not run
  through a shell. Shell operators such as `&&`, pipes, redirections, and environment
  assignments are therefore not supported; put complex build logic in a script and
  pass that script as the command instead.

`--max-iterations N`
: Set the maximum number of restore-and-verify iterations after the initial build.
  The default is `5`, and the value must be a positive integer. Increase it for
  codebases where nested or interacting suppressions require more refinement passes,
  or when removing suppressions across many modules:

  ```bash
  suppression-remover . NullAway \
    --build-cmd "./gradlew build --continue" \
    --max-iterations 10
  ```

Use `suppression-remover --help` to display the command-line help.

## Default Gradle build

For a specific non-root module, when `--build-cmd` is omitted, the tool discovers
source sets containing Java files under `MODULE/src` and runs their compile tasks
with `--continue`. For a module named `service` with `main` and `test` source sets,
the generated command is equivalent to:

```bash
./gradlew :service:compileJava :service:compileTestJava --continue
```

A custom source set such as `testFixtures` is mapped to
`compileTestFixturesJava`.

## What gets changed

Only `@SuppressWarnings` annotations containing an exact matching string are
considered. If the target checker is the only entry, the entire annotation is
removed. If an annotation also suppresses other checkers, it is rewritten with
those entries retained. For example:

```java
@SuppressWarnings({"NullAway", "deprecation"})
```

becomes:

```java
@SuppressWarnings("deprecation")
```

The tool parses Java syntax, so text in comments and string literals is ignored.
When it removes one value from a multiline annotation, it preserves the annotation's
multiline layout and indentation. It may normalize a single-line multi-checker
annotation that it updates.  Trailing comments are removed.

## Running the tool's tests

Install the test dependency and run `pytest` from this directory:

```bash
python -m pip install -e '.[test]'
python -m pytest
```
