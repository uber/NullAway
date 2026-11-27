package com.uber.nullaway.generics;

import static com.uber.nullaway.generics.TypeMetadataBuilder.TYPE_METADATA_BUILDER;

import com.google.errorprone.VisitorState;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.BoundKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.TypeTag;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.Config;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.handlers.Handler;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.function.Function;
import javax.lang.model.type.TypeKind;
import org.jspecify.annotations.Nullable;

/**
 * Renders the diagnostic for a pseudo-assignment whose source type does not have the nullness its
 * target type requires.
 *
 * <p>For {@code take(m)} with {@code m} a {@code Map<?, String>} and {@code take} requiring {@code
 * Map<? extends Object, String>}, the text reads:
 *
 * <pre>
 * incompatible nullability in Map type argument K -&gt; wildcard upper bound: found @Nullable Object, required Object
 *     found:    Map&lt;?, String&gt;
 *                   ^
 *     required: Map&lt;? extends Object, String&gt;
 *                             ^^^^^^
 *     note: the source ? has no explicit upper bound, so its upper bound is inherited from type parameter K of Map
 *     fix: declare m as Map&lt;? extends Object, String&gt;
 * </pre>
 *
 * <p>The message is built from the positions the check itself rejected, which {@link
 * NullabilityMismatches} records while {@link CheckIdenticalNullabilityVisitor} runs, and this
 * class decides what a reader is told about them. The rules below are the contract every such
 * message follows, and a new reporter that reaches this class inherits them; a reporter that builds
 * its own message text is not bound by them.
 *
 * <h2>The vocabulary</h2>
 *
 * <p>Each line opens with a key that means the same thing wherever it appears: {@code found} is the
 * source type, {@code found as} the supertype of it the check compared, {@code required} the target
 * type, {@code path} where in the type one difference sits, {@code note} a fact about the source
 * that its printed form does not show, and {@code fix} a declaration whose rewritten type the check
 * accepts. Where there are several differences, each gets a numbered entry with its own {@code
 * path}, {@code found}, and {@code required}, five at most, and {@code and N more, not listed}
 * counts the rest.
 *
 * <h2>The first line</h2>
 *
 * <p>It stands alone, since a tool may show only that line: {@code incompatible nullability in},
 * the path of the first difference, how many other positions differ, and then the two nodes there.
 * It states the two nodes rather than calling the source unassignable to the target, because the
 * difference runs both ways: a type argument the source states outright has to match, so {@code
 * String} fails against {@code @Nullable String} as surely as the reverse. That direction is the
 * one a reader can misread as a false subtype claim, since every {@code String} is a
 * {@code @Nullable String}, so there the line says first that a type argument has to match exactly.
 * Where the target bounds a {@code ? super} wildcard, the required side reads {@code a supertype
 * of} that bound.
 *
 * <p>Where the check records no position, the message is the two types and the subtype relation
 * between them, opening {@code incompatible types:}, which is all that can be said about a
 * difference nothing located.
 *
 * <h2>The caret</h2>
 *
 * <p>A caret marks the smallest node whose nullness differs, {@code @Nullable String} rather than
 * the {@code Collection<@Nullable String>} around it, and it marks that node in both printed types.
 * The check compares the source viewed as the target's class, which is the source's own class or a
 * supertype of it, so which node of the type the reader wrote the caret belongs under has two
 * answers: where the view is the same class, the position is the same position; where it is a
 * supertype, only a node the substitution carried over from the written type can be pointed at.
 * Viewing an {@code ArrayList<@Nullable String>} as a {@code List} carries the {@code @Nullable
 * String} over; for {@code class Sub extends Base<List<@Nullable String>>}, viewing a {@code Sub}
 * as a {@code Base} rebuilds the {@code List<@Nullable String>} from the declaration of {@code
 * Sub}, which the written type does not hold. Where neither answers, the view is printed as {@code
 * found as} and carries the carets. Two differences below one printed node share its caret,
 * numbered for each.
 *
 * <p>A note explains itself in the entities the message already shows. Where the source wildcard
 * stands at a position of the type the reader wrote, including one the supertype view carried over
 * from it, the note names the type parameter it takes its bound from. Where the view rebuilt the
 * wildcard, as from the supertype a subclass declares, that parameter belongs to a declaration this
 * message never prints, and naming it would set a second frame of reference against the {@code
 * path} line: {@code Base type argument B} beside {@code type parameter S of Sub} reads as a
 * contradiction rather than as two answers. The note there states the bound and stops. Where the
 * node is one a side does not print, as with the bound an unbounded wildcard leaves implicit, the
 * caret rises to the deepest node that side does print, and a {@code note} says what the reader
 * cannot see there.
 *
 * <h2>The fixes</h2>
 *
 * <p>A {@code fix} line names the declaration it rewrites and the type it declares, and both
 * candidates are checked before they are printed: the candidate is passed to the predicate that
 * rejected the assignment, and it has to print as Java a reader could write, since a type the check
 * accepts may still print {@code capture of ?}. Where the declaration is in the compilation unit
 * being analyzed and writes its type out, the line comes with an Error Prone fix that makes the
 * edit.
 *
 * <p>The two candidates differ in what they presume. Writing an explicit bound on a wildcard the
 * source declaration already writes is a mechanical fix of that wildcard, so it is offered wherever
 * it works. Changing the required type is a claim about which side of a contract is wrong, which
 * this class cannot know, so it comes with two limits: it is offered only where the required type
 * is written at the diagnostic itself, as it is in a variable declaration, and it only ever adds
 * {@code @Nullable}. At a call the required type comes from a signature somewhere else, and
 * proposing an edit to it would be a guess at what that signature is for; taking a
 * {@code @Nullable} away would be a guess of the same kind about a contract the author stated on
 * purpose.
 *
 * <p>A fix also has to survive printing. The printer shows {@code @Nullable} and drops every other
 * type-use annotation, and it writes the dimensions of a nested array in the reverse of the order a
 * declaration gives them, so a type carrying another annotation, or an annotation on a dimension of
 * a nested array, is not offered.
 */
