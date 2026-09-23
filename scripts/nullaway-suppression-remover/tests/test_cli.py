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

import tempfile
from pathlib import Path
from unittest import TestCase
from unittest.mock import patch

from suppression_remover.cli import main, parse_args


class TestParseArgs(TestCase):
    def test_positional_args_parsed(self):
        args = parse_args(["mymodule", "NullAway"])
        self.assertEqual(args.module, "mymodule")
        self.assertEqual(args.checker, "NullAway")
        self.assertEqual(args.alt_suppressions, [])

    def test_alt_suppression_single(self):
        args = parse_args(
            ["mymodule", "NullAway", "--alt-suppression", "NullAway.Init"]
        )
        self.assertEqual(args.alt_suppressions, ["NullAway.Init"])

    def test_alt_suppression_multiple(self):
        args = parse_args(
            [
                "mymodule",
                "NullAway",
                "--alt-suppression",
                "NullAway.Init",
                "--alt-suppression",
                "NullAway.Other",
            ]
        )
        self.assertEqual(args.alt_suppressions, ["NullAway.Init", "NullAway.Other"])

    def test_missing_checker_raises(self):
        with self.assertRaises(SystemExit):
            parse_args(["mymodule"])

    def test_missing_all_args_raises(self):
        with self.assertRaises(SystemExit):
            parse_args([])

    def test_project_root_default_is_none(self):
        args = parse_args(["mymodule", "NullAway"])
        self.assertIsNone(args.project_root)

    def test_project_root_parsed(self):
        args = parse_args(["mymodule", "NullAway", "--project-root", "/tmp/proj"])
        self.assertEqual(args.project_root, "/tmp/proj")

    def test_build_cmd_default_is_none(self):
        args = parse_args(["mymodule", "NullAway"])
        self.assertIsNone(args.build_cmd)

    def test_build_cmd_parsed(self):
        args = parse_args(["mymodule", "NullAway", "--build-cmd", "make compile"])
        self.assertEqual(args.build_cmd, "make compile")

    def test_max_iterations_defaults_to_core_default(self):
        args = parse_args(["mymodule", "NullAway"])
        self.assertEqual(args.max_iterations, 5)

    def test_max_iterations_parsed(self):
        args = parse_args(["mymodule", "NullAway", "--max-iterations", "12"])
        self.assertEqual(args.max_iterations, 12)

    def test_max_iterations_must_be_positive(self):
        for value in ("0", "-1", "invalid"):
            with self.subTest(value=value), self.assertRaises(SystemExit):
                parse_args(["mymodule", "NullAway", "--max-iterations", value])

    def test_dot_module_for_project_root(self):
        args = parse_args([".", "NullAway"])
        self.assertEqual(args.module, ".")


class TestMain(TestCase):
    def test_rejects_module_outside_project_root(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            parent = Path(tmpdir).resolve()
            root = parent / "project"
            outside = parent / "outside"
            root.mkdir()
            outside.mkdir()

            for module in ("../outside", str(outside)):
                with self.subTest(module=module):
                    with (
                        patch(
                            "suppression_remover.cli.core.find_project_root",
                            return_value=root,
                        ),
                        patch("suppression_remover.cli.core.run") as mock_run,
                    ):
                        with self.assertRaisesRegex(
                            SystemExit, "outside the project root"
                        ):
                            main([module, "NullAway"])
                        mock_run.assert_not_called()

    def test_project_root_requires_custom_build_command(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir).resolve()
            with patch(
                "suppression_remover.cli.core.find_project_root", return_value=root
            ):
                with self.assertRaisesRegex(
                    SystemExit, "project root requires --build-cmd"
                ):
                    main([".", "NullAway"])

    def test_project_root_accepts_custom_build_command(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir).resolve()
            with (
                patch(
                    "suppression_remover.cli.core.find_project_root", return_value=root
                ),
                patch("suppression_remover.cli.core.run") as mock_run,
            ):
                main([".", "NullAway", "--build-cmd", "./gradlew build --continue"])

            mock_run.assert_called_once_with(
                root,
                "NullAway",
                [],
                root,
                build_cmd=["./gradlew", "build", "--continue"],
                max_iterations=5,
            )
