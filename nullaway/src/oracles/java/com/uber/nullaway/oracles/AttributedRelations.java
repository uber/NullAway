package com.uber.nullaway.oracles;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.FileObjects;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.BasicJavacTask;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.tree.JCTree;
import com.uber.nullaway.oracles.Relation.Change;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.jspecify.annotations.Nullable;

/**
 * Relations that need the types javac attributes to each expression. Each attributes the seed with
 * plain javac on this JVM's classpath; a seed javac rejects yields no variant.
 */
final class AttributedRelations {

  private AttributedRelations() {}

  private static final int SINGLES = 5;
  private static final JavaCompiler JAVAC = ToolProvider.getSystemJavaCompiler();
  private static final StandardJavaFileManager FILE_MANAGER =
      JAVAC.getStandardFileManager(null, Locale.ROOT, UTF_8);

  /** Returns every relation in this class. */
  static ImmutableList<Relation> all() {
    return ImmutableList.of(CAST, LAMBDA_TO_ANONYMOUS_CLASS);
  }

  /** Receives the attributed units of a seed and adds changes for them. */
  private interface Scanner {
    void scan(
        int unit,
        ParsedUnit parsed,
        CompilationUnitTree tree,
        Trees trees,
        Types types,
        List<Change> changes);
  }

  /**
   * A relation whose changes come from scanning the attributed units.
   *
   * @param name the relation's name
   * @param scanner adds the changes for one unit
   */
  private record Attributed(String name, Scanner scanner) implements Relation {
    @Override
    public ImmutableList<ImmutableList<Source>> variants(Seed seed) {
      Attribution attribution = attribute(seed);
      if (attribution == null) {
        return ImmutableList.of();
      }
      List<Change> changes = new ArrayList<>();
      for (CompilationUnitTree unit : attribution.units()) {
        int index = indexOf(seed, unit);
        ParsedUnit parsed =
            new ParsedUnit(
                unit, seed.sources().get(index).text(), attribution.trees().getSourcePositions());
        scanner.scan(index, parsed, unit, attribution.trees(), attribution.types(), changes);
      }
      return Relation.variants(seed, changes, SINGLES);
    }
  }

  /**
   * The units of a seed as plain javac attributes them.
   *
   * @param units the units, in the order javac parsed them; {@link #indexOf} maps each to its index
   *     in the seed
   * @param trees the trees of the task, for elements and source positions
   * @param types the types of the task
   */
  record Attribution(ImmutableList<CompilationUnitTree> units, Trees trees, Types types) {}

  /**
   * Attributes {@code seed} with plain javac on this JVM's classpath, or returns null if javac
   * reports an error.
   */
  static @Nullable Attribution attribute(Seed seed) {
    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    List<JavaFileObject> files =
        seed.sources().stream()
            .map(s -> FileObjects.forSourceLines(s.path(), s.text()))
            .collect(Collectors.toList());
    JavacTask task =
        (JavacTask)
            JAVAC.getTask(
                null,
                FILE_MANAGER,
                diagnostics,
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path")),
                null,
                files);
    ImmutableList.Builder<CompilationUnitTree> units = ImmutableList.builder();
    try {
      task.parse().forEach(units::add);
      task.analyze();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) {
      return null;
    }
    return new Attribution(
        units.build(), Trees.instance(task), Types.instance(((BasicJavacTask) task).getContext()));
  }

  /** Returns the index in {@code seed} of the source that {@code unit} was parsed from. */
  static int indexOf(Seed seed, CompilationUnitTree unit) {
    String uriPath = unit.getSourceFile().toUri().getPath();
    int best = -1;
    for (int i = 0; i < seed.sources().size(); i++) {
      String path = seed.sources().get(i).path();
      if (uriPath.endsWith(path)
          && (best < 0 || path.length() > seed.sources().get(best).path().length())) {
        best = i;
      }
    }
    if (best < 0) {
      throw new IllegalStateException("No source for " + uriPath);
    }
    return best;
  }