final class NullabilityMismatchMessage {

  private final GenericsChecks checks;
  private final Config config;
  private final Handler handler;
  private final VisitorState state;

  NullabilityMismatchMessage(
      GenericsChecks checks, Config config, Handler handler, VisitorState state) {
    this.checks = checks;
    this.config = config;
    this.handler = handler;
    this.state = state;
  }

  /** How many mismatches a message lists before it says only how many more there are. */
  private static final int MAX_LISTED_MISMATCHES = 5;

  /**
   * The text of a diagnostic and the fixes to attach to it.
   *
   * @param message the text
   * @param fixes each an alternative edit after which the check accepts the pseudo-assignment, in
   *     the order the text names them
   */
  record Report(String message, List<SuggestedFix> fixes) {}

  /**
   * Builds the diagnostic for an assignment, a return, or an argument the nullability check
   * rejected.
   *
   * <p>Where the check records no rejected position, the message is the two types and the subtype
   * relation between them, which is all that can be said about a difference nothing located.
   *
   * @param lhsType the required type
   * @param rhsType the type found
   * @param requiredTypeDeclaration the declaration of a variable whose type is {@code lhsType}
   *     where the diagnostic is reported at it, which admits a fix of the required type; {@code
   *     null} elsewhere
   * @param sourceExpression the expression whose type is {@code rhsType}, which admits a fix of the
   *     declaration it names; {@code null} where there is none
   */
  Report build(
      Type lhsType,
      Type rhsType,
      @Nullable VariableTree requiredTypeDeclaration,
      @Nullable ExpressionTree sourceExpression) {
    NullabilityMismatches recorded = new NullabilityMismatches(lhsType, rhsType);
    new CheckIdenticalNullabilityVisitor(state, checks, config, handler, recorded)
        .isSubtype(lhsType, rhsType);
    List<NullabilityMismatches.Mismatch> mismatches = recorded.mismatches();
    if (mismatches.isEmpty()) {
      return new Report(
          plainIncompatibleTypesMessage(lhsType, rhsType),
          List.of()); // diff-coverage: ignore -- every false result of the visitor comes from a
      // reject call, so a rejection records a position
    }
    List<NullabilityMismatches.Mismatch> listed =
        mismatches.size() > MAX_LISTED_MISMATCHES
            ? mismatches.subList(0, MAX_LISTED_MISMATCHES)
            : mismatches;
    boolean numbered = listed.size() > 1;
    StringBuilder message = new StringBuilder(summaryLine(mismatches));
    MarkedType found =
        markedTypeLines(
            "found",
            rhsType,
            carets(listed, NullabilityMismatches.Mismatch::sourceCaret),
            numbered);
    Type viewedSource = recorded.viewedSource();
    if (found.marksPlaced() == listed.size() || viewedSource == null) {
      message.append(found.lines());
    } else {
      // a node the check compared is at no position of the type the reader wrote, so the view of
      // it the check did compare is printed too and carries the carets
      message.append(markedTypeLines("found", rhsType, List.of(), numbered).lines());
      message.append(
          markedTypeLines(
                  "found as",
                  viewedSource,
                  carets(listed, NullabilityMismatches.Mismatch::viewCaret),
                  numbered)
              .lines());
    }
    message.append(
        markedTypeLines(
                "required",
                lhsType,
                carets(listed, NullabilityMismatches.Mismatch::targetCaret),
                numbered)
            .lines());
    for (int i = 0; i < listed.size(); i++) {
      message.append(mismatchDetail(listed.get(i), numbered ? i + 1 : 0));
    }
    // a numbered entry stands apart from the next, so the list stays readable, and what follows
    // the list stands apart from it in the same way
    String separator = numbered ? "\n" : "";
    if (mismatches.size() > listed.size()) {
      message.append(
          String.format(
              "%s\n  and %d more, not listed", separator, mismatches.size() - listed.size()));
    }
    List<SuggestedFix> fixes = new ArrayList<>();
    VariableTree sourceDeclaration = sourceDeclaration(sourceExpression, rhsType);
    if (sourceDeclaration != null) {
      Type rewrittenSourceType = sourceTypeFix(lhsType, rhsType, mismatches);
      if (rewrittenSourceType != null) {
        message.append(separator);
        appendFix(message, fixes, sourceDeclaration, rewrittenSourceType);
        separator = "";
      }
    }
    if (requiredTypeDeclaration != null) {
      Type rewrittenTargetType = targetTypeFix(lhsType, rhsType, mismatches);
      if (rewrittenTargetType != null) {
        message.append(separator);
        appendFix(message, fixes, requiredTypeDeclaration, rewrittenTargetType);
      }
    }
    return new Report(message.toString(), fixes);
  }

