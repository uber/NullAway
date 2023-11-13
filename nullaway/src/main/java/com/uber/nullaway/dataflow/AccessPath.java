/*
 * Copyright (c) 2017 Uber Technologies, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.uber.nullaway.dataflow;

import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.NullabilityUtil;
import com.uber.nullaway.annotations.JacocoIgnoreGenerated;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import org.checkerframework.nullaway.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.nullaway.dataflow.cfg.node.IntegerLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.nullaway.dataflow.cfg.node.LongLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodAccessNode;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;
import org.checkerframework.nullaway.dataflow.cfg.node.StringLiteralNode;
import org.checkerframework.nullaway.dataflow.cfg.node.SuperNode;
import org.checkerframework.nullaway.dataflow.cfg.node.ThisNode;
import org.checkerframework.nullaway.dataflow.cfg.node.TypeCastNode;
import org.checkerframework.nullaway.dataflow.cfg.node.VariableDeclarationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.WideningConversionNode;
import org.checkerframework.nullaway.javacutil.TreeUtils;

/**
 * Represents an extended notion of an access path, which we track for nullness.
 *
 * <p>Typically, access paths are of the form x.f.g.h, where x is a variable and f, g, and h are
 * field names. Here, we also allow no-argument methods to appear in the access path, as well as
 * method calls passed only statically constant parameters, so an AP can be of the form
 * x.f().g.h([int_expr|string_expr]) in general.
 *
 * <p>We do not allow array accesses in access paths for the moment.
 */
public final class AccessPath implements MapKey {

  /**
   * A prefix added for elements appearing in method invocation APs which represent fields that can
   * be proven to be class-initialization time constants (i.e. static final fields of a type known
   * to be structurally immutable, such as io.grpc.Metadata.Key).
   *
   * <p>This prefix helps avoid collisions between common field names and common strings, e.g.
   * "KEY_1" and the field KEY_1.
   */
  private static final String IMMUTABLE_FIELD_PREFIX = "static final [immutable] field: ";

  /**
   * Encode a static final field as a constant argument on a method's AccessPathElement
   *
   * <p>The field must be of a type known to be structurally immutable, in addition to being
   * declared static and final for this encoding to make any sense. We do not verify this here, and
   * rather operate only on the field's fully qualified name, as this is intended to be a quick
   * utility method.
   *
   * @param fieldFQN the field's Fully Qualified Name
   * @return a string suitable to be included as part of the constant arguments of an
   *     AccessPathElement, assuming the field is indeed static final and of an structurally
   *     immutable type
   */
  public static String immutableFieldNameAsConstantArgument(String fieldFQN) {
    return IMMUTABLE_FIELD_PREFIX + fieldFQN;
  }

  /** Root of the access path. If {@code null}, the root is the receiver argument */
  @Nullable private final Element root;

  private final ImmutableList<AccessPathElement> elements;

  /**
   * if present, the argument to the map get() method call that is the final element of this path
   */
  @Nullable private final MapKey mapGetArg;

  private AccessPath(@Nullable Element root, ImmutableList<AccessPathElement> elements) {
    this(root, elements, null);
  }

  private AccessPath(
      @Nullable Element root,
      ImmutableList<AccessPathElement> elements,
      @Nullable MapKey mapGetArg) {
    this.root = root;
    this.elements = elements;
    this.mapGetArg = mapGetArg;
  }

  /**
   * Construct the access path of a local.
   *
   * @param node the local
   * @return access path representing the local
   */
  public static AccessPath fromLocal(LocalVariableNode node) {
    return new AccessPath(node.getElement(), ImmutableList.of());
  }

  /**
   * Construct the access path of a variable declaration.
   *
   * @param node the variable declaration
   * @return access path representing the variable declaration
   */
  static AccessPath fromVarDecl(VariableDeclarationNode node) {
    Element elem = TreeUtils.elementFromDeclaration(node.getTree());
    return new AccessPath(elem, ImmutableList.of());
  }

