package com.uber.nullaway;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import com.uber.nullaway.handlers.Handler.NullnessHint;
import org.jspecify.annotations.Nullable;

/**
 * Resolved method-reference types and return-nullness hint. In JSpecify mode the types preserve
 * nullness from the ground target type; otherwise the method types are the declared types.
 */
public record ResolvedMethodReference(
    Type.MethodType methodType,
    @Nullable Type qualifierType,
    Symbol.MethodSymbol functionalInterfaceMethod,
    Type.MethodType functionalInterfaceMethodType,
    boolean isUnbound,
    NullnessHint returnNullnessHint) {}
