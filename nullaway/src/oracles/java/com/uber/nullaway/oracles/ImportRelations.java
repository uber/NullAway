package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.uber.nullaway.oracles.AttributedRelations.Attribution;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import org.jspecify.annotations.Nullable;

/**
 * Relations that spell the name of a top-level class differently: fully qualified, or simple with a
 * single-type import. Both name the same class, so NullAway must report the same warnings.
 *
 * <ul>
 *   <li>{@link #SIMPLE_NAMES} shortens {@code java.util.List} to {@code List}, annotation names
 *       such as {@code @org.jspecify.annotations.Nullable} included, and {@code java.util.@Nullable
 *       List} to {@code @Nullable List}, and adds {@code import java.util.List;} unless the unit
 *       imports it already.
 *   <li>{@link #QUALIFIED_NAMES} qualifies {@code List} as {@code java.util.List}, and
 *       {@code @Nullable List} as {@code java.util.@Nullable List}, since a type-use annotation may
 *       not precede a package name.
 * </ul>
 *
 * <p>Every line stays on its line number: an added import goes on the line of the package
 * declaration, or of the first import, or at the start of the unit. A name is left alone where the
 * rewrite could make it, or another name, denote a different class: a simple name that the unit
 * declares or imports from elsewhere, or already uses for another class. A type that follows an
 * annotation in the modifiers of a declaration keeps its simple name, since the annotation may be a
 * type-use annotation, which a package name may not follow. An added import shadows a class of the
 * same package with that simple name, which is safe while the unit never names that class. Each
 * variant is attributed again and kept only if javac accepts it and every class reference in it,
 * read in source order, denotes the same class as in the seed.
 */
final class ImportRelations {

  private ImportRelations() {}

  /**
   * Variants with one class's names rewritten, tried when the variant rewriting all is rejected.
   */
  private static final int SINGLES = 5;

  /** Returns every relation in this class. */
  static ImmutableList<Relation> all() {
    return ImmutableList.of(SIMPLE_NAMES, QUALIFIED_NAMES);
  }

  /** Rewrites fully qualified names of top-level classes as simple names with imports. */
  static final Relation SIMPLE_NAMES = new Rewrite("simple-names", true);

  /** Rewrites simple names of top-level classes in named packages as fully qualified names. */
  static final Relation QUALIFIED_NAMES = new Rewrite("qualified-names", false);

  /**
   * The edits that rewrite the names of one class in one unit.
   *
   * @param unit index of the unit in the seed
   * @param edits the edits, with offsets into that unit's text
   */
  private record ClassChange(int unit, List<TextEdit> edits) {}

  /**
   * One of the two relations.
   *
   * @param name the relation's name
   * @param toSimple whether qualified names become simple, rather than the reverse
   */
  private record Rewrite(String name, boolean toSimple) implements Relation {
    @Override
    public ImmutableList<ImmutableList<Source>> variants(Seed seed) {
      Attribution original = AttributedRelations.attribute(seed);
      if (original == null) {
        return ImmutableList.of();
      }
      List<ClassChange> changes = new ArrayList<>();
      for (CompilationUnitTree unit : original.units()) {
        int index = AttributedRelations.indexOf(seed, unit);
        ParsedUnit parsed =
            new ParsedUnit(
                unit, seed.sources().get(index).text(), original.trees().getSourcePositions());
        changes.addAll(
            toSimple
                ? simpleNameChanges(index, parsed, unit, original.trees())
                : qualifiedNameChanges(index, parsed, unit, original.trees()));
      }
      if (changes.isEmpty()) {
        return ImmutableList.of();
      }
      List<String> before = references(seed, original);
      ImmutableList.Builder<ImmutableList<Source>> result = ImmutableList.builder();
      ImmutableList<Source> all = apply(seed, changes);
      if (sameReferences(seed, all, before)) {
        return ImmutableList.of(all);
      }
      if (changes.size() > 1) {
        for (ClassChange change : changes.stream().limit(SINGLES).toList()) {
          ImmutableList<Source> single = apply(seed, List.of(change));
          if (sameReferences(seed, single, before)) {
            result.add(single);
          }
        }
      }
      return result.build();
    }
  }