  /**
   * Construct the access path of a field access.
   *
   * @param node the field access
   * @param apContext the current access path context information (see {@link
   *     AccessPath.AccessPathContext}).
   * @return access path for the field access, or <code>null</code> if it cannot be represented
   */
  @Nullable
  static AccessPath fromFieldAccess(FieldAccessNode node, AccessPathContext apContext) {
    return fromNodeAndContext(node, apContext);
  }

  @Nullable
  private static AccessPath fromNodeAndContext(Node node, AccessPathContext apContext) {
    return buildAccessPathRecursive(node, new ArrayDeque<>(), apContext, null);
  }

  /**
   * Construct the access path of a method call.
   *
   * @param node the method call
   * @param apContext the current access path context information (see {@link
   *     AccessPath.AccessPathContext}).
   * @return access path for the method call, or <code>null</code> if it cannot be represented
   */
  @Nullable
  static AccessPath fromMethodCall(
      MethodInvocationNode node, VisitorState state, AccessPathContext apContext) {
    if (isMapGet(ASTHelpers.getSymbol(node.getTree()), state)) {
      return fromMapGetCall(node, state, apContext);
    }
    return fromVanillaMethodCall(node, apContext);
  }

  @Nullable
  private static AccessPath fromVanillaMethodCall(
      MethodInvocationNode node, AccessPathContext apContext) {
    return fromNodeAndContext(node, apContext);
  }

  /**
   * Returns an access path rooted at {@code newRoot} with the same elements and map-get argument as
   * {@code origAP}
   */
  static AccessPath switchRoot(AccessPath origAP, Element newRoot) {
    return new AccessPath(newRoot, origAP.elements, origAP.mapGetArg);
  }

  /**
   * Construct the access path given a {@code base.element} structure.
   *
   * @param base the base expression for the access path
   * @param element the final element of the access path (a field or method)
   * @param apContext the current access path context information (see {@link
   *     AccessPath.AccessPathContext}).
   * @return the {@link AccessPath} {@code base.element}
   */
  @Nullable
  public static AccessPath fromBaseAndElement(
      Node base, Element element, AccessPathContext apContext) {
    return fromNodeElementAndContext(base, new AccessPathElement(element), apContext);
  }

  @Nullable
  private static AccessPath fromNodeElementAndContext(
      Node base, AccessPathElement apElement, AccessPathContext apContext) {
    ArrayDeque<AccessPathElement> elements = new ArrayDeque<>();
    elements.push(apElement);
    return buildAccessPathRecursive(base, elements, apContext, null);
  }

  /**
   * Construct the access path given a {@code base.method(CONS)} structure.
   *
   * <p>IMPORTANT: Be careful with this method, the argument list is not the variable names of the
   * method arguments, but rather the string representation of primitive-type compile-time constants
   * or the name of static final fields of structurally immutable types (see {@link
   * #buildAccessPathRecursive(Node, ArrayDeque, AccessPathContext, MapKey)}).
   *
   * <p>This is used by a few specialized Handlers to set nullability around particular paths
   * involving constants.
   *
   * @param base the base expression for the access path
   * @param method the last method call in the access path
   * @param constantArguments a list of <b>constant</b> arguments passed to the method call
   * @param apContext the current access path context information (see {@link
   *     AccessPath.AccessPathContext}).
   * @return the {@link AccessPath} {@code base.method(CONS)}
   */
  @Nullable
  public static AccessPath fromBaseMethodAndConstantArgs(
      Node base, Element method, List<String> constantArguments, AccessPathContext apContext) {
    return fromNodeElementAndContext(
        base, new AccessPathElement(method, constantArguments), apContext);
  }

  /**
   * Construct the access path for <code>map.get(x)</code> from an invocation of <code>put(x)</code>
   * or <code>containsKey(x)</code>.
   *
   * @param node a node invoking containsKey() or put() on a map
   * @param apContext the current access path context information (see {@link
   *     AccessPath.AccessPathContext}).
   * @return an AccessPath representing invoking get() on the same type of map as from node, passing
   *     the same first argument as is passed in node
   */
  @Nullable
  public static AccessPath getForMapInvocation(
      MethodInvocationNode node, VisitorState state, AccessPathContext apContext) {
    // For the receiver type for get, use the declared type of the receiver of the containsKey()
    // call. Note that this may differ from the containing class of the resolved containsKey()
    // method, which can be in a superclass (e.g., LinkedHashMap does not override containsKey())
    // assumption: map type will not both override containsKey() and inherit get()
    return fromMapGetCall(node, state, apContext);
  }

