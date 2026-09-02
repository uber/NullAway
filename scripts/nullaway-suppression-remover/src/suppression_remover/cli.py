# Copyright (c) 2026 Uber Technologies, Inc.
#
# Permission is hereby granted, free of charge, to any person obtaining a copy
# of this software and associated documentation files (the "Software"), to deal
# in the Software without restriction, including without limitation the rights
# to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
# copies of the Software, and to permit persons to whom the Software is
# furnished to do so, subject to the following conditions:
#
# The above copyright notice and this permission notice shall be included in
# all copies or substantial portions of the Software.
#
# THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
# IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
# FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
# AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
# LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
# OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
# THE SOFTWARE.

"""Command-line interface for the suppression remover."""

import argparse
import sys
from pathlib import Path

from . import core


def positive_int(value: str) -> int:
    """Parse a command-line value that must be a positive integer."""
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a positive integer") from error
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def parse_args(input_args=None):
    parser = argparse.ArgumentParser(
        description="Remove unnecessary @SuppressWarnings annotations for a checker.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Examples:\n"
            "  # Gradle project, targeting the 'caffeine' submodule\n"
            "  suppression-remover caffeine NullAway --alt-suppression NullAway.Init\n"
            "\n"
            "  # Explicit module path and project root\n"
            "  suppression-remover ./submodule NullAway --project-root /path/to/repo\n"
            "\n"
            "  # Custom build command\n"
            '  suppression-remover . MyChecker --build-cmd "./gradlew build --continue"'
        ),
    )
    parser.add_argument(
        "module",
        help=(
            "Module directory to process (relative to project root). "
            "Use '.' to process every src tree under the project root; "
            "this requires --build-cmd."
        ),
    )
    parser.add_argument(
        "checker",
        help="Checker name as it appears in build errors (e.g. NullAway)",
    )
    parser.add_argument(
        "--alt-suppression",
        action="append",
        default=[],
        dest="alt_suppressions",
        metavar="ALT",
        help=(
            "Alternate suppression string for the same checker (may be repeated). "
            "Annotations containing this string are also treated as suppressing "
            "the checker and will be removed if unnecessary."
        ),
    )
    parser.add_argument(
        "--project-root",
        default=None,
        metavar="DIR",
        help=(
            "Project root directory. Auto-detected if not provided "
            "(walks up from cwd looking for build files)."
        ),
    )
    parser.add_argument(
        "--build-cmd",
        default=None,
        metavar="CMD",
        help=(
            "Custom build command. Overrides the default Gradle invocation. "
            "Must compile the target module (or all modules when MODULE is '.') "
            "and exit non-zero on errors."
        ),
    )
    parser.add_argument(
        "--max-iterations",
        type=positive_int,
        default=core.MAX_ITERATIONS,
        metavar="N",
        help=(
            "Maximum number of restore-and-verify iterations after the initial "
            f"build (default: {core.MAX_ITERATIONS})."
        ),
    )
    return parser.parse_args(input_args)


def main(input_args=None):
    args = parse_args(input_args)

    if args.project_root:
        project_root = Path(args.project_root).resolve()
    else:
        project_root = core.find_project_root()

    module_dir = (project_root / args.module).resolve()
    if not module_dir.is_dir():
        sys.exit(f"Module directory does not exist: {module_dir}")

    build_cmd = None
    if args.build_cmd:
        import shlex

        build_cmd = shlex.split(args.build_cmd)

    if module_dir == project_root and build_cmd is None:
        sys.exit("Processing the project root requires --build-cmd.")

    core.run(
        module_dir,
        args.checker,
        args.alt_suppressions,
        project_root,
        build_cmd=build_cmd,
        max_iterations=args.max_iterations,
    )


if __name__ == "__main__":
    main()