  /**
   * Appends the {@code fix:} line that declares {@code declaration} with {@code type}, and adds the
   * edit to {@code fixes} where the declaration is in the compilation unit being analyzed and
   * writes its type out.
   */
  private void appendFix(
      StringBuilder message, List<SuggestedFix> fixes, VariableTree declaration, Type type) {
    message.append(
        String.format(
            "\n  fix: declare %s as %s",
            declaration.getName(), GenericsChecks.prettyTypeForError(type, state)));
    Tree typeTree = declaration.getType();
    TreePath declarationPath = TreePath.getPath(state.getPath().getCompilationUnit(), declaration);
    if (typeTree == null
        || declarationPath == null
        || ASTHelpers.hasImplicitType(declaration, state)) {
      return; // diff-coverage: ignore -- javac versions differ in whether an inferred type gets a
      // synthesized tree
    }
    SuggestedFix.Builder fix = SuggestedFix.builder();
    String printed =
        GenericTypePrettyPrintingVisitor.printForFix(
            type, state.withPath(declarationPath), fix, hasNullableModifier(declaration));
    fixes.add(fix.replace(typeTree, printed).build());
  }

  /**
   * Whether {@code declaration} writes a nullable annotation among its modifiers, where a type-use
   * annotation applies to the base type and lies outside the type tree.
   */
  private boolean hasNullableModifier(VariableTree declaration) {
    for (AnnotationTree annotation : declaration.getModifiers().getAnnotations()) {
      Symbol annotationSymbol = ASTHelpers.getSymbol(annotation.getAnnotationType());
      if (annotationSymbol != null
          && Nullness.isNullableAnnotation(
              annotationSymbol.getQualifiedName().toString(), config)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the declaration of the variable {@code sourceExpression} reads, where it is in the
   * compilation unit being analyzed and declares the very type {@code sourceType}, so that a
   * rewrite of that type is a rewrite of the declaration; {@code null} otherwise.
   */
  private @Nullable VariableTree sourceDeclaration(
      @Nullable ExpressionTree sourceExpression, Type sourceType) {
    if (sourceExpression == null
        || !(ASTHelpers.getSymbol(ASTHelpers.stripParentheses(sourceExpression))
            instanceof Symbol.VarSymbol variable)) {
      return null;
    }
    // a member read through a parameterized receiver has a type the declaration does not state,
    // such as the List<String> a field declared List<T> has in a Box<String>
    if (!GenericsChecks.prettyTypeForError(variable.type, state)
        .equals(GenericsChecks.prettyTypeForError(sourceType, state))) {
      return null;
    }
    TreePath path = JavacTrees.instance(state.context).getPath(variable);
    return path != null
            && path.getCompilationUnit() == state.getPath().getCompilationUnit()
            && path.getLeaf() instanceof VariableTree declaration
        ? declaration
        : null;
  }

  /**
   * Builds the message for a difference the traversal did not locate: the two types, and the
   * supertype view that lines them up where their erasures differ.
   */
  private String plainIncompatibleTypesMessage(Type lhsType, Type rhsType) {
    String prettyRhsType = GenericsChecks.prettyTypeForError(rhsType, state);
    String prettyLhsType = GenericsChecks.prettyTypeForError(lhsType, state);
    String result =
        String.format(
            "incompatible types: %s cannot be converted to %s", prettyRhsType, prettyLhsType);
    if (!ASTHelpers.isSameType(lhsType, rhsType, state)
        && lhsType.getKind() == TypeKind.DECLARED
        && rhsType.getKind() == TypeKind.DECLARED
        && lhsType.asElement() instanceof Symbol.ClassSymbol classSymbol) {
      Type asSuper = TypeSubstitutionUtils.asSuper(state.getTypes(), rhsType, classSymbol, config);
      if (asSuper != null) {
        result +=
            String.format(
                " (%s is a subtype of %s)",
                prettyRhsType, GenericsChecks.prettyTypeForError(asSuper, state));
      }
    }
    return result;
  }

  /**
   * The line a reader sees first, and the only one some tools show, so it stands alone: it names
   * where the first difference sits, how many other positions differ, and the pair at the first.
   */
  private String summaryLine(List<NullabilityMismatches.Mismatch> mismatches) {
    NullabilityMismatches.Mismatch first = mismatches.get(0);
    int others = mismatches.size() - 1;
    String where =
        others == 0
            ? first.path().describe()
            : String.format(
                "%s and %d other position%s",
                first.path().describe(), others, others == 1 ? "" : "s");
    // a non-null type argument where a @Nullable one is required reads as a false subtype claim,
    // since every String is a @Nullable String; what fails is that a type argument has to match
    String exactly =
        first.relation() == NullabilityMismatches.Relation.SAME
                && checks.isNullableAnnotated(first.targetNode())
                && !checks.isNullableAnnotated(first.sourceNode())
            ? "a type argument must match exactly; "
            : "";
    return String.format(
        "incompatible nullability in %s: %sfound %s, required %s",
        where,
        exactly,
        GenericsChecks.prettyTypeForError(first.sourceNode(), state),
        required(first));
  }

  /**
   * Names what the check required of the source at {@code mismatch}: the target node, or for the
   * lower bound of a {@code ? super} wildcard, a supertype of it.
   */
  private String required(NullabilityMismatches.Mismatch mismatch) {
    String target = GenericsChecks.prettyTypeForError(mismatch.targetNode(), state);
    return mismatch.relation() == NullabilityMismatches.Relation.SUPERTYPE
        ? "a supertype of " + target
        : target;
  }

  /** Returns the caret {@code caret} gives each mismatch, in order. */
  private static List<@Nullable TypePath> carets(
      List<NullabilityMismatches.Mismatch> mismatches,
      Function<NullabilityMismatches.Mismatch, @Nullable TypePath> caret) {
    List<@Nullable TypePath> paths = new ArrayList<>();
    for (NullabilityMismatches.Mismatch mismatch : mismatches) {
      paths.add(caret.apply(mismatch));
    }
    return paths;
  }

  /**
   * Prints {@code type} on a labelled line, with a caret run under each position in {@code paths}
   * and, where {@code numbered}, the index of each under its caret.
   *
   * <p>Pointing at the position is what naming the type variable cannot do. One type may hold
   * several arguments for identically named variables — {@code Map<List<Long>, List<Float>>} has
   * two for {@code E} of {@code List} — so a name leaves the reader to work out which occurrence is
   * meant, and even a unique name makes them map it back onto the printed type themselves.
   */
  private MarkedType markedTypeLines(
      String label, Type type, List<@Nullable TypePath> paths, boolean numbered) {
    String printed = type.accept(new GenericTypePrettyPrintingVisitor(state, paths), null);
    StringBuilder stripped = new StringBuilder();
    StringBuilder carets = new StringBuilder();
    StringBuilder numbers = new StringBuilder();
    int marks = 0;
    int i = 0;
    while (i < printed.length()) {
      char c = printed.charAt(i);
      if (c != GenericTypePrettyPrintingVisitor.MARK_START || i + 1 >= printed.length()) {
        stripped.append(c);
        i++;
        continue;
      }
      // one node may carry several indices, where two mismatches share its caret
      List<String> indices = new ArrayList<>();
      i++;
      while (i < printed.length()
          && printed.charAt(i) != GenericTypePrettyPrintingVisitor.MARK_TEXT) {
        indices.add(Integer.toString(printed.charAt(i) - '0' + 1));
        i++;
      }
      i++;
      int start = stripped.length();
      while (i < printed.length()
          && printed.charAt(i) != GenericTypePrettyPrintingVisitor.MARK_END) {
        stripped.append(printed.charAt(i));
        i++;
      }
      i++;
      padTo(carets, start).append("^".repeat(Math.max(stripped.length() - start, 1)));
      padTo(numbers, start).append(String.join(",", indices));
      marks += indices.size();
    }
    String prefix = String.format("  %-9s ", label + ":");
    // the printer brackets what it was asked to, and this removes any marker regardless: nothing a
    // caller marks may put a control character in front of a reader
    String line =
        "\n"
            + prefix
            + stripped
                .toString()
                .replace(String.valueOf(GenericTypePrettyPrintingVisitor.MARK_START), "")
                .replace(String.valueOf(GenericTypePrettyPrintingVisitor.MARK_TEXT), "")
                .replace(String.valueOf(GenericTypePrettyPrintingVisitor.MARK_END), "");
    if (carets.length() > 0) {
      line += "\n" + " ".repeat(prefix.length()) + carets;
      if (numbered) {
        line += "\n" + " ".repeat(prefix.length()) + numbers;
      }
    }
    return new MarkedType(line, marks);
  }

  /**
   * A printed type and the number of requested positions the printer marked in it, counting a node
   * that carries several of them once for each.
   */
  private record MarkedType(String lines, int marksPlaced) {}

  /** Pads {@code line} with spaces up to {@code column}, so the next run starts there. */
  private static StringBuilder padTo(StringBuilder line, int column) {
    if (line.length() < column) {
      line.append(" ".repeat(column - line.length()));
    }
    return line;
  }

  /**
   * Returns the lines that describe one mismatch below the two types: for a numbered entry its
   * position and the pair the first line names only for the first entry, and for any mismatch the
   * note about the source wildcard where there is one.
   */
  private String mismatchDetail(NullabilityMismatches.Mismatch mismatch, int number) {
    String header = number == 0 ? "  " : "  " + number + ". ";
    String body = " ".repeat(header.length());
    StringBuilder detail = new StringBuilder();
    if (number != 0) {
      detail.append("\n\n").append(header).append("path: ").append(mismatch.path().describe());
      detail.append("\n").append(body).append("found:    ");
      detail.append(GenericsChecks.prettyTypeForError(mismatch.sourceNode(), state));
      detail.append("\n").append(body).append("required: ").append(required(mismatch));
    }
    String note = boundOriginNote(mismatch);
    if (note != null) {
      detail.append("\n").append(body).append("note: ").append(note);
    }
    return detail.toString();
  }

  /**
   * Says where the source wildcard takes its upper bound from, or returns {@code null} where the
   * printed node is not a wildcard, its bound is written out, or it is a captured wildcard whose
   * type variable cannot be named. Where an uncaptured wildcard's type parameter cannot be named,
   * the note states the bound alone.
   *
   * <p>A wildcard with no explicit upper bound and a captured one both take a bound the printed
   * type does not state, and a reader who is not told where it comes from reads the two bounds as a
   * contradiction. For a captured wildcard the note also says when the owner is unannotated, which
   * is what makes the bound {@code @Nullable} and the one thing about the situation a reader can go
   * and change. Unannotated is NullAway's own sense of the word: a class outside
   * {@code @NullMarked} code, and also one the configuration names as unannotated.
   */
  private @Nullable String boundOriginNote(NullabilityMismatches.Mismatch mismatch) {
    // the node the caret sits on, not the one that differs: the note answers a reader who is
    // looking at a wildcard and reading that the source has a bound the wildcard does not print
    Type.WildcardType wildcard = GenericsUtils.asWildcard(mismatch.sourcePrintedNode());
    if (wildcard == null) {
      return null;
    }
    if (mismatch.sourcePrintedNode() instanceof Type.CapturedType) {
      // a captured wildcard was captured where it was written, so its bound link still names the
      // type variable of the type the user wrote, e.g. T of Flow for a (Flow<?>) cast
      Type.TypeVar capturedTypeVariable = wildcard.bound;
      Symbol.ClassSymbol owner = typeVariableOwner(capturedTypeVariable);
      if (capturedTypeVariable == null || owner == null) {
        return null;
      }
      String note =
          String.format(
              "the source wildcard is the type argument for type parameter %s of %s",
              capturedTypeVariable.tsym.getSimpleName(), owner.getSimpleName());
      return GenericsUtils.fromUnannotatedMethodOrClass(
              capturedTypeVariable.tsym, config, handler, state)
          ? note + String.format(", and %s is unannotated", owner.getSimpleName())
          : note;
    }
    if (wildcard.kind == BoundKind.EXTENDS) {
      return null; // diff-coverage: ignore -- a ? extends wildcard prints its bound, so the caret
      // goes below it
    }
    // the step that reached the wildcard in the type the reader wrote names the type parameter it
    // instantiates there; javac's own link, WildcardType.bound, names a type parameter of whatever
    // supertype the wildcard was last substituted into
    TypePath caret = mismatch.sourceCaret();
    TypePath.Step reachingStep =
        caret == null || caret.isRoot() ? null : caret.steps().get(caret.steps().size() - 1);
    Type.TypeVar formalTypeVariable =
        reachingStep != null && reachingStep.kind() == TypePath.Step.Kind.TYPE_ARGUMENT
            ? reachingStep.formal()
            : null;
    Symbol.ClassSymbol owner = typeVariableOwner(formalTypeVariable);
    String source =
        wildcard.kind == BoundKind.UNBOUND
            ? "?"
            : "? super " + GenericsChecks.prettyTypeForError(wildcard.type, state);
    if (formalTypeVariable == null || owner == null) {
      // no type parameter this message prints stands behind the bound: the wildcard sits at no
      // position in the type the reader wrote, as where the supertype view built it from a subclass
      // declaration, or the variable it instantiates has no named owner. The note says what the
      // bound is and leaves where it came from alone
      // "the source wildcard" rather than the symbol: two printed types carry a ? here, and the
      // note is about the one the comparison used rather than about a character to go and find
      return String.format(
          "the source wildcard has an implicit %s upper bound",
          GenericsChecks.prettyTypeForError(
              GenericsUtils.wildcardUpperBound(wildcard, state, config, handler), state));
    }
    return String.format(
        "the source %s has no explicit upper bound, so its upper bound is inherited from type parameter %s of %s",
        source, formalTypeVariable.tsym.getSimpleName(), owner.getSimpleName());
  }

  /**
   * Returns {@code sourceType} with an explicit upper bound written out for each unbounded wildcard
   * whose implicit {@code @Nullable} bound the target rejects, where that type is one a reader can
   * declare and the check accepts it, and {@code null} otherwise.
   */
  private @Nullable Type sourceTypeFix(
      Type targetType, Type sourceType, List<NullabilityMismatches.Mismatch> mismatches) {
    Type rewrittenSourceType = rewriteWithExplicitBounds(sourceType, mismatches);
    if (rewrittenSourceType == null || !isDeclarable(rewrittenSourceType)) {
      return null;
    }
    // the check's own comparison rather than a prediction of it: a rewrite that leaves a mismatch
    // writing a bound out does not address, such as a nullness difference on a concrete type
    // argument, would only produce the same diagnostic again
    return checks.subtypeParameterNullability(targetType, rewrittenSourceType, state)
        ? rewrittenSourceType
        : null;
  }

  /**
   * Returns {@code targetType} with {@code @Nullable} added at every rejected position, where the
   * source has {@code @Nullable} at each of them, the result is a type a reader can declare, and
   * the check accepts the source against it; {@code null} otherwise, including where a position
   * would need a {@code @Nullable} removed. Whether it is offered at all is the caller's decision,
   * which the class comment states.
   */
  private @Nullable Type targetTypeFix(
      Type targetType, Type sourceType, List<NullabilityMismatches.Mismatch> mismatches) {
    Type rewrittenTargetType = targetType;
    for (NullabilityMismatches.Mismatch mismatch : mismatches) {
      Type rewritten = withNullableAddedFrom(mismatch.sourceNode(), mismatch.targetNode());
      TypePath targetNodePath = mismatch.targetNodePath();
      if (rewritten == null || targetNodePath == null) {
        return null;
      }
      rewrittenTargetType = replaceAt(rewrittenTargetType, targetNodePath.steps(), 0, rewritten);
      if (rewrittenTargetType == null) {
        return null; // diff-coverage: ignore -- a path from the recorder always addresses a node
        // the type has
      }
    }
    return isDeclarable(rewrittenTargetType)
            && checks.subtypeParameterNullability(rewrittenTargetType, sourceType, state)
        ? rewrittenTargetType
        : null;
  }

  /**
   * Returns {@code target} with {@code @Nullable} added where {@code source} carries it, or {@code
   * null} where {@code source} is not {@code @Nullable}, {@code target} already is, or the
   * annotation to copy cannot be found.
   *
   * <p>The fix only ever widens what the target accepts. Taking a {@code @Nullable} away, which is
   * what the other direction would need, changes a contract the author stated on purpose, and no
   * reading of the assignment says which of the two sides meant what.
   */
  private @Nullable Type withNullableAddedFrom(Type source, Type target) {
    if (!checks.isNullableAnnotated(source) || checks.isNullableAnnotated(target)) {
      return null;
    }
    for (Attribute.TypeCompound annotation : source.getAnnotationMirrors()) {
      if (Nullness.isNullableAnnotation(annotation.type.toString(), config)) {
        return TypeSubstitutionUtils.typeWithAnnot(target, annotation.type);
      }
    }
    return null; // diff-coverage: ignore -- isNullableAnnotated is true only where a nullable
    // annotation is present
  }

  /**
   * Returns {@code type} with the node that {@code steps} reach, starting at {@code from}, replaced
   * by {@code replacement}, or {@code null} where {@code type} prints no node there, as for the
   * bound an unbounded wildcard leaves implicit.
   *
   * <p>A path names the node because one {@code Type} instance can fill several positions, as one
   * {@code String} does in {@code Map<String, String>}, and only one of them is to change.
   */
  private static @Nullable Type replaceAt(
      Type type, List<TypePath.Step> steps, int from, Type replacement) {
    if (from == steps.size()) {
      return replacement;
    }
    TypePath.Step step = steps.get(from);
    switch (step.kind()) {
      case TYPE_ARGUMENT -> {
        if (!(type instanceof Type.ClassType classType)
            || step.index() >= classType.getTypeArguments().size()) {
          return null; // diff-coverage: ignore -- a path from the recorder always addresses a node
          // the type has
        }
        List<Type> typeArguments = new ArrayList<>(classType.getTypeArguments());
        Type typeArgument =
            replaceAt(typeArguments.get(step.index()), steps, from + 1, replacement);
        if (typeArgument == null) {
          return null; // diff-coverage: ignore -- a path from the recorder always addresses a node
          // the type has
        }
        typeArguments.set(step.index(), typeArgument);
        return TYPE_METADATA_BUILDER.createClassType(
            classType, classType.getEnclosingType(), typeArguments);
      }
      case ENCLOSING_TYPE -> {
        if (!(type instanceof Type.ClassType classType)) {
          return null; // diff-coverage: ignore -- a path from the recorder always addresses a node
          // the type has
        }
        Type enclosingType = replaceAt(classType.getEnclosingType(), steps, from + 1, replacement);
        return enclosingType == null
            ? null // diff-coverage: ignore -- a path from the recorder always addresses a node the
            // type has
            : TYPE_METADATA_BUILDER.createClassType(
                classType, enclosingType, classType.getTypeArguments());
      }
      case WILDCARD_BOUND -> {
        if (!(type instanceof Type.WildcardType wildcard) || wildcard.kind != BoundKind.EXTENDS) {
          return null; // diff-coverage: ignore -- a path from the recorder always addresses a node
          // the type has
        }
        Type bound = replaceAt(wildcard.type, steps, from + 1, replacement);
        return bound == null ? null : TYPE_METADATA_BUILDER.createWildcardType(wildcard, bound);
      }
      case WILDCARD_LOWER_BOUND -> {
        // a lower bound accepts less once it is @Nullable, so no fix adds the annotation there
        return null;
      }
      case ARRAY_ELEMENT -> {
        if (!(type instanceof Type.ArrayType arrayType)) {
          return null; // diff-coverage: ignore -- a path from the recorder always addresses a node
          // the type has
        }
        Type elementType = replaceAt(arrayType.elemtype, steps, from + 1, replacement);
        return elementType == null
            ? null // diff-coverage: ignore -- a path from the recorder always addresses a node the
            // type has
            : TYPE_METADATA_BUILDER.createArrayType(arrayType, elementType);
      }
    }
    throw new AssertionError(
        step.kind()); // diff-coverage: ignore -- the switch covers every step kind
  }

  /**
   * Returns {@code sourceType} with an explicit upper bound written out for every wildcard that
   * gains one, or {@code null} if there is no such wildcard.
   *
   * <p>A wildcard gains an explicit bound when it is unbounded, its implicit bound is
   * {@code @Nullable}, and the target requires a non-null class type there: writing that bound out
   * is the whole of the fix this class knows how to propose. Whether the result is worth offering
   * is decided by the caller, against the rewritten type rather than against this list.
   *
   * <p>Only a class type is written out as a bound. A type variable would have to be in scope where
   * the source type is declared, which nothing here checks; no known input reaches that case, since
   * a target bound naming a method's type variable fails inference before this class runs.
   */
  private @Nullable Type rewriteWithExplicitBounds(
      Type sourceType, List<NullabilityMismatches.Mismatch> mismatches) {
    // identity, not isSameType: a wildcard is rewritten where it occurs, not wherever an equal one
    // does, and the same wildcard may be reported at several positions
    IdentityHashMap<Type.WildcardType, Type> boundForWildcard = new IdentityHashMap<>();
    for (NullabilityMismatches.Mismatch mismatch : mismatches) {
      Type.WildcardType sourceWildcard = mismatch.sourceWildcard();
      Type targetUpperBound = mismatch.targetUpperBound();
      if (sourceWildcard == null
          || targetUpperBound == null
          || sourceWildcard.kind != BoundKind.UNBOUND) {
        // only an unbounded wildcard gains an upper bound by being written out
        continue;
      }
      if (targetUpperBound.getKind() != TypeKind.DECLARED
          || Nullness.hasNullableAnnotation(
              targetUpperBound.getAnnotationMirrors().stream(), config)
          || !Nullness.hasNullableAnnotation(
              mismatch.sourceNode().getAnnotationMirrors().stream(), config)) {
        // the bound to write out is not a class type, or writing it out would not make the source
        // wildcard non-null, which is the only fix this method knows
        continue; // diff-coverage: ignore -- the check rejects an upper bound only where the source
        // is @Nullable and the target is not
      }
      boundForWildcard.put(sourceWildcard, targetUpperBound);
    }
    if (boundForWildcard.isEmpty()) {
      return null;
    }
    return sourceType.accept(
        new Type.StructuralTypeMapping<@Nullable Void>() {
          @Override
          public Type visitWildcardType(Type.WildcardType t, @Nullable Void unused) {
            Type requiredBound = boundForWildcard.get(t);
            return requiredBound == null
                ? super.visitWildcardType(t, null)
                : new Type.WildcardType(
                    requiredBound, BoundKind.EXTENDS, Symtab.instance(state.context).boundClass);
          }
        },
        null);
  }

  /**
   * Returns whether {@code type} prints as a type a reader can declare, so that a fix pasting it
   * says what the reader gets.
   *
   * <p>This mirrors {@link GenericTypePrettyPrintingVisitor}, which prints {@code @Nullable} and
   * drops every other type-use annotation, and has four arms whose output no declaration may hold
   * or that denotes nothing: a captured wildcard prints as {@code capture of ?}, an intersection as
   * {@code A & B}, an anonymous class under its binary name, and a type that did not resolve under
   * a bare name. Dispatch is by visitor rather than by {@code instanceof} because {@link
   * Type.CapturedType} extends {@link Type.TypeVar} and {@link Type.IntersectionClassType} extends
   * {@link Type.ClassType}, so a chain of {@code instanceof} arms would classify a capture as a
   * type variable.
   */
  private boolean isDeclarable(Type type) {
    return type.accept(declarabilityVisitor, null);
  }

  private final Types.DefaultTypeVisitor<Boolean, @Nullable Void> declarabilityVisitor =
      new Types.DefaultTypeVisitor<>() {
        @Override
        public Boolean visitClassType(Type.ClassType t, @Nullable Void unused) {
          if (t.isIntersection() || t.tsym.isAnonymous() || !printsEveryAnnotation(t)) {
            return false;
          }
          for (Type typeArgument : t.getTypeArguments()) {
            if (!typeArgument.accept(this, null)) {
              return false;
            }
          }
          Type enclosingType = t.getEnclosingType();
          return enclosingType.hasTag(TypeTag.NONE) || enclosingType.accept(this, null);
        }

        @Override
        public Boolean visitWildcardType(Type.WildcardType t, @Nullable Void unused) {
          return printsEveryAnnotation(t)
              && (t.kind == BoundKind.UNBOUND || t.type.accept(this, null));
        }

        @Override
        public Boolean visitArrayType(Type.ArrayType t, @Nullable Void unused) {
          // the printer writes the dimensions of a nested array innermost first, the reverse of the
          // order a declaration gives them, so an annotated dimension would land on another one
          if (t.elemtype instanceof Type.ArrayType && hasAnnotatedDimension(t)) {
            return false;
          }
          return printsEveryAnnotation(t) && t.elemtype.accept(this, null);
        }

        @Override
        public Boolean visitCapturedType(Type.CapturedType t, @Nullable Void unused) {
          return false; // diff-coverage: ignore -- a type holding a capture is no variable's
          // declared type, so sourceDeclaration rejects it first
        }

        @Override
        public Boolean visitErrorType(Type.ErrorType t, @Nullable Void unused) {
          return false; // diff-coverage: ignore -- an unresolved type stops compilation before
          // NullAway runs
        }

        @Override
        public Boolean visitType(Type t, @Nullable Void unused) {
          return printsEveryAnnotation(t);
        }
      };

  /** Returns whether {@code array} or an array it nests directly carries a type-use annotation. */
  private static boolean hasAnnotatedDimension(Type.ArrayType array) {
    for (Type t = array; t instanceof Type.ArrayType nested; t = nested.elemtype) {
      if (!nested.getAnnotationMirrors().isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /** Returns whether the only type-use annotation on {@code type} itself is a nullable one. */
  private boolean printsEveryAnnotation(Type type) {
    for (Attribute.TypeCompound annotation : type.getAnnotationMirrors()) {
      if (!Nullness.isNullableAnnotation(annotation.type.toString(), config)) {
        return false;
      }
    }
    return true;
  }

  /** Returns the named class declaring {@code typeVariable}, or {@code null} if there is none. */
  private static Symbol.@Nullable ClassSymbol typeVariableOwner(
      Type.@Nullable TypeVar typeVariable) {
    if (typeVariable == null
        || !(typeVariable.tsym.owner instanceof Symbol.ClassSymbol owner)
        || owner.getSimpleName().isEmpty()) {
      return null;
    }
    return owner;
  }
}
