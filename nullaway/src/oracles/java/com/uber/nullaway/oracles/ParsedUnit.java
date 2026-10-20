package com.uber.nullaway.oracles;

import com.google.common.collect.Iterables;
import com.google.errorprone.FileObjects;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * A compilation unit parsed without attribution, together with the source positions of its trees.
 *
 * <p>Parsing needs no classpath, so a unit that references types the oracles cannot resolve still
 * parses. A unit with a syntax error parses too, into a tree javac has repaired; callers that edit
 * the text rely on the compilation that follows to reject such a unit.
 *
 * @param unit the parsed tree
 * @param text the text the tree was parsed from
 * @param positions start and end offsets into {@code text} for every tree in {@code unit}
 */
record ParsedUnit(CompilationUnitTree unit, String text, SourcePositions positions) {

  private static final JavaCompiler JAVAC = ToolProvider.getSystemJavaCompiler();

  /** Parses {@code source}, reporting its path as {@code source.path()}. */
  static ParsedUnit parse(Source source) {
    JavacTask task =
        (JavacTask)
            JAVAC.getTask(
                null,
                null,
                diagnostic -> {},
                List.of("-proc:none"),
                null,
                List.of(FileObjects.forSourceLines(source.path(), source.text())));
    try {
      CompilationUnitTree unit = Iterables.getOnlyElement(task.parse());
      return new ParsedUnit(unit, source.text(), Trees.instance(task).getSourcePositions());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Returns the offset of the first character of {@code tree}, or -1 if javac recorded none. */
  @SuppressWarnings("removal")
  int start(Tree tree) {
    // The overload taking only the tree is missing from JDK 17, which NullAway supports.
    return (int) positions.getStartPosition(unit, tree);
  }

  /**
   * Returns the offset just past the last character of {@code tree}, or -1 if javac recorded none.
   */
  @SuppressWarnings("removal")
  int end(Tree tree) {
    return (int) positions.getEndPosition(unit, tree);
  }

  /** Returns the text of {@code tree}, which must have both positions. */
  String textOf(Tree tree) {
    return text.substring(start(tree), end(tree));
  }
}
