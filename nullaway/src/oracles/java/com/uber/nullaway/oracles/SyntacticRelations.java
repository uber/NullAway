package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.TreeScanner;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.uber.nullaway.oracles.Relation.Change;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.jspecify.annotations.Nullable;

/** Relations that need only the parse tree of each unit. */
final class SyntacticRelations {

  private SyntacticRelations() {}

  /** Single-change variants tried when javac rejects the variant with every change. */
  private static final int SINGLES = 5;

  /** Returns every relation in this class. */
  static ImmutableList<Relation> all() {
    return ImmutableList.of(
        PARENTHESES,
        NULL_CHECK_SWAP,
        NULL_CHECK_NEGATION,
        SPLIT_DECLARATION,
        FILE_ORDER,
        PACKAGE_NULL_MARKED);
  }

  /**
   * A relation built from the changes a scanner proposes for each unit.
   *
   * @param name the relation's name
   * @param scanner adds the changes for the parsed unit with the given index
   */
  private record PerUnit(String name, BiConsumer<ParsedUnit, ChangeSink> scanner)
      implements Relation {
    @Override
    public ImmutableList<ImmutableList<Source>> variants(Seed seed) {
      List<Change> changes = new ArrayList<>();
      for (int i = 0; i < seed.sources().size(); i++) {
        int unit = i;
        scanner.accept(
            ParsedUnit.parse(seed.sources().get(i)), edits -> changes.add(new Change(unit, edits)));
      }
      return Relation.variants(seed, changes, SINGLES);
    }
  }

  /** Receives the edits of one change. */
  private interface ChangeSink {
    void add(ImmutableList<TextEdit> edits);
  }

