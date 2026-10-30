package com.uber.lib.generics;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/**
 * Type parameters whose bounds are intersections, used to test bounds read from bytecode. Only
 * {@code E1}, {@code E4}, and {@code E6} include {@code null}: an intersection bound is nullable
 * only when every one of its elements is.
 */
// The explicit Object bound of E3 is the point of the test case, not an oversight.
@SuppressWarnings("ExtendsObject")
public class IntersectionTypeParam<
    E1 extends @Nullable Object & @Nullable Serializable,
    E2 extends @Nullable Object & Serializable,
    E3 extends Object & @Nullable Serializable,
    E4 extends @Nullable Serializable & @Nullable CharSequence,
    E5 extends @Nullable Serializable & CharSequence,
    E6 extends @Nullable Serializable & @Nullable CharSequence & @Nullable Comparable<String>,
    E7 extends Serializable & CharSequence,
    E8 extends @Nullable Serializable & @Nullable CharSequence & Comparable<String>> {}
