package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.TreeScanner;
import com.uber.nullaway.oracles.AnnotationMutator.Mutation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Derives variants of a seed by changing the shape of its types and of the constructs its values
 * pass through, where {@link AnnotationMutator} only adds and removes nullness annotations.
 *
 * <p>The edits are syntactic and need no attribution, so a variant may not compile; javac rejects
 * those, and the oracles skip them. The edits are:
 *
 * <ul>
 *   <li>a type argument {@code A} in a declaration becomes {@code ? extends A} or {@code ? super
 *       A}, and a bounded wildcard becomes its bound or {@code ?};
 *   <li>a method whose return type also types some of its parameters gets a type parameter {@code
 *       T__} bounded by that type, which replaces it in the return type and those parameters;
 *   <li>a lambda's explicitly typed parameters lose their types, and implicitly typed ones are
 *       declared with {@code var};
 *   <li>an expression that is returned, initializes a variable, or is passed as an argument is
 *       wrapped in a generic call: {@code Objects.requireNonNull}, {@code Optional.ofNullable(..)
 *       .orElseThrow()}, or {@code Collections.singletonList(..).get(0)}; a returned expression may
 *       instead move into a {@code Supplier} lambda, an anonymous class, or a local class;
 *   <li>the last parameter of a method that does not read it becomes varargs.
 * </ul>
 *
 * <p>An edit that changes several places, such as a method's signature, is one {@link Mutation}
 * whose edit replaces the whole span from the first place to the last.
 */
final class TypeMutator {

  private TypeMutator() {}

  /** Returns every mutation this class can make to {@code seed}. */
  static ImmutableList<Mutation> candidates(Seed seed) {
    ImmutableList.Builder<Mutation> result = ImmutableList.builder();
    for (int i = 0; i < seed.sources().size(); i++) {
      ParsedUnit parsed = ParsedUnit.parse(seed.sources().get(i));
      new CandidateScanner(parsed, i, result).scan(parsed.unit(), null);
    }
    return result.build();
  }

  /** Collects the mutations for one unit. */
  private static final class CandidateScanner extends TreeScanner<Void, Void> {
    private final ParsedUnit parsed;
    private final int unitIndex;
    private final ImmutableList.Builder<Mutation> result;

    /**
     * The innermost enclosing method, lambda, or class; a {@code return} wraps by its method's
     * return type only when the method is the innermost.
     */
    private final Deque<Tree> enclosing = new ArrayDeque<>();

    CandidateScanner(ParsedUnit parsed, int unitIndex, ImmutableList.Builder<Mutation> result) {
      this.parsed = parsed;
      this.unitIndex = unitIndex;
      this.result = result;
    }

    @Override
    public Void visitClass(ClassTree tree, Void unused) {
      for (TypeParameterTree typeParameter : tree.getTypeParameters()) {
        typeParameter.getBounds().forEach(this::wildcardSites);
      }
      enclosing.push(tree);
      try {
        return super.visitClass(tree, null);
      } finally {
        enclosing.pop();
      }
    }

    @Override
    public Void visitMethod(MethodTree tree, Void unused) {
      wildcardSites(tree.getReturnType());
      for (TypeParameterTree typeParameter : tree.getTypeParameters()) {
        typeParameter.getBounds().forEach(this::wildcardSites);
      }
      generify(tree);
      varargs(tree);
      enclosing.push(tree);
      try {
        return super.visitMethod(tree, null);
      } finally {
        enclosing.pop();
      }
    }

    @Override
    public Void visitVariable(VariableTree tree, Void unused) {
      wildcardSites(tree.getType());
      ExpressionTree initializer = tree.getInitializer();
      if (initializer != null) {
        wrap(initializer, null);
      }
      return super.visitVariable(tree, null);
    }

    @Override
    public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
      lambdaParameters(tree);
      enclosing.push(tree);
      try {
        return super.visitLambdaExpression(tree, null);
      } finally {
        enclosing.pop();
      }
    }