  private static Node stripCasts(Node node) {
    while (node instanceof TypeCastNode) {
      node = ((TypeCastNode) node).getOperand();
    }
    return node;
  }

  @Nullable
  private static MapKey argumentToMapKeySpecifier(
      Node argument, VisitorState state, AccessPathContext apContext) {
    // Required to have Node type match Tree type in some instances.
    if (argument instanceof WideningConversionNode) {
      argument = ((WideningConversionNode) argument).getOperand();
    }
    // A switch at the Tree level should be faster than multiple if checks at the Node level.
    switch (castToNonNull(argument.getTree()).getKind()) {
      case STRING_LITERAL:
        return new StringMapKey(((StringLiteralNode) argument).getValue());
      case INT_LITERAL:
        return new NumericMapKey(((IntegerLiteralNode) argument).getValue());
      case LONG_LITERAL:
        return new NumericMapKey(((LongLiteralNode) argument).getValue());
      case METHOD_INVOCATION:
        MethodAccessNode target = ((MethodInvocationNode) argument).getTarget();
        Node receiver = stripCasts(target.getReceiver());
        List<Node> arguments = ((MethodInvocationNode) argument).getArguments();
        // Check for int/long boxing.
        if (target.getMethod().getSimpleName().toString().equals("valueOf")
            && arguments.size() == 1
            && castToNonNull(receiver.getTree()).getKind().equals(Tree.Kind.IDENTIFIER)
            && (receiver.toString().equals("Integer") || receiver.toString().equals("Long"))) {
          return argumentToMapKeySpecifier(arguments.get(0), state, apContext);
        }
        // Fine to fallthrough:
      default:
        // Every other type of expression, including variables, field accesses, new A(...), etc.
        return getAccessPathForNode(argument, state, apContext); // Every AP is a MapKey too
    }
  }

  @Nullable
  private static AccessPath fromMapGetCall(
      MethodInvocationNode node, VisitorState state, AccessPathContext apContext) {
    Node argument = node.getArgument(0);
    MapKey mapKey = argumentToMapKeySpecifier(argument, state, apContext);
    if (mapKey == null) {
      return null;
    }
    MethodAccessNode target = node.getTarget();
    Node receiver = stripCasts(target.getReceiver());
    return buildAccessPathRecursive(receiver, new ArrayDeque<>(), apContext, mapKey);
  }

  /**
   * Gets corresponding AccessPath for node, if it exists. Handles calls to <code>Map.get()
   * </code>
   *
   * @param node AST node
   * @param state the visitor state
   * @param apContext the current access path context information (see {@link
   *     AccessPath.AccessPathContext}).
   * @return corresponding AccessPath if it exists; <code>null</code> otherwise
   */
  @Nullable
  public static AccessPath getAccessPathForNode(
      Node node, VisitorState state, AccessPathContext apContext) {
    if (node instanceof LocalVariableNode) {
      return fromLocal((LocalVariableNode) node);
    } else if (node instanceof FieldAccessNode) {
      return fromFieldAccess((FieldAccessNode) node, apContext);
    } else if (node instanceof MethodInvocationNode) {
      return fromMethodCall((MethodInvocationNode) node, state, apContext);
    } else {
      return null;
    }
  }

  /**
   * Constructs an access path ending with the class field element in the argument. The receiver is
   * the method receiver itself.
   *
   * @param element the receiver element.
   * @return access path representing the class field
   */
  public static AccessPath fromFieldElement(VariableElement element) {
    Preconditions.checkArgument(
        element.getKind().isField(),
        "element must be of type: FIELD but received: " + element.getKind());
    return new AccessPath(null, ImmutableList.of(new AccessPathElement(element)));
  }