  /**
   * Returns one change per top-level class that the unit names by its fully qualified name, which
   * deletes the package prefix of every such name and adds the import. The trees javac synthesizes
   * for an anonymous class share the tree of its supertype, so a site may be seen twice; a set of
   * edits applies it once.
   */
  private static List<ClassChange> simpleNameChanges(
      int unitIndex, ParsedUnit parsed, CompilationUnitTree unit, Trees trees) {
    Map<String, String> imported = singleTypeImports(unit);
    Set<String> declared = declaredNames(unit);
    Map<String, Set<String>> simpleUses = simpleNameUses(unit, trees);
    Map<String, Set<TextEdit>> sites = new LinkedHashMap<>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitImport(ImportTree tree, Void unused) {
        return null;
      }

      @Override
      public Void visitPackage(PackageTree tree, Void unused) {
        return null;
      }

      @Override
      public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
        TypeElement type = topLevelClass(trees.getElement(getCurrentPath()));
        Element qualifier = trees.getElement(new TreePath(getCurrentPath(), tree.getExpression()));
        if (type != null && qualifier instanceof PackageElement) {
          // javac gives some trees it synthesizes, such as those of an anonymous class, positions
          // that do not span their text, so the qualifier must end right at its dot.
          int start = parsed.start(tree.getExpression());
          int dot = parsed.end(tree.getExpression());
          String text = parsed.text();
          if (start >= 0
              && dot > start
              && dot < text.length()
              && text.charAt(dot) == '.'
              && !text.substring(start, dot).contains("\n")) {
            sites
                .computeIfAbsent(type.getQualifiedName().toString(), k -> new LinkedHashSet<>())
                .add(new TextEdit(start, dot + 1, ""));
          }
          return null;
        }
        return super.visitMemberSelect(tree, null);
      }
    }.scan(new TreePath(unit), null);
    int anchor = importAnchor(parsed, unit);
    List<ClassChange> result = new ArrayList<>();
    sites.forEach(
        (qualified, edits) -> {
          String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
          String importedAs = imported.get(simple);
          Set<String> uses = simpleUses.getOrDefault(simple, Set.of());
          if (declared.contains(simple)
              || (importedAs != null && !importedAs.equals(qualified))
              || uses.stream().anyMatch(u -> !u.equals(qualified))) {
            return;
          }
          List<TextEdit> all = new ArrayList<>(edits);
          if (importedAs == null) {
            String declaration = "import " + qualified + ";";
            all.add(
                new TextEdit(anchor, anchor, anchor == 0 ? declaration + " " : " " + declaration));
          }
          result.add(new ClassChange(unitIndex, all));
        });
    return result;
  }

  /**
   * Returns one change per top-level class in a named package that the unit names by its simple
   * name outside the imports, which qualifies every such name with the package.
   */
  private static List<ClassChange> qualifiedNameChanges(
      int unitIndex, ParsedUnit parsed, CompilationUnitTree unit, Trees trees) {
    Set<Tree> afterDeclarationAnnotations = typesAfterModifierAnnotations(unit);
    Map<String, Set<TextEdit>> sites = new LinkedHashMap<>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitImport(ImportTree tree, Void unused) {
        return null;
      }

      @Override
      public Void visitPackage(PackageTree tree, Void unused) {
        return null;
      }

      @Override
      public Void visitIdentifier(IdentifierTree tree, Void unused) {
        TypeElement type = topLevelClass(trees.getElement(getCurrentPath()));
        int start = parsed.start(tree);
        if (type == null || afterDeclarationAnnotations.contains(tree) || start < 0) {
          return null;
        }
        String qualified = type.getQualifiedName().toString();
        String prefix = qualified.substring(0, qualified.length() - type.getSimpleName().length());
        if (prefix.isEmpty()) {
          return null;
        }
        // A type-use annotation may not precede a package name, so the package goes in front of
        // the annotations: @Nullable List becomes java.util.@Nullable List.
        Tree parent = getCurrentPath().getParentPath().getLeaf();
        int at =
            parent instanceof AnnotatedTypeTree annotated
                    && annotated.getUnderlyingType().equals(tree)
                    && parsed.start(annotated) >= 0
                ? parsed.start(annotated)
                : start;
        sites
            .computeIfAbsent(qualified, k -> new LinkedHashSet<>())
            .add(new TextEdit(at, at, prefix));
        return null;
      }
    }.scan(new TreePath(unit), null);
    List<ClassChange> result = new ArrayList<>();
    sites.forEach(
        (qualified, edits) -> result.add(new ClassChange(unitIndex, new ArrayList<>(edits))));
    return result;
  }

  /** Returns {@code element} if it is a top-level class, interface, enum, record, or annotation. */
  private static @Nullable TypeElement topLevelClass(@Nullable Element element) {
    return element instanceof TypeElement type && type.getNestingKind() == NestingKind.TOP_LEVEL
        ? type
        : null;
  }

  /** Returns the classes the unit imports by single-type imports, keyed by simple name. */
  private static Map<String, String> singleTypeImports(CompilationUnitTree unit) {
    Map<String, String> result = new LinkedHashMap<>();
    for (ImportTree imported : unit.getImports()) {
      String name = imported.getQualifiedIdentifier().toString();
      if (!imported.isStatic() && !name.endsWith(".*")) {
        result.put(name.substring(name.lastIndexOf('.') + 1), name);
      }
    }
    return result;
  }

  /** Returns the simple names of every class and type parameter the unit declares. */
  private static Set<String> declaredNames(CompilationUnitTree unit) {
    Set<String> result = new HashSet<>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitClass(ClassTree tree, Void unused) {
        result.add(tree.getSimpleName().toString());
        return super.visitClass(tree, null);
      }

      @Override
      public Void visitTypeParameter(TypeParameterTree tree, Void unused) {
        result.add(tree.getName().toString());
        return super.visitTypeParameter(tree, null);
      }
    }.scan(new TreePath(unit), null);
    return result;
  }

  /** Returns, for each simple name the unit uses for a class, the classes it denotes. */
  private static Map<String, Set<String>> simpleNameUses(CompilationUnitTree unit, Trees trees) {
    Map<String, Set<String>> result = new LinkedHashMap<>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitIdentifier(IdentifierTree tree, Void unused) {
        if (trees.getElement(getCurrentPath()) instanceof TypeElement type) {
          result
              .computeIfAbsent(tree.getName().toString(), k -> new HashSet<>())
              .add(type.getQualifiedName().toString());
        }
        return null;
      }
    }.scan(new TreePath(unit), null);
    return result;
  }

  /**
   * Returns the base type trees of declarations whose modifiers carry annotations: a package name
   * may not follow a type-use annotation there, and the declaration annotations cannot move.
   */
  private static Set<Tree> typesAfterModifierAnnotations(CompilationUnitTree unit) {
    Set<Tree> result = new HashSet<>();
    new TreePathScanner<Void, Void>() {
      @Override
      public Void visitVariable(VariableTree tree, Void unused) {
        add(tree.getModifiers(), tree.getType());
        return super.visitVariable(tree, null);
      }

      @Override
      public Void visitMethod(MethodTree tree, Void unused) {
        add(tree.getModifiers(), tree.getReturnType());
        return super.visitMethod(tree, null);
      }

      private void add(ModifiersTree modifiers, @Nullable Tree type) {
        if (modifiers.getAnnotations().isEmpty()) {
          return;
        }
        Tree base = type;
        while (base instanceof ParameterizedTypeTree || base instanceof ArrayTypeTree) {
          base =
              base instanceof ParameterizedTypeTree parameterized
                  ? parameterized.getType()
                  : ((ArrayTypeTree) base).getType();
        }
        if (base != null) {
          result.add(base);
        }
      }
    }.scan(new TreePath(unit), null);
    return result;
  }

  /**
   * Returns the offset where an added import keeps every line on its number: just past the
   * semicolon of the package declaration, else of the first import, else the start of the unit.
   */
  private static int importAnchor(ParsedUnit parsed, CompilationUnitTree unit) {
    Tree first =
        unit.getPackage() != null
            ? unit.getPackage()
            : unit.getImports().isEmpty() ? null : unit.getImports().get(0);
    if (first == null || parsed.end(first) < 0) {
      return 0;
    }
    int semicolon = parsed.text().indexOf(';', parsed.end(first) - 1);
    return semicolon < 0 ? 0 : semicolon + 1;
  }

  /**
   * Returns the qualified name of the class every identifier and member select outside the imports
   * and the package declaration denotes, in source order and unit by unit in seed order.
   */
  private static List<String> references(Seed seed, Attribution attribution) {
    List<List<String>> byUnit = new ArrayList<>();
    for (int i = 0; i < seed.sources().size(); i++) {
      byUnit.add(new ArrayList<>());
    }
    for (CompilationUnitTree unit : attribution.units()) {
      List<String> out = byUnit.get(AttributedRelations.indexOf(seed, unit));
      Trees trees = attribution.trees();
      new TreePathScanner<Void, Void>() {
        @Override
        public Void visitImport(ImportTree tree, Void unused) {
          return null;
        }

        @Override
        public Void visitPackage(PackageTree tree, Void unused) {
          return null;
        }

        @Override
        public Void visitIdentifier(IdentifierTree tree, Void unused) {
          record(trees.getElement(getCurrentPath()));
          return null;
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
          record(trees.getElement(getCurrentPath()));
          return super.visitMemberSelect(tree, null);
        }

        private void record(@Nullable Element element) {
          if (element instanceof TypeElement type) {
            out.add(type.getQualifiedName().toString());
          }
        }
      }.scan(new TreePath(unit), null);
    }
    List<String> result = new ArrayList<>();
    byUnit.forEach(result::addAll);
    return result;
  }

  /**
   * Returns whether javac accepts {@code variant} and every class reference in it denotes the same
   * class as the reference at the same place in {@code before}.
   */
  private static boolean sameReferences(
      Seed seed, ImmutableList<Source> variant, List<String> before) {
    Seed rewritten = new Seed(seed.name(), variant);
    Attribution attribution = AttributedRelations.attribute(rewritten);
    return attribution != null && references(rewritten, attribution).equals(before);
  }

  /** Returns the units of {@code seed} with the edits of {@code changes} applied. */
  private static ImmutableList<Source> apply(Seed seed, List<ClassChange> changes) {
    ImmutableList.Builder<Source> result = ImmutableList.builder();
    for (int i = 0; i < seed.sources().size(); i++) {
      int unit = i;
      List<TextEdit> edits =
          changes.stream().filter(c -> c.unit() == unit).flatMap(c -> c.edits().stream()).toList();
      Source source = seed.sources().get(i);
      result.add(source.withText(TextEdit.apply(source.text(), edits)));
    }
    return result.build();
  }
}