    @Override
    public Void visitReturn(ReturnTree tree, Void unused) {
      ExpressionTree expression = tree.getExpression();
      if (expression != null) {
        String returnType =
            enclosing.peek() instanceof MethodTree method && method.getReturnType() != null
                ? parsed.textOf(method.getReturnType())
                : null;
        wrap(expression, returnType);
        if (returnType != null && parsed.start(tree) >= 0 && parsed.end(tree) > 0) {
          add(
              parsed.start(tree),
              parsed.end(tree),
              "class L__ { "
                  + returnType
                  + " get() { return "
                  + parsed.textOf(expression)
                  + "; } } return new L__().get();",
              "returned expression moved into a local class");
        }
      }
      return super.visitReturn(tree, null);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Void unused) {
      tree.getArguments().forEach(a -> wrap(a, null));
      return super.visitMethodInvocation(tree, null);
    }

    /**
     * Adds the wildcard edits for every type argument within {@code type}: {@code A} becomes {@code
     * ? extends A} or {@code ? super A}, and {@code ? extends A} or {@code ? super A} becomes
     * {@code A} or {@code ?}.
     */
    private void wildcardSites(@Nullable Tree type) {
      if (type == null || parsed.start(type) < 0) {
        return;
      }
      if (type instanceof ParameterizedTypeTree parameterized) {
        for (Tree argument : parameterized.getTypeArguments()) {
          int start = parsed.start(argument);
          int end = parsed.end(argument);
          if (start < 0 || end <= start) {
            continue;
          }
          if (argument instanceof WildcardTree wildcard) {
            if (wildcard.getBound() != null) {
              add(
                  start,
                  end,
                  parsed.textOf(wildcard.getBound()),
                  "wildcard " + parsed.textOf(argument) + " made exact");
              add(start, end, "?", "wildcard " + parsed.textOf(argument) + " made unbounded");
              wildcardSites(wildcard.getBound());
            }
          } else if (!(argument instanceof PrimitiveTypeTree)) {
            String text = parsed.textOf(argument);
            add(start, end, "? extends " + text, "type argument " + text + " made ? extends");
            add(start, end, "? super " + text, "type argument " + text + " made ? super");
            wildcardSites(argument);
          }
        }
      } else if (type instanceof ArrayTypeTree array) {
        wildcardSites(array.getType());
      } else if (type instanceof AnnotatedTypeTree annotated) {
        wildcardSites(annotated.getUnderlyingType());
      }
    }

    /**
     * Adds the edit that gives {@code method} a type parameter {@code T__} bounded by its return
     * type, if some parameter has exactly the return type's text.
     */
    private void generify(MethodTree method) {
      Tree returnType = method.getReturnType();
      if (returnType == null
          || returnType instanceof PrimitiveTypeTree
          || parsed.start(returnType) < 0) {
        return;
      }
      String type = parsed.textOf(returnType);
      List<TextEdit> edits = new ArrayList<>();
      List<? extends TypeParameterTree> typeParameters = method.getTypeParameters();
      if (typeParameters.isEmpty()) {
        edits.add(
            new TextEdit(
                parsed.start(returnType), parsed.start(returnType), "<T__ extends " + type + "> "));
      } else {
        int end = parsed.end(typeParameters.get(typeParameters.size() - 1));
        if (end < 0) {
          return;
        }
        edits.add(new TextEdit(end, end, ", T__ extends " + type));
      }
      edits.add(new TextEdit(parsed.start(returnType), parsed.end(returnType), "T__"));
      boolean anyParameter = false;
      for (VariableTree parameter : method.getParameters()) {
        Tree parameterType = parameter.getType();
        if (parameterType != null
            && parsed.start(parameterType) >= 0
            && parsed.textOf(parameterType).equals(type)) {
          edits.add(new TextEdit(parsed.start(parameterType), parsed.end(parameterType), "T__"));
          anyParameter = true;
        }
      }
      if (anyParameter) {
        addCombined(edits, "method " + method.getName() + " made generic in its type " + type);
      }
    }

    /**
     * Adds the edit that turns the last parameter of {@code method} into varargs if no code reads
     * it.
     */
    private void varargs(MethodTree method) {
      List<? extends VariableTree> parameters = method.getParameters();
      if (parameters.isEmpty()) {
        return;
      }
      VariableTree last = parameters.get(parameters.size() - 1);
      Tree type = last.getType();
      if (type == null || type instanceof ArrayTypeTree || parsed.end(type) < 0) {
        return;
      }
      String name = last.getName().toString();
      boolean read =
          method.getBody() != null
              && Boolean.TRUE.equals(
                  new TreeScanner<Boolean, Void>() {
                    @Override
                    public Boolean visitIdentifier(IdentifierTree tree, Void unused) {
                      return tree.getName().contentEquals(name);
                    }

                    @Override
                    public Boolean reduce(Boolean a, Boolean b) {
                      return Boolean.TRUE.equals(a) || Boolean.TRUE.equals(b);
                    }
                  }.scan(method.getBody(), null));
      if (!read) {
        add(
            parsed.end(type),
            parsed.end(type),
            "...",
            "parameter " + name + " of " + method.getName() + " made varargs");
      }
    }