  private static boolean isBoxingMethod(Symbol.MethodSymbol methodSymbol) {
    if (methodSymbol.isStatic() && methodSymbol.getSimpleName().contentEquals("valueOf")) {
      Symbol.PackageSymbol enclosingPackage = ASTHelpers.enclosingPackage(methodSymbol.enclClass());
      return enclosingPackage != null && enclosingPackage.fullname.contentEquals("java.lang");
    }
    return false;
  }

  /**
   * A helper function that recursively builds an AccessPath from a CFG node.
   *
   * @param node the CFG node
   * @param elements elements to append to the final access path.
   * @param apContext context information, used to handle cases with constant arguments
   * @param mapKey map key to be used as the map-get argument, or {@code null} if there is no key
   * @return the final access path
   */
  @Nullable
  private static AccessPath buildAccessPathRecursive(
      Node node,
      ArrayDeque<AccessPathElement> elements,
      AccessPathContext apContext,
      @Nullable MapKey mapKey) {
    AccessPath result;
    if (node instanceof FieldAccessNode) {
      FieldAccessNode fieldAccess = (FieldAccessNode) node;
      if (fieldAccess.isStatic()) {
        // this is the root
        result = new AccessPath(fieldAccess.getElement(), ImmutableList.copyOf(elements), mapKey);
      } else {
        // instance field access
        elements.push(new AccessPathElement(fieldAccess.getElement()));
        result =
            buildAccessPathRecursive(
                stripCasts(fieldAccess.getReceiver()), elements, apContext, mapKey);
      }
    } else if (node instanceof MethodInvocationNode) {
      MethodInvocationNode invocation = (MethodInvocationNode) node;
      AccessPathElement accessPathElement;
      MethodAccessNode accessNode = invocation.getTarget();
      if (invocation.getArguments().size() == 0) {
        Symbol.MethodSymbol symbol = ASTHelpers.getSymbol(invocation.getTree());
        if (symbol.isStatic()) {
          // a zero-argument static method call can be the root of an access path
          return new AccessPath(symbol, ImmutableList.copyOf(elements), mapKey);
        } else {
          accessPathElement = new AccessPathElement(accessNode.getMethod());
        }
      } else {
        List<String> constantArgumentValues = new ArrayList<>();
        for (Node argumentNode : invocation.getArguments()) {
          Tree tree = argumentNode.getTree();
          if (tree == null) {
            return null; // Not an AP
          } else if (tree.getKind().equals(Tree.Kind.METHOD_INVOCATION)) {
            // Check for boxing call
            MethodInvocationTree methodInvocationTree = (MethodInvocationTree) tree;
            if (methodInvocationTree.getArguments().size() == 1
                && isBoxingMethod(ASTHelpers.getSymbol(methodInvocationTree))) {
              tree = methodInvocationTree.getArguments().get(0);
            }
          }
          switch (tree.getKind()) {
            case BOOLEAN_LITERAL:
            case CHAR_LITERAL:
            case DOUBLE_LITERAL:
            case FLOAT_LITERAL:
            case INT_LITERAL:
            case LONG_LITERAL:
            case STRING_LITERAL:
              constantArgumentValues.add(((LiteralTree) tree).getValue().toString());
              break;
            case NULL_LITERAL:
              // Um, probably not? Return null for now.
              return null; // Not an AP
            case MEMBER_SELECT: // check for Foo.CONST
            case IDENTIFIER: // check for CONST
              // Check for a constant field (static final)
              Symbol symbol = ASTHelpers.getSymbol(tree);
              if (symbol instanceof Symbol.VarSymbol
                  && symbol.getKind().equals(ElementKind.FIELD)) {
                Symbol.VarSymbol varSymbol = (Symbol.VarSymbol) symbol;
                // From docs: getConstantValue() returns the value of this variable if this is a
                // static final field initialized to a compile-time constant. Returns null
                // otherwise.
                // This means that foo(FOUR) will match foo(4) iff FOUR=4 is a compile time
                // constant :)
                Object constantValue = varSymbol.getConstantValue();
                if (constantValue != null) {
                  constantArgumentValues.add(constantValue.toString());
                  break;
                }
                // The above will not work for static final fields of reference type, since they are
                // initialized at class-initialization time, not compile time. Properly handling
                // such fields would further require proving deep immutability for the object type
                // itself. We use a handler-augment list of safe types:
                Set<Modifier> modifiersSet = varSymbol.getModifiers();
                if (modifiersSet.contains(Modifier.STATIC)
                    && modifiersSet.contains(Modifier.FINAL)
                    && apContext.isStructurallyImmutableType(varSymbol.type)) {
                  String immutableFieldFQN =
                      varSymbol.enclClass().flatName().toString()
                          + "."
                          + varSymbol.flatName().toString();
                  constantArgumentValues.add(
                      immutableFieldNameAsConstantArgument(immutableFieldFQN));
                  break;
                }
              }
              // Cascade to default, symbol is not a constant field
              // fall through
            default:
              return null; // Not an AP
          }
        }
        accessPathElement = new AccessPathElement(accessNode.getMethod(), constantArgumentValues);
      }
      elements.push(accessPathElement);
      result =
          buildAccessPathRecursive(
              stripCasts(accessNode.getReceiver()), elements, apContext, mapKey);
    } else if (node instanceof LocalVariableNode) {
      result =
          new AccessPath(
              ((LocalVariableNode) node).getElement(), ImmutableList.copyOf(elements), mapKey);
    } else if (node instanceof ThisNode || node instanceof SuperNode) {
      result = new AccessPath(null, ImmutableList.copyOf(elements), mapKey);
    } else {
      // don't handle any other cases
      result = null;
    }
    return result;
  }