  /**
   * Wraps argument, return, initializer, assigned, condition, and conditional-branch expressions,
   * and receivers that are method calls, in parentheses. An expression that spans several lines
   * stays as it is: NullAway may report a warning on it at its first line, which the opening
   * parenthesis would move.
   */
  static final Relation PARENTHESES =
      new PerUnit(
          "parentheses",
          (parsed, sink) ->
              new TreeScanner<Void, Void>() {
                private void wrap(@Nullable ExpressionTree e) {
                  if (e == null
                      || (e instanceof NewArrayTree array && array.getType() == null)
                      || parsed.start(e) < 0
                      || parsed.end(e) < 0
                      || parsed.textOf(e).contains("\n")) {
                    return;
                  }
                  sink.add(
                      ImmutableList.of(
                          new TextEdit(parsed.start(e), parsed.start(e), "("),
                          new TextEdit(parsed.end(e), parsed.end(e), ")")));
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
                  tree.getArguments().forEach(this::wrap);
                  if (tree.getMethodSelect() instanceof MemberSelectTree select
                      && select.getExpression() instanceof MethodInvocationTree) {
                    wrap(select.getExpression());
                  }
                  return super.visitMethodInvocation(tree, null);
                }

                @Override
                public Void visitNewClass(NewClassTree tree, Void unused) {
                  tree.getArguments().forEach(this::wrap);
                  return super.visitNewClass(tree, null);
                }

                @Override
                public Void visitReturn(ReturnTree tree, Void unused) {
                  wrap(tree.getExpression());
                  return super.visitReturn(tree, null);
                }

                @Override
                public Void visitVariable(VariableTree tree, Void unused) {
                  if ((((JCTree.JCVariableDecl) tree).mods.flags & Flags.ENUM) == 0) {
                    wrap(tree.getInitializer());
                  }
                  return super.visitVariable(tree, null);
                }

                @Override
                public Void visitAssignment(AssignmentTree tree, Void unused) {
                  wrap(tree.getExpression());
                  return super.visitAssignment(tree, null);
                }

                @Override
                public Void visitIf(IfTree tree, Void unused) {
                  wrapCondition(tree.getCondition());
                  return super.visitIf(tree, null);
                }

                @Override
                public Void visitWhileLoop(WhileLoopTree tree, Void unused) {
                  wrapCondition(tree.getCondition());
                  return super.visitWhileLoop(tree, null);
                }

                @Override
                public Void visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
                  wrapCondition(tree.getCondition());
                  return super.visitDoWhileLoop(tree, null);
                }

                @Override
                public Void visitConditionalExpression(
                    ConditionalExpressionTree tree, Void unused) {
                  wrap(tree.getTrueExpression());
                  wrap(tree.getFalseExpression());
                  return super.visitConditionalExpression(tree, null);
                }

                @Override
                public Void visitAnnotation(AnnotationTree tree, Void unused) {
                  // An element value such as {@code value = {"a"}} is not an expression.
                  return null;
                }

                private void wrapCondition(ExpressionTree condition) {
                  wrap(
                      condition instanceof ParenthesizedTree parens
                          ? parens.getExpression()
                          : condition);
                }
              }.scan(parsed.unit(), null));

  /**
   * Swaps the operands of {@code ==} and {@code !=} when one of them is {@code null} and both are
   * on one line.
   */
  static final Relation NULL_CHECK_SWAP =
      new PerUnit(
          "null-check-swap",
          (parsed, sink) ->
              new TreeScanner<Void, Void>() {
                @Override
                public Void visitBinary(BinaryTree tree, Void unused) {
                  ExpressionTree left = tree.getLeftOperand();
                  ExpressionTree right = tree.getRightOperand();
                  // Operands on different lines would move a warning on either of them to the
                  // other's line.
                  if (operator(tree) != null
                      && hasNullOperand(tree)
                      && parsed.start(tree) >= 0
                      && !parsed
                          .text()
                          .substring(parsed.start(left), parsed.end(right))
                          .contains("\n")) {
                    // The operands trade places; the operator stays.
                    sink.add(
                        ImmutableList.of(
                            new TextEdit(
                                parsed.start(left), parsed.end(left), parsed.textOf(right)),
                            new TextEdit(
                                parsed.start(right), parsed.end(right), parsed.textOf(left))));
                  }
                  return super.visitBinary(tree, null);
                }
              }.scan(parsed.unit(), null));

  /** Rewrites {@code a != null} as {@code !(a == null)}, with {@code null} on either side. */
  static final Relation NULL_CHECK_NEGATION =
      new PerUnit(
          "null-check-negation",
          (parsed, sink) ->
              new TreeScanner<Void, Void>() {
                @Override
                public Void visitBinary(BinaryTree tree, Void unused) {
                  if (tree.getKind() == Tree.Kind.NOT_EQUAL_TO
                      && hasNullOperand(tree)
                      && parsed.start(tree) >= 0) {
                    int op =
                        operatorOffset(
                            parsed.text(),
                            parsed.end(tree.getLeftOperand()),
                            parsed.start(tree.getRightOperand()),
                            "!=");
                    if (op < 0) {
                      return super.visitBinary(tree, null);
                    }
                    sink.add(
                        ImmutableList.of(
                            new TextEdit(parsed.start(tree), parsed.start(tree), "!("),
                            new TextEdit(op, op + 2, "=="),
                            new TextEdit(parsed.end(tree), parsed.end(tree), ")")));
                  }
                  return super.visitBinary(tree, null);
                }
              }.scan(parsed.unit(), null));

  /**
   * Splits a local variable declaration with an initializer into a declaration and an assignment on
   * the same line: {@code T x = e;} becomes {@code T x; x = e;}. A {@code var} declaration, an
   * annotated declaration, and an array initializer stay as they are, and of a declaration of
   * several variables only the last one is split: {@code int p = 1, q = 2;} becomes {@code int p =
   * 1, q ; q = 2;}.
   */
  static final Relation SPLIT_DECLARATION =
      new PerUnit(
          "split-declaration",
          (parsed, sink) ->
              new TreeScanner<Void, Void>() {
                @Override
                public Void visitBlock(BlockTree tree, Void unused) {
                  for (StatementTree statement : tree.getStatements()) {
                    if (statement instanceof VariableTree variable) {
                      split(variable);
                    }
                  }
                  return super.visitBlock(tree, null);
                }

                private void split(VariableTree variable) {
                  ExpressionTree init = variable.getInitializer();
                  // An annotation on the declaration, such as @SuppressWarnings, would no longer
                  // cover the initializer once it moves into an assignment.
                  if (init == null
                      || !variable.getModifiers().getAnnotations().isEmpty()
                      || variable.getType() == null
                      || parsed.start(variable.getType()) < 0
                      || parsed.textOf(variable.getType()).equals("var")
                      || (init instanceof NewArrayTree array && array.getType() == null)) {
                    return;
                  }
                  int initEnd = parsed.end(init);
                  String rest = parsed.text().substring(initEnd).stripLeading();
                  int equals = parsed.text().lastIndexOf('=', parsed.start(init));
                  if (!rest.startsWith(";") || equals < parsed.start(variable)) {
                    return;
                  }
                  // Only the '=' changes, so a declaration that spans lines keeps its line breaks.
                  sink.add(
                      ImmutableList.of(
                          new TextEdit(equals, equals + 1, "; " + variable.getName() + " =")));
                }
              }.scan(parsed.unit(), null));

  /** Passes the units to javac in reverse order. */
  static final Relation FILE_ORDER =
      new Relation() {
        @Override
        public String name() {
          return "file-order";
        }

        @Override
        public ImmutableList<ImmutableList<Source>> variants(Seed seed) {
          return seed.sources().size() < 2
              ? ImmutableList.of()
              : ImmutableList.of(seed.sources().reverse());
        }
      };

  /**
   * Moves {@code @NullMarked} from the top-level classes of a package to a new {@code
   * package-info.java}, when every top-level class of that package in the seed carries it and the
   * seed has no {@code package-info.java} of its own.
   */
  static final Relation PACKAGE_NULL_MARKED =
      new Relation() {
        @Override
        public String name() {
          return "package-null-marked";
        }

        @Override
        public ImmutableList<ImmutableList<Source>> variants(Seed seed) {
          if (seed.sources().stream().anyMatch(s -> s.path().endsWith("package-info.java"))) {
            return ImmutableList.of();
          }
          List<ParsedUnit> units = seed.sources().stream().map(ParsedUnit::parse).toList();
          String pkg = null;
          List<Change> changes = new ArrayList<>();
          for (int i = 0; i < units.size(); i++) {
            ParsedUnit parsed = units.get(i);
            if (parsed.unit().getPackageName() == null) {
              continue;
            }
            String unitPkg = parsed.unit().getPackageName().toString();
            if (pkg != null && !pkg.equals(unitPkg)) {
              return ImmutableList.of();
            }
            pkg = unitPkg;
            for (Tree type : parsed.unit().getTypeDecls()) {
              if (!(type instanceof ClassTree cls)) {
                continue;
              }
              AnnotationTree marker = nullMarked(cls);
              if (marker == null) {
                return ImmutableList.of();
              }
              changes.add(Change.of(i, new TextEdit(parsed.start(marker), parsed.end(marker), "")));
            }
          }
          if (pkg == null || changes.isEmpty()) {
            return ImmutableList.of();
          }
          String path = seed.sources().get(changes.get(0).unit()).path();
          String dir = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
          ImmutableList<ImmutableList<Source>> movedVariants = Relation.variants(seed, changes, 0);
          if (movedVariants.isEmpty()) {
            return ImmutableList.of();
          }
          ImmutableList<Source> moved = movedVariants.get(0);
          return ImmutableList.of(
              ImmutableList.<Source>builder()
                  .addAll(moved)
                  .add(
                      new Source(
                          dir + "package-info.java",
                          "@org.jspecify.annotations.NullMarked\npackage " + pkg + ";\n"))
                  .build());
        }
      };

  private static @Nullable AnnotationTree nullMarked(ClassTree cls) {
    for (AnnotationTree a : cls.getModifiers().getAnnotations()) {
      String type = a.getAnnotationType().toString();
      if (type.equals("NullMarked") || type.endsWith(".NullMarked")) {
        return a;
      }
    }
    return null;
  }

  /**
   * Returns the offset of {@code operator} in {@code text} between {@code from} and {@code to},
   * outside comments, or -1 if it does not occur there.
   */
  static int operatorOffset(String text, int from, int to, String operator) {
    int i = from;
    while (i < to) {
      if (text.startsWith("/*", i)) {
        int close = text.indexOf("*/", i + 2);
        i = close < 0 ? to : close + 2;
      } else if (text.startsWith("//", i)) {
        int newline = text.indexOf('\n', i);
        i = newline < 0 ? to : newline + 1;
      } else if (text.startsWith(operator, i)) {
        return i;
      } else {
        i++;
      }
    }
    return -1;
  }

  private static @Nullable String operator(BinaryTree tree) {
    return switch (tree.getKind()) {
      case EQUAL_TO -> "==";
      case NOT_EQUAL_TO -> "!=";
      default -> null;
    };
  }

  private static boolean hasNullOperand(BinaryTree tree) {
    return tree.getLeftOperand().getKind() == Tree.Kind.NULL_LITERAL
        || tree.getRightOperand().getKind() == Tree.Kind.NULL_LITERAL;
  }
}