    /**
     * Adds the edit that drops the declared types of the lambda's parameters, or that declares
     * implicitly typed ones with {@code var}.
     */
    private void lambdaParameters(LambdaExpressionTree lambda) {
      List<? extends VariableTree> parameters = lambda.getParameters();
      if (parameters.isEmpty()) {
        return;
      }
      int start = parsed.start(parameters.get(0));
      VariableTree last = parameters.get(parameters.size() - 1);
      int end = parsed.end(last);
      if (start < 0) {
        return;
      }
      if (end <= start) {
        end = parsed.start(last) + last.getName().length();
      }
      boolean explicit =
          parameters.stream().allMatch(p -> p.getType() != null && parsed.start(p.getType()) >= 0);
      List<String> names = parameters.stream().map(p -> p.getName().toString()).toList();
      if (explicit) {
        add(start, end, String.join(", ", names), "lambda parameters made implicit");
      } else {
        String declared = String.join(", ", names.stream().map(n -> "var " + n).toList());
        boolean parenthesized = parsed.text().charAt(parsed.start(lambda)) == '(';
        add(
            start,
            end,
            parenthesized ? declared : "(" + declared + ")",
            "lambda parameters declared with var");
      }
    }

    /**
     * Adds the edits that wrap {@code expression} in a generic call and, when {@code returnType}
     * names the type of the enclosing method's result, in a lambda or an anonymous class.
     */
    private void wrap(ExpressionTree expression, @Nullable String returnType) {
      if (expression instanceof LambdaExpressionTree
          || expression instanceof MemberReferenceTree
          || (expression instanceof NewArrayTree array && array.getType() == null)
          || (expression instanceof LiteralTree literal && literal.getValue() != null)) {
        return;
      }
      int start = parsed.start(expression);
      int end = parsed.end(expression);
      if (start < 0 || end <= start) {
        return;
      }
      String text = parsed.textOf(expression);
      add(
          start,
          end,
          "java.util.Objects.requireNonNull(" + text + ")",
          "Objects.requireNonNull around " + text);
      add(
          start,
          end,
          "java.util.Optional.ofNullable(" + text + ").orElseThrow()",
          "Optional chain around " + text);
      add(
          start,
          end,
          "java.util.Collections.singletonList(" + text + ").get(0)",
          "singletonList around " + text);
      if (returnType != null) {
        if (!(enclosingReturnType() instanceof PrimitiveTypeTree)) {
          add(
              start,
              end,
              "((java.util.function.Supplier<" + returnType + ">) () -> " + text + ").get()",
              "Supplier lambda around " + text);
        }
        add(
            start,
            end,
            "new Object() { " + returnType + " get() { return " + text + "; } }.get()",
            "anonymous class around " + text);
      }
    }

    private @Nullable Tree enclosingReturnType() {
      return enclosing.peek() instanceof MethodTree method ? method.getReturnType() : null;
    }

    /** Adds one mutation that applies {@code edits}, by replacing the span they cover. */
    private void addCombined(List<TextEdit> edits, String description) {
      int start = edits.stream().mapToInt(TextEdit::start).min().orElseThrow();
      int end = edits.stream().mapToInt(TextEdit::end).max().orElseThrow();
      List<TextEdit> shifted =
          edits.stream()
              .map(e -> new TextEdit(e.start() - start, e.end() - start, e.replacement()))
              .toList();
      add(start, end, TextEdit.apply(parsed.text().substring(start, end), shifted), description);
    }

    /** Adds the mutation that replaces {@code [start, end)} with {@code replacement}. */
    private void add(int start, int end, String replacement, String description) {
      if (start >= 0 && end >= start) {
        result.add(
            new Mutation(
                unitIndex,
                new TextEdit(start, end, replacement),
                description
                    + " at "
                    + parsed.unit().getSourceFile().getName()
                    + ":"
                    + parsed.unit().getLineMap().getLineNumber(start)));
      }
    }
  }
}