  /**
   * Creates an access path representing a Map get call, where the key is obtained by calling {@code
   * next()} on some {@code Iterator}. Used to support reasoning about iteration over a map's key
   * set using an enhanced-for loop.
   *
   * @param mapNode Node representing the map
   * @param iterVar local variable holding the iterator
   * @param apContext access path context
   * @return access path representing the get call, or {@code null} if the map node cannot be
   *     represented with an access path
   */
  @Nullable
  public static AccessPath mapWithIteratorContentsKey(
      Node mapNode, LocalVariableNode iterVar, AccessPathContext apContext) {
    IteratorContentsKey iterContentsKey =
        new IteratorContentsKey((VariableElement) iterVar.getElement());
    return buildAccessPathRecursive(mapNode, new ArrayDeque<>(), apContext, iterContentsKey);
  }

  /**
   * Creates an access path identical to {@code accessPath} (which must represent a map get), but
   * replacing its map {@code get()} argument with {@code mapKey}
   */
  public static AccessPath replaceMapKey(AccessPath accessPath, MapKey mapKey) {
    return new AccessPath(accessPath.getRoot(), accessPath.getElements(), mapKey);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    AccessPath that = (AccessPath) o;
    return Objects.equals(root, that.root)
        && elements.equals(that.elements)
        && Objects.equals(mapGetArg, that.mapGetArg);
  }

  @Override
  public int hashCode() {
    int result = 1;
    result = 31 * result + (root != null ? root.hashCode() : 0);
    result = 31 * result + elements.hashCode();
    result = 31 * result + (mapGetArg != null ? mapGetArg.hashCode() : 0);
    return result;
  }

  /**
   * Returns the root element of the access path. If the root is the receiver argument, returns
   * {@code null}.
   */
  @Nullable
  public Element getRoot() {
    return root;
  }

  public ImmutableList<AccessPathElement> getElements() {
    return elements;
  }

  @Nullable
  public MapKey getMapGetArg() {
    return mapGetArg;
  }

  @Override
  public String toString() {
    return "AccessPath{"
        + "root="
        + (root == null ? "this" : root)
        + ", elements="
        + elements
        + ", mapGetArg="
        + mapGetArg
        + '}';
  }

  private static boolean isMapGet(Symbol.MethodSymbol symbol, VisitorState state) {
    return NullabilityUtil.isMapMethod(symbol, state, "get", 1);
  }

  public static boolean isContainsKey(Symbol.MethodSymbol symbol, VisitorState state) {
    return NullabilityUtil.isMapMethod(symbol, state, "containsKey", 1);
  }

