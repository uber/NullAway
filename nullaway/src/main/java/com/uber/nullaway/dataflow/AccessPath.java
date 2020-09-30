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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.IntegerLiteralNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.LongLiteralNode;
import org.checkerframework.dataflow.cfg.node.MethodAccessNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.StringLiteralNode;
import org.checkerframework.dataflow.cfg.node.ThisLiteralNode;
import org.checkerframework.dataflow.cfg.node.VariableDeclarationNode;
import org.checkerframework.dataflow.cfg.node.WideningConversionNode;
import org.checkerframework.javacutil.TreeUtils;

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

  private final Root root;

  private final ImmutableList<AccessPathElement> elements;

  /**
   * if present, the argument to the map get() method call that is the final element of this path
   */
  @Nullable private final MapKey mapGetArg;

  AccessPath(Root root, List<AccessPathElement> elements) {
    this.root = root;
    this.elements = ImmutableList.copyOf(elements);
    this.mapGetArg = null;
  }

  private AccessPath(Root root, List<AccessPathElement> elements, MapKey mapGetArg) {
    this.root = root;
    this.elements = ImmutableList.copyOf(elements);
    this.mapGetArg = mapGetArg;
  }

  /**
   * Construct the access path of a local.
   *
   * @param node the local
   * @return access path representing the local
   */
  static AccessPath fromLocal(LocalVariableNode node) {
    return new AccessPath(new Root(node.getElement()), ImmutableList.of());
  }

  /**
   * Construct the access path of a variable declaration.
   *
   * @param node the variable declaration
   * @return access path representing the variable declaration
   */
  static AccessPath fromVarDecl(VariableDeclarationNode node) {
    Element elem = TreeUtils.elementFromDeclaration(node.getTree());
    return new AccessPath(new Root(elem), ImmutableList.of());
  }

  /**
   * Construct the access path of a field access.
   *
   * @param node the field access
   * @return access path for the field access, or <code>null</code> if it cannot be represented
   */
  @Nullable
  static AccessPath fromFieldAccess(FieldAccessNode node) {
    List<AccessPathElement> elements = new ArrayList<>();
    Root root = populateElementsRec(node, elements);
    return (root != null) ? new AccessPath(root, elements) : null;
  }

  /**
   * Construct the access path of a method call.
   *
   * @param node the method call
   * @return access path for the method call, or <code>null</code> if it cannot be represented
   */
  @Nullable
  static AccessPath fromMethodCall(MethodInvocationNode node, @Nullable Types types) {
    if (types != null && isMapGet(ASTHelpers.getSymbol(node.getTree()), types)) {
      return fromMapGetCall(node);
    }
    return fromVanillaMethodCall(node);
  }

  @Nullable
  private static AccessPath fromVanillaMethodCall(MethodInvocationNode node) {
    List<AccessPathElement> elements = new ArrayList<>();
    Root root = populateElementsRec(node, elements);
    return (root != null) ? new AccessPath(root, elements) : null;
  }

  /**
   * Construct the access path given a {@code base.element} structure.
   *
   * @param base the base expression for the access path
   * @param element the final element of the access path (a field or method)
   * @return the {@link AccessPath} {@code base.element}
   */
  @Nullable
  public static AccessPath fromBaseAndElement(Node base, Element element) {
    List<AccessPathElement> elements = new ArrayList<>();
    Root root = populateElementsRec(base, elements);
    if (root == null) {
      return null;
    }
    elements.add(new AccessPathElement(element));
    return new AccessPath(root, elements);
  }

  /**
   * Construct the access path for <code>map.get(x)</code> from an invocation of <code>put(x)</code>
   * or <code>containsKey(x)</code>.
   *
   * @param node a node invoking containsKey() or put() on a map
   * @return an AccessPath representing invoking get() on the same type of map as from node, passing
   *     the same first argument as is passed in node
   */
  @Nullable
  public static AccessPath getForMapInvocation(MethodInvocationNode node) {
    // For the receiver type for get, use the declared type of the receiver of the containsKey()
    // call.
    // Note that this may differ from the containing class of the resolved containsKey() method,
    // which
    // can be in a superclass (e.g., LinkedHashMap does not override containsKey())
    // assumption: map type will not both override containsKey() and inherit get()
    return fromMapGetCall(node);
  }

  @Nullable
  private static MapKey argumentToMapKeySpecifier(Node argument) {
    // Required to have Node type match Tree type in some instances.
    if (argument instanceof WideningConversionNode) {
      argument = ((WideningConversionNode) argument).getOperand();
    }
    // A switch at the Tree level should be faster than multiple if checks at the Node level.
    switch (argument.getTree().getKind()) {
      case STRING_LITERAL:
        return new StringMapKey(((StringLiteralNode) argument).getValue());
      case INT_LITERAL:
        return new NumericMapKey(((IntegerLiteralNode) argument).getValue());
      case LONG_LITERAL:
        return new NumericMapKey(((LongLiteralNode) argument).getValue());
      case METHOD_INVOCATION:
        MethodAccessNode target = ((MethodInvocationNode) argument).getTarget();
        List<Node> arguments = ((MethodInvocationNode) argument).getArguments();
        // Check for int/long boxing.
        if (target.getMethod().getSimpleName().toString().equals("valueOf")
            && arguments.size() == 1
            && target.getReceiver().getTree().getKind().equals(Tree.Kind.IDENTIFIER)
            && (target.getReceiver().toString().equals("Integer")
                || target.getReceiver().toString().equals("Long"))) {
          return argumentToMapKeySpecifier(arguments.get(0));
        }
        // Fine to fallthrough:
      default:
        // Every other type of expression, including variables, field accesses, new A(...), etc.
        return getAccessPathForNodeNoMapGet(argument); // Every AP is a MapKey too
    }
  }

  @Nullable
  private static AccessPath fromMapGetCall(MethodInvocationNode node) {
    Node argument = node.getArgument(0);
    MapKey mapKey = argumentToMapKeySpecifier(argument);
    if (mapKey == null) {
      return null;
    }
    MethodAccessNode target = node.getTarget();
    Node receiver = target.getReceiver();
    List<AccessPathElement> elements = new ArrayList<>();
    Root root = populateElementsRec(receiver, elements);
    if (root == null) {
      return null;
    }
    return new AccessPath(root, elements, mapKey);
  }

  /**
   * Gets corresponding AccessPath for node, if it exists. Does <emph>not</emph> handle calls to
   * <code>Map.get()</code>
   *
   * @param node AST node
   * @return corresponding AccessPath if it exists; <code>null</code> otherwise
   */
  @Nullable
  public static AccessPath getAccessPathForNodeNoMapGet(Node node) {
    return getAccessPathForNodeWithMapGet(node, null);
  }

  /**
   * Gets corresponding AccessPath for node, if it exists. Handles calls to <code>Map.get()
   * </code>
   *
   * @param node AST node
   * @param types javac {@link Types}
   * @return corresponding AccessPath if it exists; <code>null</code> otherwise
   */
  @Nullable
  public static AccessPath getAccessPathForNodeWithMapGet(Node node, @Nullable Types types) {
    if (node instanceof LocalVariableNode) {
      return fromLocal((LocalVariableNode) node);
    } else if (node instanceof FieldAccessNode) {
      return fromFieldAccess((FieldAccessNode) node);
    } else if (node instanceof MethodInvocationNode) {
      return fromMethodCall((MethodInvocationNode) node, types);
    } else {
      return null;
    }
  }

  public static AccessPath fromFieldAccessNode(Element fieldElement, Node nodeReceiver) {
    Tree receiver = null;
    if (nodeReceiver != null) {
      receiver = nodeReceiver.getTree();
    }
    return fromFieldAccess(fieldElement, receiver);
  }

  public static AccessPath fromFieldAccessTree(Element fieldElement, MemberSelectTree tree) {
    Tree receiver = null;
    if (tree != null) {
      receiver = tree.getExpression();
    }
    return fromFieldAccess(fieldElement, receiver);
  }

  private static AccessPath fromFieldAccess(Element fieldElement, Tree receiver) {
    List<AccessPathElement> elements = new ArrayList<>();
    elements.add(new AccessPathElement(fieldElement));
    Root root;
    if (receiver == null) {
      root = new Root();
    } else {
      root = new Root(ASTHelpers.getSymbol(receiver));
    }
    return new AccessPath(root, elements);
  }

  private static boolean isBoxingMethod(Symbol.MethodSymbol methodSymbol) {
    return methodSymbol.isStatic()
        && methodSymbol.getSimpleName().contentEquals("valueOf")
        && methodSymbol.enclClass().packge().fullname.contentEquals("java.lang");
  }

  @Nullable
  private static Root populateElementsRec(Node node, List<AccessPathElement> elements) {
    Root result;
    if (node instanceof FieldAccessNode) {
      FieldAccessNode fieldAccess = (FieldAccessNode) node;
      if (fieldAccess.isStatic()) {
        // this is the root
        result = new Root(fieldAccess.getElement());
      } else {
        // instance field access
        result = populateElementsRec(fieldAccess.getReceiver(), elements);
        elements.add(new AccessPathElement(fieldAccess.getElement()));
      }
    } else if (node instanceof MethodInvocationNode) {
      MethodInvocationNode invocation = (MethodInvocationNode) node;
      AccessPathElement accessPathElement;
      MethodAccessNode accessNode = invocation.getTarget();
      if (invocation.getArguments().size() == 0) {
        accessPathElement = new AccessPathElement(accessNode.getMethod());
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
              // Um, probably not? Cascade to default for now.
            default:
              return null; // Not an AP
          }
        }
        accessPathElement = new AccessPathElement(accessNode.getMethod(), constantArgumentValues);
      }
      result = populateElementsRec(accessNode.getReceiver(), elements);
      elements.add(accessPathElement);
    } else if (node instanceof LocalVariableNode) {
      result = new Root(((LocalVariableNode) node).getElement());
    } else if (node instanceof ThisLiteralNode) {
      result = new Root();
    } else {
      // don't handle any other cases
      result = null;
    }
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AccessPath)) {
      return false;
    }

    AccessPath that = (AccessPath) o;

    if (!root.equals(that.root)) {
      return false;
    }
    if (!elements.equals(that.elements)) {
      return false;
    }
    return mapGetArg != null
        ? (that.mapGetArg != null && mapGetArg.equals(that.mapGetArg))
        : that.mapGetArg == null;
  }

  @Override
  public int hashCode() {
    int result = root.hashCode();
    result = 31 * result + elements.hashCode();
    result = 31 * result + (mapGetArg != null ? mapGetArg.hashCode() : 0);
    return result;
  }

  public Root getRoot() {
    return root;
  }

  public ImmutableList<AccessPathElement> getElements() {
    return elements;
  }

  @Override
  public String toString() {
    return "AccessPath{" + "root=" + root + ", elements=" + elements + '}';
  }

  private static boolean isMapMethod(
      Symbol.MethodSymbol symbol, Types types, String methodName, int numParams) {
    if (!symbol.getSimpleName().toString().equals(methodName)) {
      return false;
    }
    if (symbol.getParameters().size() != numParams) {
      return false;
    }
    Symbol owner = symbol.owner;
    if (owner.getQualifiedName().toString().equals("java.util.Map")) {
      return true;
    }
    com.sun.tools.javac.util.List<Type> supertypes = types.closure(owner.type);
    for (Type t : supertypes) {
      if (t.asElement().getQualifiedName().toString().equals("java.util.Map")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isMapGet(Symbol.MethodSymbol symbol, Types types) {
    return isMapMethod(symbol, types, "get", 1);
  }

  public static boolean isContainsKey(Symbol.MethodSymbol symbol, Types types) {
    return isMapMethod(symbol, types, "containsKey", 1);
  }

  public static boolean isMapPut(Symbol.MethodSymbol symbol, Types types) {
    return isMapMethod(symbol, types, "put", 2);
  }

  /**
   * root of an access path; either a variable {@link javax.lang.model.element.Element} or <code>
   * this</code> (enclosing method receiver)
   */
  public static final class Root {

    /** does this represent the receiver? */
    private final boolean isMethodReceiver;

    @Nullable private final Element varElement;

    Root(Element varElement) {
      this.isMethodReceiver = false;
      this.varElement = Preconditions.checkNotNull(varElement);
    }

    /** for case when it represents the receiver */
    Root() {
      this.isMethodReceiver = true;
      this.varElement = null;
    }

    /**
     * Get the variable element of this access path root, if not representing <code>this</code>.
     *
     * @return the variable, if not representing 'this'
     */
    public Element getVarElement() {
      return Preconditions.checkNotNull(varElement);
    }

    /**
     * Check whether this access path root represents the receiver (i.e. <code>this</code>). s
     *
     * @return <code>true</code> if representing 'this', <code>false</code> otherwise
     */
    public boolean isReceiver() {
      return isMethodReceiver;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      Root root = (Root) o;

      if (isMethodReceiver != root.isMethodReceiver) {
        return false;
      }
      return varElement != null ? varElement.equals(root.varElement) : root.varElement == null;
    }

    @Override
    public int hashCode() {
      int result = (isMethodReceiver ? 1 : 0);
      result = 31 * result + (varElement != null ? varElement.hashCode() : 0);
      return result;
    }

    @Override
    public String toString() {
      return "Root{" + "isMethodReceiver=" + isMethodReceiver + ", varElement=" + varElement + '}';
    }
  }

  private static final class StringMapKey implements MapKey {

    private String key;

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

    private long key;

    public NumericMapKey(long key) {
      this.key = key;
    }

    @Override
    public int hashCode() {
      return Long.hashCode(this.key);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof NumericMapKey) {
        return this.key == ((NumericMapKey) obj).key;
      }
      return false;
    }
  }
}
