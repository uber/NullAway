package com.uber.nullaway;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.handlers.Handler.NullnessHint;
import org.jspecify.annotations.Nullable;

/**
 * Resolved method-reference types and return-nullness hint.
 *
 * @param methodType the referenced method's type, with receiver and available method type-argument
 *     substitutions and handler-provided models applied in JSpecify mode, or its declared type
 *     otherwise
 * @param qualifierType the instance receiver's type in JSpecify mode, instantiated from the
 *     functional interface's receiver parameter for an unbound reference; {@code null} for static
 *     references, outside JSpecify mode, or when unavailable
 * @param functionalInterfaceMethod the symbol of the target functional interface's abstract method
 * @param functionalInterfaceMethodType the functional interface method's type as a member of the
 *     ground target type in JSpecify mode, or its declared type otherwise
 * @param isUnbound whether the first functional interface parameter supplies the instance receiver,
 *     as in {@code String::length}
 * @param returnNullnessHint the return-nullness refinement from handlers or the core {@code
 *     Map.getOrDefault} rule, stored separately from {@code methodType}; {@link
 *     NullnessHint#UNKNOWN} when no refinement is available
 */
public record ResolvedMethodReference(
    Type.MethodType methodType,
    @Nullable Type qualifierType,
    Symbol.MethodSymbol functionalInterfaceMethod,
    Type.MethodType functionalInterfaceMethodType,
    boolean isUnbound,
    NullnessHint returnNullnessHint) {}
