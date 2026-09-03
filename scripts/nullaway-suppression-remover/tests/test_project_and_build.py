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

import os
import tempfile
from pathlib import Path
from unittest import TestCase
from unittest.mock import MagicMock, patch

from suppression_remover.core import (
    discover_source_sets,
    find_project_root,
    get_source_files,
    run_build,
)


class TestFindProjectRoot(TestCase):
    def _chdir(self, path):
        old = os.getcwd()
        os.chdir(path)
        return old

    def test_settings_gradle_kts_in_cwd(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            (root / "settings.gradle.kts").touch()
            old = self._chdir(root)
            try:
                self.assertEqual(find_project_root(), root.resolve())
            finally:
                os.chdir(old)

    def test_build_gradle_two_levels_up(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            (root / "build.gradle").touch()
            subdir = root / "sub1" / "sub2"
            subdir.mkdir(parents=True)
            old = self._chdir(subdir)
            try:
                self.assertEqual(find_project_root(), root.resolve())
            finally:
                os.chdir(old)

    def test_pom_xml_detected(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            (root / "pom.xml").touch()
            old = self._chdir(root)
            try:
                self.assertEqual(find_project_root(), root.resolve())
            finally:
                os.chdir(old)

    def test_no_project_root_raises(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            old = self._chdir(tmpdir)
            try:
                with self.assertRaises(RuntimeError):
                    find_project_root()
            finally:
                os.chdir(old)

    def test_explicit_start_directory(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            (root / "settings.gradle.kts").touch()
            subdir = root / "sub"
            subdir.mkdir()
            self.assertEqual(find_project_root(subdir), root.resolve())


class TestGetSourceFiles(TestCase):
    def test_finds_java_files_under_src(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            module = Path(tmpdir)
            java_file = module / "src" / "main" / "java" / "Foo.java"
            java_file.parent.mkdir(parents=True)
            java_file.touch()
            paths = get_source_files(module)
            self.assertEqual(paths, [java_file])

    def test_no_src_directory_returns_empty(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            module = Path(tmpdir)
            paths = get_source_files(module)
            self.assertEqual(paths, [])

    def test_ignores_non_java_files(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            module = Path(tmpdir)
            src = module / "src" / "main" / "java"
            src.mkdir(parents=True)
            (src / "Foo.java").touch()
            (src / "Foo.kt").touch()
            (src / "readme.txt").touch()
            paths = get_source_files(module)
            self.assertEqual(len(paths), 1)
            self.assertTrue(paths[0].name.endswith(".java"))

    def test_finds_java_files_across_nested_modules(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            root_java = root / "src" / "main" / "java" / "Root.java"
            first_module_java = (
                root / "first" / "src" / "main" / "java" / "First.java"
            )
            second_module_java = (
                root / "nested" / "second" / "src" / "test" / "java" / "Second.java"
            )
            outside_src = root / "third" / "Outside.java"
            for java_file in (
                root_java,
                first_module_java,
                second_module_java,
                outside_src,
            ):
                java_file.parent.mkdir(parents=True, exist_ok=True)
                java_file.touch()

            paths = get_source_files(root, include_nested_modules=True)

            self.assertEqual(
                paths,
                sorted([root_java, first_module_java, second_module_java]),
            )

    def test_module_scope_does_not_include_nested_modules(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            module = Path(tmpdir)
            nested_java = module / "nested" / "src" / "main" / "java" / "Nested.java"
            nested_java.parent.mkdir(parents=True)
            nested_java.touch()

            self.assertEqual(get_source_files(module), [])


class TestDiscoverSourceSets(TestCase):
    def test_discovers_main_and_test(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            module = Path(tmpdir)
            (module / "src" / "main" / "java").mkdir(parents=True)
            (module / "src" / "main" / "java" / "Foo.java").touch()
            (module / "src" / "test" / "java").mkdir(parents=True)
            (module / "src" / "test" / "java" / "FooTest.java").touch()
            self.assertEqual(discover_source_sets(module), ["main", "test"])

    def test_discovers_custom_source_sets(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            module = Path(tmpdir)
            for name in ["main", "test", "testFixtures", "jmh"]:
                d = module / "src" / name / "java"
                d.mkdir(parents=True)
                (d / "A.java").touch()
            result = discover_source_sets(module)
            self.assertEqual(result, ["jmh", "main", "test", "testFixtures"])

    def test_skips_dirs_without_java_files(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            module = Path(tmpdir)
            (module / "src" / "main" / "java").mkdir(parents=True)
            (module / "src" / "main" / "java" / "Foo.java").touch()
            (module / "src" / "resources").mkdir(parents=True)
            (module / "src" / "resources" / "app.yml").touch()
            self.assertEqual(discover_source_sets(module), ["main"])

    def test_no_src_dir_defaults_to_main(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            module = Path(tmpdir)
            self.assertEqual(discover_source_sets(module), ["main"])


class TestRunBuild(TestCase):
    def test_default_gradle_cmd_compiles_all_source_sets(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            module = root / "mymodule"
            for name in ["main", "test"]:
                d = module / "src" / name / "java"
                d.mkdir(parents=True)
                (d / "A.java").touch()
            (root / "gradlew").touch()

            mock_result = MagicMock()
            mock_result.returncode = 0
            mock_result.stdout = "BUILD SUCCESSFUL\n"
            mock_result.stderr = ""

            with patch("suppression_remover.core.subprocess.run", return_value=mock_result) as mock_run:
                output, ok = run_build(module, root)

            self.assertTrue(ok)
            self.assertIn("BUILD SUCCESSFUL", output)
            cmd = mock_run.call_args[0][0]
            self.assertIn(":mymodule:compileJava", cmd)
            self.assertIn(":mymodule:compileTestJava", cmd)
            self.assertIn("--continue", cmd)
            self.assertIs(mock_run.call_args.kwargs["check"], False)

    def test_custom_build_cmd(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            module = root / "mod"
            module.mkdir()

            mock_result = MagicMock()
            mock_result.returncode = 1
            mock_result.stdout = ""
            mock_result.stderr = "error output\n"

            with patch("suppression_remover.core.subprocess.run", return_value=mock_result) as mock_run:
                output, ok = run_build(module, root, build_cmd=["make", "compile"])

            self.assertFalse(ok)
            self.assertIn("error output", output)
            cmd = mock_run.call_args[0][0]
            self.assertEqual(cmd, ["make", "compile"])