  /**
   * Casts argument, return, and initializer expressions to their own static type: {@code e} becomes
   * {@code (T) (e)}. Only a primitive type, or a top-level or member class with no type parameters,
   * is cast to, and a call of a generic method is left alone, since a cast takes away the target
   * type its inference uses. An expression that spans several lines stays as it is, since the cast
   * could move a warning to its first line.
   */
  static final Relation CAST =
      new Attributed(
          "cast",
          (unit, parsed, tree, trees, types, changes) ->
              new TreePathScanner<Void, Void>() {
                private void cast(@Nullable ExpressionTree e) {
                  if (e == null
                      || parsed.start(e) < 0
                      || parsed.end(e) < 0
                      || parsed.textOf(e).contains("\n")) {
                    return;
                  }
                  // Parentheses leave a poly expression a poly expression, so the checks apply to
                  // what they enclose. A cast is not a target type for a lambda's or a generic
                  // call's inference, and the branches of a conditional or a switch take their
                  // target type from the context the cast would replace.
                  TreePath path = new TreePath(getCurrentPath(), e);
                  ExpressionTree inner = e;
                  while (inner instanceof ParenthesizedTree parens) {
                    inner = parens.getExpression();
                    path = new TreePath(path, inner);
                  }
                  switch (inner.getKind()) {
                    case LAMBDA_EXPRESSION,
                        MEMBER_REFERENCE,
                        NULL_LITERAL,
                        TYPE_CAST,
                        CONDITIONAL_EXPRESSION,
                        SWITCH_EXPRESSION -> {
                      return;
                    }
                    default -> {}
                  }
                  if (inner instanceof NewArrayTree) {
                    return;
                  }
                  if (inner instanceof MethodInvocationTree
                      && trees.getElement(path) instanceof ExecutableElement method
                      && !method.getTypeParameters().isEmpty()) {
                    return;
                  }
                  String type = castableName(trees.getTypeMirror(path));
                  if (type != null) {
                    changes.add(
                        Change.of(
                            unit,
                            new TextEdit(parsed.start(e), parsed.start(e), "(" + type + ") ("),
                            new TextEdit(parsed.end(e), parsed.end(e), ")")));
                  }
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree t, Void unused) {
                  t.getArguments().forEach(this::cast);
                  return super.visitMethodInvocation(t, null);
                }

                @Override
                public Void visitReturn(ReturnTree t, Void unused) {
                  cast(t.getExpression());
                  return super.visitReturn(t, null);
                }

                @Override
                public Void visitVariable(VariableTree t, Void unused) {
                  if ((((JCTree.JCVariableDecl) t).mods.flags & Flags.ENUM) == 0) {
                    cast(t.getInitializer());
                  }
                  return super.visitVariable(t, null);
                }
              }.scan(tree, null));

  /** Returns the source name of {@code type} if {@link #CAST} may cast to it, else null. */
  private static @Nullable String castableName(@Nullable TypeMirror type) {
    if (type == null) {
      return null;
    }
    if (type.getKind().isPrimitive()) {
      return type.getKind().name().toLowerCase(Locale.ROOT);
    }
    if (type.getKind() != TypeKind.DECLARED) {
      return null;
    }
    DeclaredType declared = (DeclaredType) type;
    TypeElement element = (TypeElement) declared.asElement();
    NestingKind nesting = element.getNestingKind();
    if (!declared.getTypeArguments().isEmpty()
        || !element.getTypeParameters().isEmpty()
        || (nesting != NestingKind.TOP_LEVEL && nesting != NestingKind.MEMBER)) {
      return null;
    }
    return element.getQualifiedName().toString();
  }

  private static final Pattern THIS_OR_SUPER = Pattern.compile("\\b(this|super)\\b");