  public static boolean isMapPut(Symbol.MethodSymbol symbol, VisitorState state) {
    return NullabilityUtil.isMapMethod(symbol, state, "put", 2)
        || NullabilityUtil.isMapMethod(symbol, state, "putIfAbsent", 2);
  }

  public static boolean isMapComputeIfAbsent(Symbol.MethodSymbol symbol, VisitorState state) {
    return NullabilityUtil.isMapMethod(symbol, state, "computeIfAbsent", 2);
  }

  private static final class StringMapKey implements MapKey {

    private final String key;

    public StringMapKey(String key) {
      this.key = key;
    }

    @Override
    public int hashCode() {
      return this.key.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof StringMapKey) {
        return this.key.equals(((StringMapKey) obj).key);
      }
      return false;
    }
  }

  private static final class NumericMapKey implements MapKey {

    private final long key;

    public NumericMapKey(long key) {
      this.key = key;
    }

    @Override
    public int hashCode() {
      return Long.hashCode(this.key);
    }

    /**
     * We ignore this method for code coverage since there is non-determinism somewhere deep in a
     * Map implementation such that, depending on how AccessPaths get bucketed in the Map (which
     * depends on non-deterministic hash codes), sometimes this method is called and sometimes it is
     * not.
     */
    @Override
    @JacocoIgnoreGenerated
    public boolean equals(Object obj) {
      if (obj instanceof NumericMapKey) {
        return this.key == ((NumericMapKey) obj).key;
      }
      return false;
    }
  }

  /**
   * Represents all possible values that could be returned by calling {@code next()} on an {@code
   * Iterator} variable
   */
  public static final class IteratorContentsKey implements MapKey {

    /**
     * Element for the local variable holding the {@code Iterator}. We only support locals for now,
     * as this class is designed specifically for reasoning about iterating over map keys using an
     * enhanced-for loop over a {@code keySet()}, and for such cases the iterator is always stored
     * locally
     */
    private final VariableElement iteratorVarElement;

    IteratorContentsKey(VariableElement iteratorVarElement) {
      this.iteratorVarElement = iteratorVarElement;
    }

    public VariableElement getIteratorVarElement() {
      return iteratorVarElement;
    }

    /**
     * We ignore this method for code coverage since there is non-determinism somewhere deep in a
     * Map implementation such that, depending on how AccessPaths get bucketed in the Map (which
     * depends on non-deterministic hash codes), sometimes this method is called and sometimes it is
     * not.
     */
    @Override
    @JacocoIgnoreGenerated
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      IteratorContentsKey that = (IteratorContentsKey) o;
      return iteratorVarElement.equals(that.iteratorVarElement);
    }

    @Override
    public int hashCode() {
      return iteratorVarElement.hashCode();
    }
  }

  /**
   * Represents a per-javac instance of an AccessPath context options.
   *
   * <p>This includes, for example, data on known structurally immutable types.
   */
  public static final class AccessPathContext {

    private final ImmutableSet<String> immutableTypes;

    private AccessPathContext(ImmutableSet<String> immutableTypes) {
      this.immutableTypes = immutableTypes;
    }

    public boolean isStructurallyImmutableType(Type type) {
      return immutableTypes.contains(type.tsym.toString());
    }

    public static Builder builder() {
      return new AccessPathContext.Builder();
    }

    /** class for building up instances of the AccessPathContext. */
    public static final class Builder {

      @Nullable private ImmutableSet<String> immutableTypes;

      Builder() {}

      /**
       * Passes the set of structurally immutable types registered into this AccessPathContext.
       *
       * <p>See {@link com.uber.nullaway.handlers.Handler#onRegisterImmutableTypes} for more info.
       *
       * @param immutableTypes the immutable types known to our dataflow analysis.
       */
      public Builder setImmutableTypes(ImmutableSet<String> immutableTypes) {
        this.immutableTypes = immutableTypes;
        return this;
      }

      /**
       * Construct the immutable AccessPathContext instance.
       *
       * @return an access path context constructed from everything added to the builder
       */
      public AccessPathContext build() {
        if (immutableTypes == null) {
          throw new IllegalStateException("must set immutable types before building");
        }
        return new AccessPathContext(immutableTypes);
      }
    }
  }
}
