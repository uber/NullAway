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
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import org.checkerframework.dataflow.cfg.node.FieldAccessNode;
import org.checkerframework.dataflow.cfg.node.LocalVariableNode;
import org.checkerframework.dataflow.cfg.node.MethodAccessNode;
import org.checkerframework.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.dataflow.cfg.node.Node;
import org.checkerframework.dataflow.cfg.node.ThisLiteralNode;
import org.checkerframework.dataflow.cfg.node.VariableDeclarationNode;
import org.checkerframework.javacutil.TreeUtils;

/**
 * Represents an extended notion of an access path, which we track for nullness.
 *
 * <p>Typically, access paths are of the form x.f.g.h, where x is a variable and f, g, and h are
 * field names. Here, we also allow no-argument methods to appear in the access path, so it can be
 * of the form x.f().g.h() in general.
 *
 * <p>We do not allow array accesses in access paths for the moment.
 */
public class AccessPath {

  private final Root root;

  private final ImmutableList<Element> elements;

  /**
   * if present, the argument to the map get() method call that is the final element of this path
   */
  @Nullable private final AccessPath mapGetArgAccessPath;

  AccessPath(Root root, List<Element> elements) {
    this.root = root;
    this.elements = ImmutableList.copyOf(elements);
    this.mapGetArgAccessPath = null;
  }

  private AccessPath(Root root, List<Element> elements, AccessPath mapGetArgAccessPath) {
    this.root = root;
    this.elements = ImmutableList.copyOf(elements);
    this.mapGetArgAccessPath = mapGetArgAccessPath;
  }

  /**
   * @param node the local
   * @return access path representing the local
   */
  static AccessPath fromLocal(LocalVariableNode node) {
    return new AccessPath(new Root(node.getElement()), ImmutableList.of());
  }

  /**
   * @param node the variable declaration
   * @return access path representing the variable declaration
   */
  static AccessPath fromVarDecl(VariableDeclarationNode node) {
    Element elem = TreeUtils.elementFromDeclaration(node.getTree());
    return new AccessPath(new Root(elem), ImmutableList.of());
  }

  /**
   * @param node the field access
   * @return access path for the field access, or <code>null</code> if it cannot be represented
   */
  @Nullable
  static AccessPath fromFieldAccess(FieldAccessNode node) {
    List<Element> elements = new ArrayList<>();
    Root root = populateElementsRec(node, elements);
    return (root != null) ? new AccessPath(root, elements) : null;
  }

  /**
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
    List<Element> elements = new ArrayList<>();
    Root root = populateElementsRec(node, elements);
    return (root != null) ? new AccessPath(root, elements) : null;
  }

  /**
   * @param base the base expression for the access path
   * @param element the final element of the access path (a field or method)
   * @return the {@link AccessPath} {@code base.element}
   */
  @Nullable
  public static AccessPath fromBaseAndElement(Node base, Element element) {
    List<Element> elements = new ArrayList<>();
    Root root = populateElementsRec(base, elements);
    if (root == null) {
      return null;
    }
    elements.add(element);
    return new AccessPath(root, elements);
  }

  /**
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
  private static AccessPath fromMapGetCall(MethodInvocationNode node) {
    Node argument = node.getArgument(0);
    AccessPath argAccessPath = getAccessPathForNodeNoMapGet(argument);
    if (argAccessPath == null) {
      return null;
    }
    MethodAccessNode target = node.getTarget();
    Node receiver = target.getReceiver();
    List<Element> elements = new ArrayList<>();
    Root root = populateElementsRec(receiver, elements);
    if (root == null) {
      return null;
    }
    return new AccessPath(root, elements, argAccessPath);
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

  @Nullable
  private static Root populateElementsRec(Node node, List<Element> elements) {
    Root result;
    if (node instanceof FieldAccessNode) {
      FieldAccessNode fieldAccess = (FieldAccessNode) node;
      if (fieldAccess.isStatic()) {
        // this is the root
        result = new Root(fieldAccess.getElement());
      } else {
        // instance field access
        result = populateElementsRec(fieldAccess.getReceiver(), elements);
        elements.add(fieldAccess.getElement());
      }
    } else if (node instanceof MethodInvocationNode) {
      MethodInvocationNode invocation = (MethodInvocationNode) node;
      // only support zero-argument methods
      if (invocation.getArguments().size() > 0) {
        return null;
      }
      MethodAccessNode accessNode = invocation.getTarget();
      result = populateElementsRec(accessNode.getReceiver(), elements);
      elements.add(accessNode.getMethod());
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
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    AccessPath that = (AccessPath) o;

    if (!root.equals(that.root)) {
      return false;
    }
    if (!elements.equals(that.elements)) {
      return false;
    }
    return mapGetArgAccessPath != null
        ? (that.mapGetArgAccessPath != null && mapGetArgAccessPath.equals(that.mapGetArgAccessPath))
        : that.mapGetArgAccessPath == null;
  }

  @Override
  public int hashCode() {
    int result = root.hashCode();
    result = 31 * result + elements.hashCode();
    result = 31 * result + (mapGetArgAccessPath != null ? mapGetArgAccessPath.hashCode() : 0);
    return result;
  }

  public Root getRoot() {
    return root;
  }

  public ImmutableList<Element> getElements() {
    return elements;
  }

  @Override
  public String toString() {
    return "AccessPath{" + "root=" + root + ", elements=" + elements + '}';
  }

  private static boolean isMapMethod(Symbol.MethodSymbol symbol, Types types, String methodName) {
    if (!symbol.getSimpleName().toString().equals(methodName)) {
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
    return isMapMethod(symbol, types, "get");
  }

  public static boolean isContainsKey(Symbol.MethodSymbol symbol, Types types) {
    return isMapMethod(symbol, types, "containsKey");
  }

  public static boolean isMapPut(Symbol.MethodSymbol symbol, Types types) {
    return isMapMethod(symbol, types, "put");
  }

  /**
   * root of an access path; either a variable {@link javax.lang.model.element.Element} or <code>
   * this</code> (enclosing method receiver)
   */
  static final class Root {

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

    /** @return the variable, if not representing 'this' */
    public Element getVarElement() {
      return Preconditions.checkNotNull(varElement);
    }

    /** @return <code>true</code> if representing 'this', <code>false</code> otherwise */
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
}