  /**
   * Rewrites a lambda as an anonymous class implementing the lambda's functional interface: {@code
   * x -> f(x)} becomes {@code new Function<A, B>() { @Override public B apply(A x) { return f(x); }
   * }}, on the same lines. A lambda is left alone when its body uses {@code this} or {@code super},
   * or any simple name that names a member of the interface or of one of its supertypes, {@code
   * Object} included, since inside the class those names mean something else; when a type it needs,
   * a thrown type included, has no source form, such as a captured wildcard; when the interface
   * method's signature mentions a type variable or declares type parameters; when its interface
   * comes from a class file rather than from the seed; when the interface is in another package or
   * under another {@code @NullMarked} or {@code @NullUnmarked} scope than the lambda and the
   * rewrite would copy a reference type from it, the return type or an inferred parameter type,
   * whose nullness default would change; when an inferred parameter carries an annotation; and when
   * it is an argument whose type javac infers.
   */
  static final Relation LAMBDA_TO_ANONYMOUS_CLASS =
      new Attributed(
          "lambda-to-anonymous-class",
          (unit, parsed, tree, trees, types, changes) ->
              new TreePathScanner<Void, Void>() {
                @Override
                public Void visitLambdaExpression(LambdaExpressionTree lambda, Void unused) {
                  if (isInferenceArgument(getCurrentPath(), trees)) {
                    return super.visitLambdaExpression(lambda, null);
                  }
                  String replacement =
                      anonymousClass(parsed, lambda, getCurrentPath(), trees, types);
                  if (replacement != null) {
                    changes.add(
                        Change.of(
                            unit,
                            new TextEdit(parsed.start(lambda), parsed.end(lambda), replacement)));
                  }
                  return super.visitLambdaExpression(lambda, null);
                }
              }.scan(tree, null));

  /** Returns the element of the method, field, or class nearest to {@code path}, or null. */
  private static @Nullable Element enclosingElement(TreePath path, Trees trees) {
    for (TreePath p = path; p != null; p = p.getParentPath()) {
      Tree.Kind kind = p.getLeaf().getKind();
      if (kind == Tree.Kind.METHOD
          || kind == Tree.Kind.VARIABLE
          || p.getLeaf() instanceof com.sun.source.tree.ClassTree) {
        Element element = trees.getElement(p);
        if (element != null) {
          return element;
        }
      }
    }
    return null;
  }

  /**
   * Returns the simple name of the nearest {@code NullMarked} or {@code NullUnmarked} annotation on
   * {@code element} or a class or method enclosing it, or the empty string if there is none. The
   * package is left out: whether its annotation applies depends on NullAway's options, such as
   * {@code UnannotatedSubPackages}, so callers compare packages separately.
   */
  private static String nullMarking(Element element) {
    for (Element e = element;
        e != null && e.getKind() != ElementKind.PACKAGE && e.getKind() != ElementKind.MODULE;
        e = e.getEnclosingElement()) {
      for (var annotation : e.getAnnotationMirrors()) {
        String name = annotation.getAnnotationType().asElement().getSimpleName().toString();
        if (name.equals("NullMarked") || name.equals("NullUnmarked")) {
          return name;
        }
      }
    }
    return "";
  }

  private static String packageOf(Element element) {
    Element e = element;
    while (e.getKind() != ElementKind.PACKAGE) {
      e = e.getEnclosingElement();
    }
    return e.toString();
  }

  /**
   * Returns whether the lambda at {@code path} is an argument of a call to a generic method or of a
   * diamond constructor. There javac infers the type arguments without nullness and NullAway infers
   * its own, so an anonymous class that spells out javac's inference is a different program.
   */
  private static boolean isInferenceArgument(TreePath path, Trees trees) {
    Tree parent = path.getParentPath().getLeaf();
    if (parent instanceof MethodInvocationTree) {
      return trees.getElement(path.getParentPath()) instanceof ExecutableElement method
          && !method.getTypeParameters().isEmpty();
    }
    if (parent instanceof com.sun.source.tree.NewClassTree newClass) {
      return newClass.getIdentifier() instanceof com.sun.source.tree.ParameterizedTypeTree type
          && type.getTypeArguments().isEmpty();
    }
    return false;
  }

