package com.uber.nullaway.generics;

import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
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
import javax.lang.model.type.TypeKind;
import org.jspecify.annotations.Nullable;

/**
 * Renders the diagnostic for a pseudo-assignment whose source type does not have the nullness its
 * target type requires.
 *
 * <p>The message is built from the positions {@link NullabilityMismatches} found, and this class
 * decides what a reader is told about them. The rules below are the contract every such message
 * follows, and a new reporter that reaches this class inherits them; a reporter that builds its own
 * message text is not bound by them and reads worse for it.
 *
 * <h2>The vocabulary</h2>
 *
 * <p>Four words carry the message, and they mean the same thing wherever they appear. {@code found}
 * is what the source has, {@code required} is what the target demands, {@code path} is where in the
 * type that pair sits, and {@code note} is a fact about the source that its printed form does not
 * show. A reader who has learned one message has learned every other, and a tool matching on the
 * text has four stable keys rather than a sentence to parse.
 *
 * <h2>The first line</h2>
 *
 * <p>It opens {@code incompatible nullability:} and then states the one difference, or how many
 * there are where naming one would misrepresent the rest. It states the two nodes rather than
 * calling the source unassignable to the target, because the difference runs both ways: a type
 * argument the source states outright has to match, so {@code String} fails against
 * {@code @Nullable String} as surely as the reverse. That direction is the one a reader can misread
 * as a false subtype claim, since every {@code String} is a {@code @Nullable String}, so there the
 * line says first that a type argument has to match exactly. It never carries the two whole types,
 * which are printed below where a caret can point into them.
 *
 * <h2>The caret</h2>
 *
 * <p>A caret marks the smallest node whose nullness differs, {@code @Nullable String} rather than
 * the {@code Collection<@Nullable String>} around it, and it marks that node in both printed types.
 * The comparison views the source as the target's supertype, so which node of the type the reader
 * wrote the caret belongs under is a question with two answers: where the view is that same class,
 * the position is the same position; where it is another class, only a node the substitution
 * carried over rather than rebuilt can be pointed at. Where neither answers, the view is printed as
 * {@code found as} and carries the carets, so a caret marks the type the check compared rather than
 * a node in one where the difference cannot be seen.
 *
 * <p>A note explains itself in the entities the message already shows, and it appears where the
 * printed source does not carry the nullness the comparison used: under a wildcard that inherits a
 * bound, and under a type variable whose own declaration supplies one. Where the source wildcard
 * stands at a position of the type the reader wrote, the note names the type parameter it takes its
 * bound from. Where the source was viewed as a supertype, that parameter belongs to a declaration
 * this message never prints, and naming it would set a second frame of reference against the {@code
 * path} line: {@code Base type argument B} beside {@code type parameter S of Sub} reads as a
 * contradiction rather than as two answers. The note there states the bound and stops. Where the
 * node is one a side does not print, as with the bound an unbounded wildcard leaves implicit, the
 * caret rises to the deepest node that side does print, and a {@code note} says what the reader
 * cannot see there. Where a position cannot be told from another, because one type instance sits at
 * two of them, that side gets no caret at all: a caret under a guess is worse than none, and the
 * {@code path} line still names the position.
 *
 * <h2>The repairs</h2>
 *
 * <p>Two repairs may be offered, and both are checked before they are printed: the candidate is
 * passed to the predicate that rejected the assignment, and it has to print as Java a reader could
 * write, since a type the check accepts may still print {@code capture of ?}.
 *
 * <p>They differ in what they presume. Writing an explicit bound on a wildcard the reader already
 * wrote is a mechanical repair of that wildcard, so it is offered wherever it works. Changing the
 * required type is a claim about which side of a contract is wrong, which this class cannot know,
 * so it comes with two limits: it is offered only where the required type is written at the
 * diagnostic itself, as it is in a variable declaration, and it only ever adds {@code @Nullable}.
 * At a call the required type comes from a signature somewhere else, and proposing an edit to it
 * would be a guess at what that signature is for; taking a {@code @Nullable} away would be a guess
 * of the same kind about a contract the author stated on purpose.
 *
 * <p>A repair also has to survive printing. The printer shows {@code @Nullable} and drops every
 * other type-use annotation, which costs a message that only describes a type nothing, and costs a
 * reader who pastes one whatever the printer left out, so a type carrying such an annotation is not
 * offered.
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
   * Builds the diagnostic for an assignment, a return, or an argument the nullability check
   * rejected.
   *
   * <p>Where the traversal finds no nullness difference it can name, the message is the two types
   * and the subtype relation between them, which is all that can be said about a difference nothing
   * located.
   *
   * @param requiredTypeIsWrittenHere whether the required type is written at the diagnostic, which
   *     is what admits a suggestion about it
   */
  String build(Type lhsType, Type rhsType, boolean requiredTypeIsWrittenHere) {
    List<NullabilityMismatches.Mismatch> mismatches =
        new NullabilityMismatches(checks, config, handler, state).collect(lhsType, rhsType);
    if (mismatches.isEmpty()) {
      return plainIncompatibleTypesMessage(lhsType, rhsType);
    }
    List<NullabilityMismatches.Mismatch> listed =
        mismatches.size() > MAX_LISTED_MISMATCHES
            ? mismatches.subList(0, MAX_LISTED_MISMATCHES)
            : mismatches;
    boolean numbered = listed.size() > 1;
    StringBuilder message = new StringBuilder(summaryLine(mismatches));
    List<@Nullable TypePath> viewCarets = sourceCarets(listed);
    Type comparedSourceType = sourceViewedAsTarget(lhsType, rhsType);
    // a position of the view is a position of the written type where the two are one class; where
    // they are not, only a node the view shares with it can be marked, and the instance says which
    List<@Nullable TypePath> writtenCarets =
        comparedSourceType == null ? viewCarets : instanceCarets(listed, rhsType);
    MarkedType found = markedTypeLines("found", rhsType, writtenCarets, numbered);
    if (found.marksPlaced() == listed.size()) {
      message.append(found.lines());
    } else {
      message.append(markedTypeLines("found", rhsType, noCarets(listed.size()), numbered).lines());
      if (comparedSourceType != null) {
        // what the comparison reached is at no position of the type the reader wrote, so the type
        // it did compare is printed too and carries the carets
        message.append(
            markedTypeLines("found as", comparedSourceType, viewCarets, numbered).lines());
      }
    }
    message.append(markedTypeLines("required", lhsType, targetCarets(listed), numbered).lines());
    for (int i = 0; i < listed.size(); i++) {
      message.append(mismatchDetail(listed.get(i), numbered ? i + 1 : 0, rhsType));
    }
    // a numbered entry stands apart from the next, so the list stays readable, and what follows
    // the list stands apart from it in the same way
    String separator = numbered ? "\n" : "";
    if (mismatches.size() > listed.size()) {
      message.append(
          String.format(
              "%s\n  and %d more, not listed", separator, mismatches.size() - listed.size()));
    }
    Type rewrittenSourceType = rewriteWithExplicitBounds(rhsType, mismatches);
    String suggestion =
        rewrittenSourceType == null ? null : suggestedSourceType(lhsType, rewrittenSourceType);
    String targetSuggestion =
        requiredTypeIsWrittenHere ? suggestedTargetType(lhsType, rhsType, mismatches) : null;
    if (suggestion != null) {
      message.append(String.format("%s\n  did you mean %s?", separator, suggestion));
      separator = "";
    }
    if (targetSuggestion != null) {
      message.append(
          String.format(
              "%s\n  consider changing the required type to:\n    %s",
              separator, targetSuggestion));
    }
    return message.toString();
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
   * The line a reader sees first, and the only one some tools show: the difference itself where
   * there is one, and how many there are where naming one would misrepresent the rest.
   */
  private String summaryLine(List<NullabilityMismatches.Mismatch> mismatches) {
    if (mismatches.size() == 1) {
      NullabilityMismatches.Mismatch only = mismatches.get(0);
      // a nullable source where a non-null one is required is a subtype claim and reads as one.
      // The other direction is not: every String is a @Nullable String, and what fails is that a
      // type argument has to match rather than be a subtype, which the pair alone does not say
      String exactly =
          checks.isNullableAnnotated(only.targetNode())
                  && !checks.isNullableAnnotated(only.sourceNode())
              ? "a type argument must match exactly; "
              : "";
      return String.format(
          "incompatible nullability: %sfound %s, required %s",
          exactly,
          GenericsChecks.prettyTypeForError(only.sourceNode(), state),
          GenericsChecks.prettyTypeForError(only.targetNode(), state));
    }
    return String.format(
        "incompatible nullability: %d mismatches between source and target types",
        mismatches.size());
  }

  /**
   * Where each mismatch is drawn in the target, which the traversal walked as the reader wrote it.
   */
  private static List<@Nullable TypePath> targetCarets(
      List<NullabilityMismatches.Mismatch> mismatches) {
    List<@Nullable TypePath> paths = new ArrayList<>();
    for (NullabilityMismatches.Mismatch mismatch : mismatches) {
      paths.add(mismatch.targetCaret());
    }
    return paths;
  }

  /**
   * Returns {@code sourceType} viewed as the class {@code targetType} names, or {@code null} where
   * that view is the type the reader wrote or cannot be taken.
   *
   * <p>The traversal compares the source in this view at every level, so a node it reports may sit
   * at no position in the type the reader wrote: viewing a {@code Sub<?>} as a {@code Base} keeps
   * the wildcard and changes the type printed around it. Printing the view gives the caret
   * something to point at that the comparison actually used.
   */
  private @Nullable Type sourceViewedAsTarget(Type targetType, Type sourceType) {
    if (!(targetType.tsym instanceof Symbol.ClassSymbol targetSymbol)
        || targetSymbol.equals(sourceType.tsym)) {
      // the same class, so the comparison ran on the type the reader wrote and its positions are
      // that type's own. Whether the view would print differently decides nothing: two classes of
      // one simple name print alike and remain two classes
      return null;
    }
    return TypeSubstitutionUtils.asSuper(state.getTypes(), sourceType, targetSymbol, config);
  }

  /**
   * Where each mismatch is drawn in the source type as the reader wrote it, with {@code null} for
   * one that type does not show.
   *
   * <p>By instance, since the view the comparison used is a type of another class and a position in
   * it names nothing here: what the two share is the node itself, wherever a substitution carried
   * it over rather than building one of its own.
   */
  private static List<@Nullable TypePath> instanceCarets(
      List<NullabilityMismatches.Mismatch> mismatches, Type sourceType) {
    List<@Nullable TypePath> paths = new ArrayList<>();
    for (NullabilityMismatches.Mismatch mismatch : mismatches) {
      paths.add(NullabilityMismatches.pathOfInstance(sourceType, mismatch.sourcePrintedNode()));
    }
    return paths;
  }

  /** A caret for no position at all, one per mismatch, so the two sides stay numbered alike. */
  private static List<@Nullable TypePath> noCarets(int mismatches) {
    List<@Nullable TypePath> paths = new ArrayList<>();
    for (int i = 0; i < mismatches; i++) {
      paths.add(null);
    }
    return paths;
  }

  /**
   * Where each mismatch sits in the source, as the traversal viewed it.
   *
   * <p>The same positions serve the type the reader wrote and the view of it the comparison used,
   * since the two agree wherever the view changed nothing; where they do not agree, the position
   * reaches nothing in the written type and the caller prints the view instead.
   */
  private static List<@Nullable TypePath> sourceCarets(
      List<NullabilityMismatches.Mismatch> mismatches) {
    List<@Nullable TypePath> paths = new ArrayList<>();
    for (NullabilityMismatches.Mismatch mismatch : mismatches) {
      paths.add(mismatch.sourceCaret());
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
      int index = printed.charAt(i + 1) - '0';
      int start = stripped.length();
      i += 2;
      while (i < printed.length()
          && printed.charAt(i) != GenericTypePrettyPrintingVisitor.MARK_END) {
        stripped.append(printed.charAt(i));
        i++;
      }
      i++;
      padTo(carets, start).append("^".repeat(Math.max(stripped.length() - start, 1)));
      padTo(numbers, start).append(index + 1);
      marks++;
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
   * A printed type and the number of positions the printer could mark in it.
   *
   * <p>The count is what tells a caller whether the type it printed is the one to draw on: a
   * position the traversal reached in its own view of the source reaches nothing in the type the
   * reader wrote once those two are different types.
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
   * Names where one mismatch sits and, for a numbered entry, what the two sides have there.
   *
   * <p>The two shapes carry the same fields in the same order, because a reader who has learned one
   * message has learned the other: a single mismatch states its position under the two types, and
   * where there are several, each entry states its own position and the pair the summary could not
   * name.
   */
  private String mismatchDetail(
      NullabilityMismatches.Mismatch mismatch, int number, Type sourceType) {
    // the root stands as a guard rather than as a case with traffic: a nullness difference at the
    // root of the two types is reported by the check for assigning a @Nullable value, before a
    // pseudo-assignment of type arguments is looked at
    String position = mismatch.path().isRoot() ? "the type itself" : mismatch.path().describe();
    String header = number == 0 ? "  " : "  " + number + ". ";
    String body = " ".repeat(header.length());
    StringBuilder detail = new StringBuilder();
    if (number != 0) {
      detail.append("\n");
    }
    detail
        .append("\n")
        .append(header)
        .append(wrapped("path: " + position, header.length(), body + "      "));
    if (number != 0) {
      detail.append("\n").append(body).append("found:    ");
      detail.append(GenericsChecks.prettyTypeForError(mismatch.sourceNode(), state));
      detail.append("\n").append(body).append("required: ");
      detail.append(GenericsChecks.prettyTypeForError(mismatch.targetNode(), state));
    }
    String note = boundOriginNote(mismatch, sourceType);
    if (note != null) {
      detail
          .append("\n")
          .append(body)
          .append(wrapped("note: " + note, body.length(), body + "      "));
    }
    return detail.toString();
  }

  /** The column a message line may not run past, which a deeply nested type reaches easily. */
  private static final int MAX_LINE_LENGTH = 100;

  /**
   * Returns {@code text} broken across lines that end before {@link #MAX_LINE_LENGTH}, with every
   * line after the first opening at {@code continuation}.
   *
   * <p>A type path breaks before one of its {@code ->} separators, so that a line starts with the
   * step it describes; any other text breaks at a space.
   */
  private static String wrapped(String text, int firstLineIndent, String continuation) {
    String[] segments = text.contains(" -> ") ? text.split(" (?=-> )") : text.split(" ");
    StringBuilder wrappedText = new StringBuilder(segments[0]);
    int lineLength = firstLineIndent + segments[0].length();
    for (int i = 1; i < segments.length; i++) {
      String segment = segments[i];
      if (lineLength + 1 + segment.length() > MAX_LINE_LENGTH) {
        wrappedText.append("\n").append(continuation).append(segment);
        lineLength = continuation.length() + segment.length();
      } else {
        wrappedText.append(" ").append(segment);
        lineLength += 1 + segment.length();
      }
    }
    return wrappedText.toString();
  }

  /**
   * Says where the source takes a nullness its printed form does not show: the bound a wildcard
   * inherits, or the bound a type variable was declared with. Returns {@code null} where the
   * printed type shows it already.
   *
   * <p>A wildcard with no explicit upper bound and a captured one both take a bound the printed
   * type does not state, and a reader who is not told where it comes from reads the two bounds as a
   * contradiction. For a captured wildcard the note also says when the owner is unannotated, which
   * is what makes the bound {@code @Nullable} and the one thing about the situation a reader can go
   * and change. Unannotated is NullAway's own sense of the word: a class outside
   * {@code @NullMarked} code, and also one the configuration names as unannotated.
   */
  private @Nullable String boundOriginNote(
      NullabilityMismatches.Mismatch mismatch, Type sourceType) {
    // the node the caret sits on, not the one that differs: the note answers a reader who is
    // looking at a wildcard and reading that the source has a bound the wildcard does not print
    Type.WildcardType wildcard = GenericsUtils.asWildcard(mismatch.sourcePrintedNode());
    if (wildcard == null) {
      // a captured wildcard is a Type.TypeVar as well, so it is ruled out above rather than here
      // a type variable that carries its own @Nullable is printed with it, and needs no note
      return mismatch.sourcePrintedNode() instanceof Type.TypeVar sourceTypeVariable
              && !checks.isNullableAnnotated(mismatch.sourcePrintedNode())
          ? String.format(
              "the source %s has no nullness of its own, so it takes the nullness of its declared"
                  + " upper bound",
              sourceTypeVariable.tsym.getSimpleName())
          : null;
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
      return null;
    }
    Type.TypeVar formalTypeVariable =
        typeVariableAtPositionOf(mismatch.sourcePrintedNode(), sourceType);
    Symbol.ClassSymbol owner = typeVariableOwner(formalTypeVariable);
    String source =
        wildcard.kind == BoundKind.UNBOUND
            ? "?"
            : "? super " + GenericsChecks.prettyTypeForError(wildcard.type, state);
    if (formalTypeVariable == null || owner == null) {
      // no type parameter this message prints stands behind the bound: the wildcard sits at no
      // position in the type the reader wrote, as happens once that source is viewed as a
      // supertype, or the variable it instantiates has no named owner. The note says what the
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
   * Returns the printed form of {@code rewrittenSourceType} where it is worth offering as a
   * rewrite, or {@code null} where it is not.
   */
  private @Nullable String suggestedSourceType(Type targetType, Type rewrittenSourceType) {
    if (!printsEveryAnnotation(rewrittenSourceType)) {
      // the printer shows @Nullable and drops every other type-use annotation, which is harmless
      // where the type is only described and wrong where it is offered to be pasted
      return null;
    }
    if (!isDenotable(rewrittenSourceType)) {
      // the rewrite would print a type the reader cannot write, such as one holding a captured
      // wildcard, and the clause invites them to paste it
      return null;
    }
    // javac rejects a `? super S` target holding a `?` before NullAway runs, which is the one
    // position where the comparison below would accept without analysing
    if (!checks.subtypeParameterNullability(targetType, rewrittenSourceType, state)) {
      // the rewrite does not repair every incompatibility, so suggesting it would only produce the
      // same diagnostic again. This is the check's own comparison rather than a prediction of it:
      // a mismatch it rejects for a reason writing a bound out does not address, such as a nullness
      // difference on a concrete type argument, is one no rewritten wildcard reaches.
      return null;
    }
    return GenericsChecks.prettyTypeForError(rewrittenSourceType, state);
  }

  /**
   * Returns the printed form of the target type with the nullness the source has at every position
   * that differs, or {@code null} where that type is not worth offering. Whether it is offered at
   * all is the caller's decision, which the class comment states.
   */
  private @Nullable String suggestedTargetType(
      Type targetType, Type sourceType, List<NullabilityMismatches.Mismatch> mismatches) {
    IdentityHashMap<Type, Type> replacements = new IdentityHashMap<>();
    for (NullabilityMismatches.Mismatch mismatch : mismatches) {
      Type rewritten = withNullableAddedFrom(mismatch.sourceNode(), mismatch.targetNode());
      if (rewritten == null) {
        return null;
      }
      replacements.put(mismatch.targetNode(), rewritten);
    }
    if (replacements.isEmpty()) {
      return null;
    }
    Type rewrittenTargetType = replaceNodes(targetType, replacements);
    if (!printsEveryAnnotation(rewrittenTargetType)
        || !isDenotable(rewrittenTargetType)
        || !checks.subtypeParameterNullability(rewrittenTargetType, sourceType, state)) {
      return null;
    }
    String printed = GenericsChecks.prettyTypeForError(rewrittenTargetType, state);
    return printed.equals(GenericsChecks.prettyTypeForError(targetType, state)) ? null : printed;
  }

  /**
   * Returns {@code target} with {@code @Nullable} added where {@code source} carries it, or {@code
   * null} where the two already agree or the annotation to copy cannot be found.
   *
   * <p>The repair only ever widens what the target accepts. Taking a {@code @Nullable} away, which
   * is what the other direction would need, changes a contract the author stated on purpose, and no
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
    return null;
  }

  /** Returns {@code type} with each node in {@code replacements} replaced by its new form. */
  private static Type replaceNodes(Type type, IdentityHashMap<Type, Type> replacements) {
    return type.accept(
        new Type.StructuralTypeMapping<@Nullable Void>() {
          private Type replaced(Type t, java.util.function.Supplier<Type> otherwise) {
            Type replacement = replacements.get(t);
            return replacement != null ? replacement : otherwise.get();
          }

          @Override
          public Type visitClassType(Type.ClassType t, @Nullable Void unused) {
            return replaced(t, () -> super.visitClassType(t, null));
          }

          @Override
          public Type visitWildcardType(Type.WildcardType t, @Nullable Void unused) {
            return replaced(t, () -> super.visitWildcardType(t, null));
          }

          @Override
          public Type visitArrayType(Type.ArrayType t, @Nullable Void unused) {
            return replaced(t, () -> super.visitArrayType(t, null));
          }

          @Override
          public Type visitTypeVar(Type.TypeVar t, @Nullable Void unused) {
            return replaced(t, () -> super.visitTypeVar(t, null));
          }
        },
        null);
  }

  /**
   * Returns {@code sourceType} with an explicit upper bound written out for every wildcard that
   * gains one, or {@code null} if there is no such wildcard.
   *
   * <p>A wildcard gains an explicit bound when it is unbounded, its implicit bound is
   * {@code @Nullable}, and the target requires a non-null class type there: writing that bound out
   * is the whole of the repair this class knows how to propose. Whether the result is worth
   * suggesting is decided by the caller, against the rewritten type rather than against this list.
   *
   * <p>Requiring the bound to be a class type stands as a guard rather than as a filter with
   * traffic: no input is known to reach it, since a target bound naming a method's type variable
   * fails inference first and a class's type variable is rejected by javac. Passing it would not
   * put the name in scope where the source type was declared either, because a declared type may be
   * unimported there or carry a type variable among its own type arguments.
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
        // wildcard non-null, which is the only repair this method knows
        continue;
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
   * Returns whether every type-use annotation in {@code type} survives printing, so that a
   * suggestion naming it says what the reader would be pasting.
   *
   * <p>{@link GenericTypePrettyPrintingVisitor} prints {@code @Nullable} and drops every other
   * type-use annotation. A message that only describes a type loses nothing a reader needs; a
   * suggestion that drops one hands them a type that says something else about their code.
   */
  private boolean printsEveryAnnotation(Type type) {
    for (Attribute.TypeCompound annotation : type.getAnnotationMirrors()) {
      if (!Nullness.isNullableAnnotation(annotation.type.toString(), config)) {
        return false;
      }
    }
    if (type instanceof Type.ClassType classType) {
      for (Type typeArgument : classType.getTypeArguments()) {
        if (!printsEveryAnnotation(typeArgument)) {
          return false;
        }
      }
      Type enclosingType = classType.getEnclosingType();
      return enclosingType.hasTag(TypeTag.NONE) || printsEveryAnnotation(enclosingType);
    }
    if (type instanceof Type.WildcardType wildcardType) {
      return wildcardType.kind == BoundKind.UNBOUND || printsEveryAnnotation(wildcardType.type);
    }
    if (type instanceof Type.ArrayType arrayType) {
      return printsEveryAnnotation(arrayType.elemtype);
    }
    return true;
  }

  /**
   * Returns whether {@code type} prints as Java a reader could write, so that a suggestion naming
   * it is worth offering.
   *
   * <p>This mirrors {@link GenericTypePrettyPrintingVisitor}: that printer has four arms whose
   * output cannot serve as a suggestion. Three emit text no declaration may contain — a captured
   * wildcard prints as {@code capture of ?}, an intersection as {@code A & B}, an anonymous class
   * under its binary name. The fourth prints a type that did not resolve under a bare name, which
   * is writable but denotes nothing, so pasting it trades one compile error for another. Dispatch
   * is by visitor rather than by {@code instanceof} because {@link Type.CapturedType} extends
   * {@link Type.TypeVar} and {@link Type.IntersectionClassType} extends {@link Type.ClassType}, so
   * a chain of {@code instanceof} arms would classify a capture as a type variable.
   */
  private static boolean isDenotable(Type type) {
    return type.accept(DENOTABILITY_VISITOR, null);
  }

  private static final Types.DefaultTypeVisitor<Boolean, @Nullable Void> DENOTABILITY_VISITOR =
      new Types.DefaultTypeVisitor<>() {
        @Override
        public Boolean visitClassType(Type.ClassType t, @Nullable Void unused) {
          if (t.isIntersection() || t.tsym.isAnonymous()) {
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
          return t.kind == BoundKind.UNBOUND || t.type.accept(this, null);
        }

        @Override
        public Boolean visitArrayType(Type.ArrayType t, @Nullable Void unused) {
          return t.elemtype.accept(this, null);
        }

        @Override
        public Boolean visitCapturedType(Type.CapturedType t, @Nullable Void unused) {
          return false;
        }

        @Override
        public Boolean visitErrorType(Type.ErrorType t, @Nullable Void unused) {
          return false;
        }

        @Override
        public Boolean visitType(Type t, @Nullable Void unused) {
          return true;
        }
      };

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

  /**
   * Returns the type variable that {@code typeArgument} instantiates where it occurs inside {@code
   * enclosingType}, or {@code null} if {@code typeArgument} is not a type argument of {@code
   * enclosingType} or of a type nested in it.
   *
   * <p>javac keeps a similar link in {@link Type.WildcardType#bound}, but overwrites it whenever
   * the wildcard is substituted into a supertype: after viewing {@code List<?>} as a {@code
   * Collection}, the wildcard points at a type variable of an intermediate supertype rather than at
   * {@code E} of {@code List}. Locating the wildcard by position reports the type the user wrote.
   */
  @SuppressWarnings({"ReferenceEquality", "TypeEquals"})
  private static Type.@Nullable TypeVar typeVariableAtPositionOf(
      Type typeArgument, Type enclosingType) {
    if (enclosingType instanceof Type.ArrayType arrayType) {
      return typeVariableAtPositionOf(typeArgument, arrayType.elemtype);
    }
    if (!(enclosingType instanceof Type.ClassType classType)) {
      return null;
    }
    List<Type> typeArguments = classType.getTypeArguments();
    List<Type> formals = classType.tsym.type.getTypeArguments();
    for (int i = 0; i < typeArguments.size(); i++) {
      Type actual = typeArguments.get(i);
      // identity, not isSameType: the same wildcard may appear at several positions
      if (actual == typeArgument) {
        return i < formals.size() && formals.get(i) instanceof Type.TypeVar formal ? formal : null;
      }
      Type.TypeVar nested = typeVariableAtPositionOf(typeArgument, actual);
      if (nested != null) {
        return nested;
      }
    }
    return typeVariableAtPositionOf(typeArgument, classType.getEnclosingType());
  }
}
