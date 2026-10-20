package com.uber.nullaway.oracles;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.sun.source.tree.AnnotatedTypeTree;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WildcardTree;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.jspecify.annotations.Nullable;

/**
 * Derives variants of a seed by adding and removing nullness annotations.
 *
 * <p>Every edit keeps the unit syntactically valid, and most keep it compilable: an added
 * annotation is fully qualified, so it needs no import, and a removed one leaves at most an unused
 * import behind. The edits are:
 *
 * <ul>
 *   <li>{@code @Nullable} on a type in a field, a method signature, or a type-parameter bound,
 *       including each type argument, each wildcard bound, and both the element and the array level
 *       of an array type;
 *   <li>{@code @NullMarked} on a named class that has none, and {@code @NullUnmarked} on a method;
 *   <li>removal of any annotation named {@code Nullable}, {@code NonNull}, {@code NullMarked}, or
 *       {@code NullUnmarked}, whatever its package.
 * </ul>
 *
 * <p>A type-use annotation on a scoping construct, such as {@code @Nullable java.util.List}, does
 * not compile, so a qualified type is annotated before its last name: {@code java.util.@Nullable
 * List}. A variant that still fails to compile is left for javac to reject.
 */
final class AnnotationMutator {

  private static final String NULLABLE = "@org.jspecify.annotations.Nullable ";
  private static final String NULL_MARKED = "@org.jspecify.annotations.NullMarked ";
  private static final String NULL_UNMARKED = "@org.jspecify.annotations.NullUnmarked ";
  private static final ImmutableSet<String> REMOVABLE =
      ImmutableSet.of("Nullable", "NonNull", "NullMarked", "NullUnmarked");

  /**
   * One edit to one unit of a seed.
   *
   * @param unitIndex index of the unit in the seed's sources
   * @param edit the edit, with offsets into that unit's text
   * @param description what the edit does and where, for the report
   */
  record Mutation(int unitIndex, TextEdit edit, String description) {}

  /**
   * A seed with some mutations applied.
   *
   * @param sources the edited units, in the seed's order
   * @param mutations the mutations applied
   */
  record Variant(ImmutableList<Source> sources, ImmutableList<Mutation> mutations) {}

  private AnnotationMutator() {}

  /** Returns whether {@code type} carries an annotation named {@code NullMarked}. */
  private static boolean isNullMarked(ClassTree type) {
    return type.getModifiers().getAnnotations().stream()
        .anyMatch(a -> a.getAnnotationType().toString().endsWith("NullMarked"));
  }

  /** Returns every mutation this class can make to {@code seed}. */
  static ImmutableList<Mutation> candidates(Seed seed) {
    ImmutableList.Builder<Mutation> result = ImmutableList.builder();
    for (int i = 0; i < seed.sources().size(); i++) {
      ParsedUnit parsed = ParsedUnit.parse(seed.sources().get(i));
      new CandidateScanner(parsed, i, result).scan(parsed.unit(), null);
    }
    return result.build();
  }

  /**
   * Returns a variant of {@code seed} with between one and {@code maxMutations} of {@code
   * candidates} applied, or null if {@code candidates} is empty. Two mutations that would overlap
   * are never applied together, so the variant may carry fewer than were drawn.
   */
  static @Nullable Variant mutate(
      Seed seed, List<Mutation> candidates, Random random, int maxMutations) {
    if (candidates.isEmpty()) {
      return null;
    }
    int count = 1 + random.nextInt(maxMutations);
    List<Mutation> chosen = new ArrayList<>();
    for (int attempt = 0; attempt < count; attempt++) {
      Mutation next = candidates.get(random.nextInt(candidates.size()));
      if (chosen.stream().noneMatch(m -> overlaps(m, next))) {
        chosen.add(next);
      }
    }
    ImmutableList.Builder<Source> sources = ImmutableList.builder();
    for (int i = 0; i < seed.sources().size(); i++) {
      int unit = i;
      List<TextEdit> edits =
          chosen.stream().filter(m -> m.unitIndex() == unit).map(Mutation::edit).toList();
      Source source = seed.sources().get(i);
      sources.add(source.withText(TextEdit.apply(source.text(), edits)));
    }
    return new Variant(sources.build(), ImmutableList.copyOf(chosen));
  }