  private static @Nullable String anonymousClass(
      ParsedUnit parsed, LambdaExpressionTree lambda, TreePath path, Trees trees, Types types) {
    Type target = ((JCTree.JCLambda) lambda).type;
    if (target == null || target.isErroneous() || parsed.start(lambda) < 0) {
      return null;
    }
    // Only an interface compiled from the seed's sources: for one from a class file, such as
    // java.util.function.Function, NullAway lets a lambda take the nullness of an unannotated
    // method, while an overriding method declared in annotated code gets non-null defaults.
    JavaFileObject origin = target.tsym.outermostClass().classfile;
    if (origin == null || origin.getKind() != JavaFileObject.Kind.SOURCE) {
      return null;
    }
    Symbol descriptor;
    try {
      descriptor = types.findDescriptorSymbol(target.tsym);
    } catch (RuntimeException e) {
      return null;
    }
    // The anonymous class declares the interface method's own signature, annotations included.
    Symbol.MethodSymbol declared = (Symbol.MethodSymbol) descriptor;
    Type method = declared.type;
    // A signature that mentions a type variable would need the nullness NullAway infers for it,
    // which javac's substitution drops, so such a lambda stays as it is.
    if (mentionsTypeVariable(method.getReturnType())
        || method.getParameterTypes().stream().anyMatch(AttributedRelations::mentionsTypeVariable)
        || !declared.getTypeParameters().isEmpty()) {
      return null;
    }
    // A type the anonymous class copies from the interface method, its return type or the type of
    // an inferred parameter, takes the nullness defaults of the lambda's location, while in the
    // lambda it has the defaults of the interface. Where the two differ, only a type that has no
    // nullness, such as void or a primitive, is copied. An explicitly typed parameter is declared
    // at the lambda's location in both forms.
    Element enclosing = enclosingElement(path, trees);
    if (enclosing == null) {
      return null;
    }
    boolean defaultsDiffer =
        !nullMarking(enclosing).equals(nullMarking(declared))
            || !packageOf(enclosing).equals(packageOf(declared));
    if (defaultsDiffer && !method.getReturnType().isPrimitiveOrVoid()) {
      return null;
    }
    // Inside the anonymous class, a simple name the body uses could bind to a member the interface
    // declares or inherits, such as a default method, where in the lambda it binds to the
    // enclosing scope.
    String body = parsed.textOf(lambda.getBody());
    Set<String> members = new TreeSet<>(List.of("equals", "hashCode", "toString", "getClass"));
    for (Type supertype : types.closure(target)) {
      for (Symbol member : supertype.tsym.members().getSymbols()) {
        if (!member.isConstructor()) {
          members.add(member.getSimpleName().toString());
        }
      }
    }
    if (THIS_OR_SUPER.matcher(body).find()
        || Pattern.compile(
                "(?<![.\\w$])(?:"
                    + members.stream().map(Pattern::quote).collect(Collectors.joining("|"))
                    + ")(?![\\w$])")
            .matcher(body)
            .find()) {
      return null;
    }
    String targetName = sourceName(target);
    String returnType = withDeclarationNullable(declared, sourceName(method.getReturnType()));
    if (targetName == null || returnType == null) {
      return null;
    }
    List<String> params = new ArrayList<>();
    List<? extends VariableTree> lambdaParams = lambda.getParameters();
    for (int i = 0; i < lambdaParams.size(); i++) {
      VariableTree p = lambdaParams.get(i);
      // javac 25 gives the type of a var parameter a start position and no end position.
      if (p.getType() != null
          && parsed.start(p.getType()) >= 0
          && parsed.end(p.getType()) >= 0
          && !parsed.textOf(p.getType()).equals("var")) {
        params.add(parsed.textOf(p));
      } else if (!p.getModifiers().getAnnotations().isEmpty()) {
        // An annotation on an inferred parameter, such as @Nullable var s, would be lost.
        return null;
      } else {
        Symbol.VarSymbol paramSymbol = declared.getParameters().get(i);
        if (defaultsDiffer && !paramSymbol.type.isPrimitive()) {
          return null;
        }
        String paramType = withDeclarationNullable(paramSymbol, sourceName(paramSymbol.type));
        if (paramType == null) {
          return null;
        }
        params.add(paramType + " " + p.getName());
      }
    }
    List<String> thrown = new ArrayList<>();
    for (Type t : method.getThrownTypes()) {
      String s = sourceName(t);
      if (s == null) {
        return null;
      }
      thrown.add(s);
    }
    // The line breaks between the parameters and the body move in front of the body, so every
    // line of the body stays on its line.
    String breaks =
        "\n"
            .repeat(
                (int)
                    parsed
                        .text()
                        .substring(parsed.start(lambda), parsed.start(lambda.getBody()))
                        .chars()
                        .filter(c -> c == '\n')
                        .count());
    String block =
        lambda.getBodyKind() == LambdaExpressionTree.BodyKind.STATEMENT
            ? breaks + body
            : returnType.equals("void")
                ? "{ " + breaks + body + "; }"
                : "{ return " + breaks + body + "; }";
    return "new "
        + targetName
        + "() { @Override public "
        + returnType
        + " "
        + descriptor.getSimpleName()
        + "("
        + String.join(", ", params)
        + ")"
        + (thrown.isEmpty() ? "" : " throws " + String.join(", ", thrown))
        + " "
        + block
        + " }";
  }

