NullAway deliberately trades soundness for speed, low annotation overhead, and predictable behavior in large builds. This page documents some known false negatives that come from those tradeoffs.

For background, see Section 4 of the [NullAway paper](https://manu.sridharan.net/files/FSE19NullAway.pdf) and the [[How NullAway Works]] page. This page covers the main deliberate sources of unsoundness currently documented in the codebase and issue tracker.

## Zero-argument method calls are assumed pure and deterministic

NullAway assumes that repeated calls to the same zero-argument method always return the same value and have no side effects.

For example:

```java
if (rental.returnPolicy() == null
    || rental.returnPolicy().mustBeReturnedAfter() == null) {
  return 0;
}
```

NullAway does not warn on the dereference of the second `rental.returnPolicy()` call, even though the method's return value could change between calls — for example, if the backing field was mutated by another call in between. This behavior is deliberate; it keeps common getter-heavy code concise and avoids forcing users to introduce locals everywhere just to satisfy the checker.

If you want to avoid this false negative in your own code, store the value in a local first:

```java
ReturnPolicy policy = rental.returnPolicy();
if (policy == null || policy.mustBeReturnedAfter() == null) {
  return 0;
}
```

## Nullness facts are not invalidated after variable reassignment

When a variable is reassigned, NullAway does not re-check nullness facts that were learned before the reassignment.

For example:

```java
if (m.containsKey(o)) {
  m = new HashMap();
  m.get(o).toString();
}
```

In this case, NullAway may continue using the fact learned from `m.containsKey(o)` even after `m` has been reassigned. This is another deliberate tradeoff in favor of performance and implementation simplicity.

## Map lookups are treated optimistically after key checks

As described on the [[Maps]] page, NullAway treats `m.get(k)` as `@NonNull` after checks like `m.containsKey(k)` or after a non-null `put(...)`, even though Java maps can legally store `null` values.

This is also unsound:

```java
if (m.containsKey(key)) {
  m.get(key).toString();
}
```

If `m` stores a `null` value for `key`, the dereference is still unsafe at runtime. NullAway deliberately ignores this possibility because many codebases treat map presence as implying a meaningful non-null value, and warning on every such usage would make the checker substantially noisier.

If your code relies on maps that may contain `null` values, prefer an explicit local check on `m.get(key)` instead of `containsKey(key)`.

## Array element reads are assumed `@NonNull` outside JSpecify mode

Outside JSpecify mode, NullAway cannot represent nullable array element types and unsoundly treats all array element reads as `@NonNull`.

For example:

```java
String[] arr = {"hello", null};
arr[1].toString(); // NullAway does not warn even though arr[1] is null
```

JSpecify mode adds support for nullable array element type annotations, enabling more precise analysis. If array contents are an important source of nullability bugs in your codebase, prefer enabling JSpecify mode and using explicit nullable type annotations on array element types where appropriate.

## When you need a sounder checker

If these tradeoffs are not acceptable for your use case, use a checker with a stronger soundness story, such as the [Checker Framework Nullness Checker](https://checkerframework.org/manual/#nullness-checker).
