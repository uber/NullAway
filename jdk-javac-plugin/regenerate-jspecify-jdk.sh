#!/usr/bin/env bash
set -euo pipefail

readonly JDK_REPOSITORY=https://github.com/jspecify/jdk.git
readonly JDK_REVISION=e89cf6d97e5df874be7d9db4f0e14cc0c0a20648

usage() {
  cat <<EOF
Usage: $(basename "$0") [--jdk-dir PATH] [--boot-jdk PATH] [--keep-temp] [--verbose]

Regenerate nullaway/src/main/resources/jspecify-jdk.astubx and run NullAway tests.

  --jdk-dir PATH   Use an existing clean checkout at $JDK_REVISION.
                  Otherwise, fetch a shallow checkout into a temporary directory.
  --boot-jdk PATH  Pass this JDK to configure as the boot JDK (e.g., JDK 27).
                  Otherwise, let configure discover a compatible installed JDK.
  --keep-temp     Keep the temporary checkout, build, JSON files, and logs on success.
  --verbose       Stream command output to the terminal as well as saving logs.
  -h, --help      Show this help.

Temporary files are always retained on failure. The existing checkout is not
modified; configuration and build output go into a fresh temporary directory.
EOF
}

die() {
  printf 'Error: %s\n' "$*" >&2
  exit 1
}

jdk_dir=
boot_jdk=
keep_temp=false
verbose=false
while (($#)); do
  case "$1" in
    --jdk-dir|--boot-jdk)
      (($# >= 2)) || die "$1 requires a path"
      case "$1" in
        --jdk-dir) jdk_dir=$2 ;;
        --boot-jdk) boot_jdk=$2 ;;
      esac
      shift 2
      ;;
    --keep-temp) keep_temp=true; shift ;;
    --verbose) verbose=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "Unknown argument: $1" ;;
  esac
done

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
nullaway_root=$(cd -- "$script_dir/.." && pwd -P)

if [[ -n "$jdk_dir" ]]; then
  [[ -d "$jdk_dir" ]] || die "JDK checkout directory does not exist: $jdk_dir"
  jdk_dir=$(cd -- "$jdk_dir" && pwd -P)
  actual_revision=$(git -C "$jdk_dir" rev-parse HEAD)
  [[ "$actual_revision" == "$JDK_REVISION" ]] ||
    die "Expected JDK checkout at $JDK_REVISION; found $actual_revision"
  git -C "$jdk_dir" diff --quiet HEAD -- || die "JDK checkout has tracked changes"
fi

if [[ -n "$boot_jdk" ]]; then
  [[ -x "$boot_jdk/bin/java" && -x "$boot_jdk/bin/javac" ]] ||
    die "Boot JDK must contain bin/java and bin/javac: $boot_jdk"
  boot_jdk=$(cd -- "$boot_jdk" && pwd -P)
fi

work_dir=$(mktemp -d "${TMPDIR:-/tmp}/nullaway-jdk.XXXXXX")
work_dir=$(cd -- "$work_dir" && pwd -P)

# Retain failed builds for diagnosis; remove only our own temporary directory.
cleanup() {
  local status=$?
  if ((status != 0)); then
    printf 'Regeneration failed. Build files and logs retained at %s\n' "$work_dir" >&2
  elif [[ "$keep_temp" == true ]]; then
    printf 'Temporary files retained at %s\n' "$work_dir"
  else
    rm -rf -- "$work_dir"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Save tool output, optionally stream it, and report command failures.
run_logged() {
  local log_file=$1
  shift
  if [[ "$verbose" == true ]]; then
    if "$@" 2>&1 | tee "$log_file"; then
      return 0
    fi
  elif "$@" >"$log_file" 2>&1; then
    return 0
  fi
  printf 'Command failed; see %s\n' "$log_file" >&2
  if [[ "$verbose" != true ]]; then
    tail -n 60 "$log_file" >&2
  fi
  return 1
}

if [[ -z "$jdk_dir" ]]; then
  printf 'Fetching JSpecify JDK at %s\n' "$JDK_REVISION"
  jdk_dir=$work_dir/jdk
  git init -q "$jdk_dir"
  git -C "$jdk_dir" remote add origin "$JDK_REPOSITORY"
  run_logged "$work_dir/fetch.log" git -C "$jdk_dir" fetch --depth=1 origin "$JDK_REVISION"
  git -C "$jdk_dir" checkout -q --detach FETCH_HEAD
fi
[[ -f "$jdk_dir/configure" ]] || die "Missing JDK configure script: $jdk_dir/configure"

cd -- "$nullaway_root"
printf 'Building the serializer and astubx generator\n'
run_logged "$work_dir/gradle-build.log" ./gradlew \
  :jdk-javac-plugin:shadowJar :jdk-annotations:astubx-generator-cli:shadowJar

build_dir=$work_dir/build
json_dir=$work_dir/json
mkdir "$build_dir" "$json_dir"
configure_args=(--enable-javac-server)
if [[ -n "$boot_jdk" ]]; then
  configure_args+=("--with-boot-jdk=$boot_jdk")
fi

printf 'Configuring JDK in %s\n' "$build_dir"
(
  cd -- "$build_dir"
  run_logged "$work_dir/configure.log" bash "$jdk_dir/configure" "${configure_args[@]}"
)
printf 'include %s/jdk-build-hooks/custom-spec.gmk\n' "$script_dir" >"$build_dir/custom-spec.gmk"

printf 'Building JDK with the serializer (log: %s/build.log)\n' "$work_dir"
run_logged "$work_dir/build.log" make -C "$build_dir" jdk "NULLAWAY_JDK_JSON_DIR=$json_dir"

printf 'Generating jspecify-jdk.astubx\n'
run_logged "$work_dir/generator.log" "$build_dir/jdk/bin/java" -jar \
  "$nullaway_root/jdk-annotations/astubx-generator-cli/build/libs/astubx-generator-cli.jar" \
  "$json_dir" "$work_dir/generated"
[[ -s "$work_dir/generated/output.astubx" ]] || die "Generator produced no astubx file"
cp "$work_dir/generated/output.astubx" "$nullaway_root/nullaway/src/main/resources/jspecify-jdk.astubx"

printf 'Running the main NullAway tests and self-check\n'
run_logged "$work_dir/gradle-test.log" ./gradlew :nullaway:test :nullaway:buildWithNullAway
printf 'Updated nullaway/src/main/resources/jspecify-jdk.astubx; tests and self-check passed.\n'