  /**
   * Returns {@code type}, the source name of the type {@code symbol} declares, with a JSpecify
   * {@code @Nullable} added when {@code symbol} carries a declaration annotation named {@code
   * Nullable}, such as {@code javax.annotation.Nullable}; a type-use annotation is already part of
   * {@code type}. Returns null if {@code type} is null or cannot take the annotation.
   */
  private static @Nullable String withDeclarationNullable(Symbol symbol, @Nullable String type) {
    if (type == null) {
      return null;
    }
    boolean declarationNullable =
        symbol.getDeclarationAttributes().stream()
            .anyMatch(
                a -> a.getAnnotationType().asElement().getSimpleName().contentEquals("Nullable"));
    return declarationNullable && !type.contains("Nullable") ? annotateNullable(type) : type;
  }

  /** Returns whether {@code type} is or contains a type variable. */
  private static boolean mentionsTypeVariable(Type type) {
    if (type.getKind() == TypeKind.TYPEVAR) {
      return true;
    }
    if (type instanceof Type.ArrayType array) {
      return mentionsTypeVariable(array.elemtype);
    }
    if (type instanceof Type.WildcardType wildcard) {
      return wildcard.type != null && mentionsTypeVariable(wildcard.type);
    }
    return type.getTypeArguments().stream().anyMatch(AttributedRelations::mentionsTypeVariable);
  }

  /**
   * Returns {@code type}, a source name from {@link #sourceName}, with a JSpecify {@code @Nullable}
   * on its outermost type, or null for an array or primitive type, where the annotation would mean
   * something else or nothing.
   */
  private static @Nullable String annotateNullable(String type) {
    if (type.endsWith("]") || (!type.contains(".") && Character.isLowerCase(type.charAt(0)))) {
      return null;
    }
    int typeArgs = type.indexOf('<');
    String base = typeArgs < 0 ? type : type.substring(0, typeArgs);
    int simple = base.lastIndexOf('.') + 1;
    return type.substring(0, simple)
        + "@org.jspecify.annotations.Nullable "
        + type.substring(simple);
  }

  /**
   * Returns how {@code type} is written in source, with its type annotations, or null if it has no
   * source form or cannot be the type of an anonymous class's supertype or member.
   */
  private static @Nullable String sourceName(Type type) {
    // javac prints a type-use annotation in source form, such as
    // java.util.List<java.lang.@org.jspecify.annotations.Nullable String>, so it stays.
    String s = type.toString();
    if (s.contains("capture#")
        || s.contains("&")
        || s.contains("<any>")
        || s.contains("<anonymous")) {
      return null;
    }
    if (type.getKind() == TypeKind.DECLARED) {
      Element e = type.asElement();
      if (e.getKind() == ElementKind.CLASS
          && ((TypeElement) e).getNestingKind() == NestingKind.LOCAL) {
        return null;
      }
      // A wildcard can parameterize a lambda's target type, but not an anonymous class's supertype.
      if (type.getTypeArguments().stream().anyMatch(a -> a.getKind() == TypeKind.WILDCARD)) {
        return null;
      }
    }
    return s;
  }
}
