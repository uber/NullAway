/*
 * Copyright (c) 2021 Uber Technologies, Inc.
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
package com.uber.nullaway.handlers;

import static com.uber.nullaway.ASTHelpersBackports.getEnclosedElements;
import static com.uber.nullaway.NullabilityUtil.castToNonNull;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.VisitorState;
import com.google.errorprone.suppliers.Supplier;
import com.google.errorprone.suppliers.Suppliers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.uber.nullaway.NullAway;
import com.uber.nullaway.Nullness;
import com.uber.nullaway.annotations.Initializer;
import com.uber.nullaway.dataflow.AccessPath;
import com.uber.nullaway.dataflow.AccessPathNullnessPropagation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.type.TypeKind;
import org.checkerframework.nullaway.dataflow.cfg.node.MethodInvocationNode;
import org.checkerframework.nullaway.dataflow.cfg.node.Node;

public class GrpcHandler extends BaseNoOpHandler {
  private static final String GRPC_METADATA_TNAME = "io.grpc.Metadata";
  private static final String GRPC_METADATA_KEY_TNAME = "io.grpc.Metadata.Key";
  private static final String GRPC_CONTAINSKEY_MNAME = "containsKey";
  private static final String GRPC_GETTER_MNAME = "get";

  private static final Supplier<Type> GRPC_METADATA_TYPE_SUPPLIER =
      Suppliers.typeFromString(GRPC_METADATA_TNAME);

  private static final Supplier<Type> GRPC_METADATA_KEY_TYPE_SUPPLIER =
      Suppliers.typeFromString(GRPC_METADATA_KEY_TNAME);

  private Optional<Type> grpcMetadataType;
  private Optional<Type> grpcKeyType;

  /**
   * This method is annotated {@code @Initializer} since it will be invoked when the first class is
   * processed, before any other handler methods
   */
  @Initializer
  @Override
  public void onMatchTopLevelClass(
      NullAway analysis, ClassTree tree, VisitorState state, Symbol.ClassSymbol classSymbol) {
    if (grpcMetadataType == null || grpcKeyType == null) {
      grpcMetadataType =
          Optional.ofNullable(GRPC_METADATA_TYPE_SUPPLIER.get(state))
              .map(state.getTypes()::erasure);
      grpcKeyType =
          Optional.ofNullable(GRPC_METADATA_KEY_TYPE_SUPPLIER.get(state))
              .map(state.getTypes()::erasure);
    }
  }

  @Override
  public NullnessHint onDataflowVisitMethodInvocation(
      MethodInvocationNode node,
      Symbol.MethodSymbol symbol,
      VisitorState state,
      AccessPath.AccessPathContext apContext,
      AccessPathNullnessPropagation.SubNodeValues inputs,
      AccessPathNullnessPropagation.Updates thenUpdates,
      AccessPathNullnessPropagation.Updates elseUpdates,
      AccessPathNullnessPropagation.Updates bothUpdates) {
    MethodInvocationTree tree = castToNonNull(node.getTree());
    Types types = state.getTypes();
    if (grpcIsMetadataContainsKeyCall(symbol, types)) {
      // On seeing o.containsKey(k), set AP for o.get(k) to @NonNull
      Element getter = getGetterForMetadataSubtype(symbol.enclClass(), types);
      Node base = node.getTarget().getReceiver();
      // Argument list and types should be already checked by grpcIsMetadataContainsKeyCall
      Symbol keyArgSymbol = ASTHelpers.getSymbol(tree.getArguments().get(0));
      if (getter != null
          && keyArgSymbol instanceof Symbol.VarSymbol
          && keyArgSymbol.getKind().equals(ElementKind.FIELD)) {
        Symbol.VarSymbol varSymbol = (Symbol.VarSymbol) keyArgSymbol;
        String immutableFieldFQN =
            varSymbol.enclClass().flatName().toString() + "." + varSymbol.flatName().toString();
        String keyStr = AccessPath.immutableFieldNameAsConstantArgument(immutableFieldFQN);
        List<String> constantArgs = new ArrayList<>(1);
        constantArgs.add(keyStr);
        AccessPath ap =
            AccessPath.fromBaseMethodAndConstantArgs(base, getter, constantArgs, apContext);
        if (ap != null) {
          thenUpdates.set(ap, Nullness.NONNULL);
        }
      }
    }
    return NullnessHint.UNKNOWN;
  }

  @Override
  public ImmutableSet<String> onRegisterImmutableTypes() {
    return ImmutableSet.of(GRPC_METADATA_KEY_TNAME);
  }

  @Nullable
  private Symbol.MethodSymbol getGetterForMetadataSubtype(
      Symbol.ClassSymbol classSymbol, Types types) {
    // Is there a better way than iteration?
    for (Symbol elem : getEnclosedElements(classSymbol)) {
      if (elem.getKind().equals(ElementKind.METHOD)) {
        Symbol.MethodSymbol methodSymbol = (Symbol.MethodSymbol) elem;
        if (grpcIsMetadataGetCall(methodSymbol, types)) {
          return methodSymbol;
        }
      }
    }
    return null;
  }

  private boolean grpcIsMetadataContainsKeyCall(Symbol.MethodSymbol symbol, Types types) {
    // noinspection ConstantConditions
    return grpcMetadataType.isPresent()
        && grpcKeyType.isPresent()
        // Check declaring class type first, as that will short-circuit 99% of cases
        && types.isSubtype(symbol.owner.type, grpcMetadataType.get())
        && symbol.getSimpleName().toString().startsWith(GRPC_CONTAINSKEY_MNAME)
        && symbol.getParameters().length() == 1
        && types.isSubtype(symbol.getParameters().get(0).type, grpcKeyType.get())
        && symbol.getReturnType().getKind() == TypeKind.BOOLEAN;
  }

  private boolean grpcIsMetadataGetCall(Symbol.MethodSymbol symbol, Types types) {
    // noinspection ConstantConditions
    return grpcMetadataType.isPresent()
        && grpcKeyType.isPresent()
        && types.isSubtype(symbol.owner.type, grpcMetadataType.get())
        && symbol.getSimpleName().toString().startsWith(GRPC_GETTER_MNAME)
        && symbol.getParameters().length() == 1
        && types.isSubtype(symbol.getParameters().get(0).type, grpcKeyType.get());
  }
}