  /**
   * Returns whether applying both {@code a} and {@code b} could garble the text: they are the same
   * mutation, or one starts strictly inside the span the other replaces.
   */
  private static boolean overlaps(Mutation a, Mutation b) {
    if (a.unitIndex() != b.unitIndex()) {
      return false;
    }
    if (a.equals(b)) {
      return true;
    }
    TextEdit x = a.edit();
    TextEdit y = b.edit();
    return (x.start() < y.start() && y.start() < x.end())
        || (y.start() < x.start() && x.start() < y.end());
  }

  /** Collects the mutations for one unit. */
  private static final class CandidateScanner extends TreeScanner<Void, Void> {
    private final ParsedUnit parsed;
    private final int unitIndex;
    private final ImmutableList.Builder<Mutation> result;

    CandidateScanner(ParsedUnit parsed, int unitIndex, ImmutableList.Builder<Mutation> result) {
      this.parsed = parsed;
      this.unitIndex = unitIndex;
      this.result = result;
    }

    @Override
    public Void visitClass(ClassTree tree, Void unused) {
      // An anonymous class, enum constant bodies included, starts at its opening brace, where an
      // annotation does not parse.
      if (!isNullMarked(tree) && !tree.getSimpleName().isEmpty()) {
        insert(parsed.start(tree), NULL_MARKED, "@NullMarked on class " + tree.getSimpleName());
      }
      for (TypeParameterTree typeParameter : tree.getTypeParameters()) {
        typeParameter.getBounds().forEach(this::typeSites);
      }
      for (Tree member : tree.getMembers()) {
        if (member instanceof VariableTree field) {
          typeSites(field.getType());
        } else if (member instanceof MethodTree method) {
          insert(
              parsed.start(method), NULL_UNMARKED, "@NullUnmarked on method " + method.getName());
          typeSites(method.getReturnType());
          method.getParameters().forEach(p -> typeSites(p.getType()));
          for (TypeParameterTree typeParameter : method.getTypeParameters()) {
            typeParameter.getBounds().forEach(this::typeSites);
          }
        }
      }
      return super.visitClass(tree, null);
    }

    @Override
    public Void visitAnnotation(AnnotationTree tree, Void unused) {
      Tree type = tree.getAnnotationType();
      String simpleName =
          type instanceof MemberSelectTree select
              ? select.getIdentifier().toString()
              : type.toString();
      int start = parsed.start(tree);
      int end = parsed.end(tree);
      if (REMOVABLE.contains(simpleName) && start >= 0 && end > start) {
        result.add(
            new Mutation(
                unitIndex,
                new TextEdit(start, end, ""),
                "remove " + parsed.textOf(tree) + " at " + location(start)));
      }
      return super.visitAnnotation(tree, null);
    }

    /** Adds a {@code @Nullable} insertion for every type-use position within {@code type}. */
    private void typeSites(@Nullable Tree type) {
      if (type == null || parsed.start(type) < 0) {
        return;
      }
      if (type instanceof IdentifierTree identifier) {
        insert(parsed.start(identifier), NULLABLE, "@Nullable on " + identifier.getName());
      } else if (type instanceof MemberSelectTree select) {
        insert(
            parsed.end(select) - select.getIdentifier().length(),
            NULLABLE,
            "@Nullable on " + parsed.textOf(select));
      } else if (type instanceof ParameterizedTypeTree parameterized) {
        typeSites(parameterized.getType());
        parameterized.getTypeArguments().forEach(this::typeSites);
      } else if (type instanceof ArrayTypeTree array) {
        typeSites(array.getType());
        int end = parsed.end(array.getType());
        if (end >= 0) {
          insert(end, " " + NULLABLE.trim(), "@Nullable on array " + parsed.textOf(array));
        }
      } else if (type instanceof WildcardTree wildcard) {
        typeSites(wildcard.getBound());
      } else if (type instanceof AnnotatedTypeTree annotated) {
        typeSites(annotated.getUnderlyingType());
      }
    }

    private void insert(int offset, String text, String description) {
      if (offset >= 0) {
        result.add(
            new Mutation(
                unitIndex,
                new TextEdit(offset, offset, text),
                description + " at " + location(offset)));
      }
    }

    private String location(int offset) {
      return parsed.unit().getSourceFile().getName()
          + ":"
          + parsed.unit().getLineMap().getLineNumber(offset);
    }
  }
}
