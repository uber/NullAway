# Track call-specific full-type bounds on top of `issue-1919`

## Summary and base branch

Use `issue-1919` as the parent of a new `issue-1932` branch. The local branch and local remote-tracking ref both point to `2490d1e3`; verify the parent tip again when implementation begins.

This branch already supplies important groundwork for [#1932](https://github.com/uber/NullAway/issues/1932):

- Implicit lambda parameters receive target-derived types during constraint generation.
- Nested lambda parameter environments are saved and restored in `finally`.
- Inference results are not cached while provisional lambda parameter types are active.
- Block-body scanning registers `var` locals before generating return constraints.
- Tests cover implicit parameters, local copies, nested inference, and a generic call in a lambda's `var` initializer.

Build on these mechanisms instead of independently implementing them again. The branch leaves the solver and substitution utilities unchanged, so call-specific identities and full-type information must still be added to inference.

Success requires fixing [#1291](https://github.com/uber/NullAway/issues/1291) and the deeper `flatMap` example in [#1585](https://github.com/uber/NullAway/issues/1585), retaining the #1919 behavior, and removing nested annotation repairs after regression gates pass.

Agreed decisions remain:

- Javac determines Java type shapes. NullAway retains authoritative annotated bounds, propagates their dependencies, and checks that a complete substitution satisfies them.
- Keep the existing top-level nullness solver and extend it to reconcile full-type bounds. Do not start by introducing a separate inference variable for every nested nullness position.
- Preservation and consistency checking belong in the solver together. Recording a type from one argument is insufficient unless the resulting substitution satisfies every relevant constraint.
- Deliver incremental stages, removing repairs last.
- Preserve existing warning controls and reporting behavior.
- Replace the current implementation within existing JSpecify modes, without a new flag.

## Implementation stages

### 0. Establish the branch baseline

Create the child branch from the current `issue-1919` tip without rewriting the parent branch or reusing the existing detached exploration worktree.

Run the #1919 test class first, then the main module tests and self-check. Record failures and timing before changing the solver.

The parent branch is currently six commits ahead of its common ancestor with `master`, while `master` has nine commits absent from the parent. Keep branch synchronization separate from solver changes. When #1919 lands, rebase the child onto the updated `master` and rerun acceptance checks.

### 1. Separate inference variables by site

Introduce:

```java
record InferenceVariable(Element typeVariable, ExpressionTree site) {}
```

Support method invocations, diamond constructor calls, and generic method references. Use AST node identity for the site.

- Change registration, variable-state maps, graph edges, work queues, and solutions to use this key.
- Retain scalar nullability values for this stage to isolate the identity change.
- Register only variables inferred at the corresponding site. Receiver parameters, caller variables, and explicit type witnesses remain fixed.
- Preserve deterministic insertion order and existing annotation-override behavior.

Give each constraint operand its own inference scope. An inner call's return and an outer call's formal parameter belong to different sites, even when both methods declare a parameter named `T`.

Bind variable occurrences before traversing types, and retain those identities through nested arguments, arrays, wildcards, and functional-interface substitutions. Do not infer ownership afterward from `Element` alone.

Extend the #1919 provisional parameter environment to retain the owning inference scope along with each parameter's type. Nested lambda environments must preserve outer bindings and restore them on both success and failure.

Update caches and substitution consumers to select results by site. A solution shared by several calls must never apply one call's substitution to another occurrence of the same declaration parameter.

**Gate:** #1291 and its negative variants pass, along with the parent branch's nested-lambda tests and existing diagnostic tests.

### 2. Retain and reconcile full-type bounds

Introduce a per-problem inference session containing site registrations, javac-derived shapes, scoped annotated bounds, dependency relationships, and provisional lambda environments.

The motivating distinction is that a full substitution can carry already-known nested facts. For the deeper #1585 example on top of #1919:

```text
lambda parameter: Box<Box<@Nullable String>>
lambda return requirement: Box<R>
matching the invariant Box arguments gives R = Box<@Nullable String>
```

The inner annotation is supplied by the target-derived parameter type. The solver must retain it and reconcile it with other requirements on R; it need not invent a new unknown for that String position.

Change the final solver contract to:

```java
void registerInferenceVariable(
    InferenceVariable variable, Type javacInstantiation);

void addSubtypeConstraint(
    ScopedType subtype,
    ScopedType supertype,
    boolean localVariableType);

Map<InferenceVariable, Type> solve();
```

`ScopedType` is an internal view of an annotated javac `Type` with bindings identifying the call-specific inference variables occurring in it. Preserve these bindings through substitution, including types containing variables from multiple sites. This is ownership metadata, not a graph of nested nullness variables. Keep source-expression provenance on recorded constraints for diagnostics.

Keep scalar nullness state internally for top-level solving. Remove the public `InferredNullability` result representation after consumers migrate to complete `Type` substitutions.

**Recover shapes from javac attribution**

Match the receiver-substituted declaration signature against the instantiated call signature, recursively covering parameters, returns, nested arguments, enclosing types, and arrays.

For diamonds, also match the generic class declaration against the constructed type, excluding synthetic anonymous-class types. For generic references, use the attributed reference signature and functional-interface adaptation.

Use existing annotation-aware `asSuper` and `memberType` utilities to align inherited views. Check that repeated occurrences produce consistent shapes after ignoring nullness. Derive unused variables from substituted bounds without affecting observable call types.

Use a subtype's corresponding supertype view when its nominal type differs from javac's inferred shape. If alignment still fails, do not choose a different Java shape; retain the existing checking fallback and do not publish a purported full solution.

**Record structured bounds at inference-variable boundaries**

Extend each call-specific variable's state with its javac shape, annotated lower and upper bounds, existing scalar nullness state, and dependent constraints. For example:

```text
Box<@Nullable String> <: alpha
alpha <: beta
beta <: Box<@Nullable String>
```

When traversal reaches an inference variable, retain the opposing annotated type and the relation rather than reducing the information to top-level nullness alone. Concrete portions of a bound are evidence; unresolved method variables remain scoped references. Keep lower bounds, upper bounds, and equality requirements distinct.

**Propagate and reconcile the bounds**

Reuse the existing work-list propagation for top-level nullness. Extend the work list to revisit dependent full-type constraints when substitutions or their available annotated evidence change.

- Substitute available scoped bindings into bounds and decompose the resulting type relations recursively, using javac shapes and the existing annotation-aware alignment utilities.
- Reconcile all relevant bounds before returning a solution. For invariant concrete generic arguments, require compatible nested facts in both directions; do not pick the first argument's annotations and ignore the rest.
- Merge compatible covariant requirements structurally, within javac's fixed shape. For a reference position, a nullable lower bound requires a nullable result; a non-null upper requirement prohibits one. Apply these rules at array component/dimension positions where covariance permits them. Invariant positions instead require agreement.
- Preserve current array covariance, wildcard containment rules, and wildcard feature gates.
- Include declaration upper bounds and handler-provided models.
- Use NullAway expression types, target-derived lambda parameters, modeled declarations, and assignment contexts as annotation evidence. Missing annotations in javac's inferred substitution are not proof that an authoritative nullable fact is absent.
- Treat explicit `@Nullable T` and `@NonNull T` uses as root overrides while retaining the substituted type's nested structure.
- Keep fixed type variables symbolic rather than treating their bounds as inferred concrete nullness.
- Delay defaults until propagation has reached a fixed point. Preserve the existing non-null default for unconstrained method-variable roots; retain declaration/model semantics for nested parts without additional evidence.
- Preserve the current local-assignment root exception; `var` declarations provide no target type.

For the existing `issue1455` negative case, the javac shape is `T = Supplier<OuterT>` and the actual arguments supply both `Supplier<@Nullable OuterT> <: T` and `Supplier<OuterT> <: T`. Given the fixed shape and invariant arguments, these requirements conflict. Detect that conflict within bound reconciliation while retaining the ordinary incompatible-type diagnostic expected by the test.

Validate every recorded bound against the final complete substitutions. A candidate assembled from known facts is successful only if all its constraints hold. Covariant differences must not be mistaken for invariant contradictions, and nullable annotations must not be unioned indiscriminately across generic arguments.

Javac's annotations within inferred substitutions are not authoritative. Authoritative non-null facts must also remove misplaced nullable annotations, as required by the Caffeine repair case.

Deduplicate bounds and dependency work, detect cycles during structural comparison, and revisit constraints only when evidence changes. Do not finalize an unresolved dependency by substituting a non-null default early. Reconstruct detached javac types from reconciled bounds and scalar results, preserving nominal shapes and unrelated metadata.

This stage uses recursive reconciliation of annotated bounds, not an eagerly allocated per-position nullness graph. If a concrete acceptance test requires a richer representation, first isolate the failing case and document why bound propagation and structural merging cannot express it before expanding the design.

### 3. Integrate full substitutions with #1919's lambda and cache handling

Retain the parent branch's target-derived implicit parameter handling and shallow return/`var` scanner.

Upgrade provisional parameter values to scoped annotated types that can reference unsolved variables. While inspecting lambda bodies:

- Resolve parameter identifiers and member/array projections through the active environment.
- Preserve full-type bound dependencies through `var` copies and generic initializer calls.
- Generate constraints for expression bodies and applicable block returns.
- Do not finalize unresolved substitutions before all constraints are gathered and propagated.
- Restore enclosing environments through the existing `finally` lifecycle.

Publish final functional-interface types after successful solving, including poly expressions belonging to nested participating calls.

Replace scalar annotation overlays with complete substitution into receiver-substituted, modeled declaration types. Apply the same substitution consistently to parameters, returns, thrown types, constructed types, functional-interface targets, and inferred locals.

Explicit use-site annotations continue taking precedence at the substituted root.

Preserve and extend `okToCacheInferenceResult`:

- Never persist results derived from unfinished dataflow or provisional lambda environments.
- Audit every call, poly-expression, conditional, and local-type cache path—not only paths currently using the helper.
- During provisional inference, use session-local dependent results instead of persistent cached results.
- Publish only completed solutions and clear added persistent caches at compilation-unit cleanup.
- Replace repair-specific re-entry handling with an inference-session lifecycle.

Keep current top-level inference failure warnings, attribution, and deduplication. Retain source/site/structural-position provenance for nested conflicts. Preserve ordinary incompatible-type diagnostics for existing negative cases, and never cache partial contradictory substitutions as successful results.

**Gate:** the deeper #1585 example passes through the lambda and `var` local, while #1919's unsafe `copy.get().length()` still reports a nullable dereference.

### 4. Remove obsolete repair logic

After the full-type path passes independently of repair output:

- Delete `NestedTypeVarSubstitutionRepairVisitor`, its wrapper, and dedicated re-entry tracking.
- Remove scalar-result adapters and obsolete inference annotation overlays.
- Retain general annotation-preserving substitution, member/supertype utilities, wildcard/capture handling, and poly-expression target logic.
- Update repair-specific test comments to describe full inference.
- Add Javadoc for new non-trivial production methods.

Land each stage with passing checks; cleanup must not be required to make an earlier stage correct.

## Test plan and acceptance

| Area | Required scenarios |
|---|---|
| Parent branch | All #1919 tests, including block returns, local copies, nested lambdas, and unsafe dereferences |
| #1291 | Exact reproducer, reversed arguments, deeper nesting, and nullable selected results |
| #1585 | Exact deeper `flatMap` reproducer; expression/block bodies; `var` and explicit result declarations |
| Observable inferred types | Verify nullable innermost values remain nullable after extraction |
| Repair replacement | Existing `issue1455`, Caffeine nested-argument, and top-level annotation preservation tests |
| Consistent bounds | Multiple arguments providing identical nested facts, with argument-order-independent substitutions |
| Conflicting bounds | Incompatible invariant nested nullness from arguments or contexts, including reversed argument order |
| Covariant bounds | Compatible array differences at distinct dimensions/components; merged result satisfies every bound and retains nullable dereference errors |
| Dependent bounds | Annotated facts propagate through several call-specific variables, including facts becoming available after the dependent constraint was recorded |
| Other sites | Repeated diamonds, anonymous diamonds, generic constructors, and repeated generic references |
| Structures | Nested arrays, enclosing types, inherited generic views, wildcards, captures, and recursive bounds |
| Fixed types | Caller/receiver variables, explicit witnesses, annotated variable uses, and modeled bounds |
| Lifecycle | Nested environment restoration after failure, dataflow re-entry, cache isolation, and multiple compilation units |
| Diagnostics | Existing warning controls, file attribution, upper-bound messages, and deduplication |
| Isolation/performance | Compiler-owned types unchanged; recursive cases terminate; larger expressions avoid excessive bound/dependency growth |

Add compiler-backed solver tests using attributed javac types to inspect complete substitution values, call separation, bound satisfaction, consistency, and dependency propagation. Include solver-level contradiction tests so ordinary post-inference checks do not hide missing reconciliation.

Run targeted classes during development, followed sequentially by:

```bash
./gradlew :nullaway:test
./gradlew :nullaway:buildWithNullAway
```

Do not run other modules' tests or parallel Gradle commands locally. Use existing CI for supported JDK/Error Prone combinations and downstream compatibility. Compare targeted-test timings with the parent-branch baseline and investigate substantial regressions.

## Risks and assumptions

| Risk | Mitigation |
|---|---|
| Reimplementing #1919 loses working behavior | Preserve its parameter scoping, `finally` restoration, scanner, and tests throughout the rewrite |
| Provisional types lose outer-call ownership | Carry explicit variable identities into parameter environments, bounds, and substitutions |
| First-source selection hides contradictions or depends on argument order | Retain all bounds, reconcile by the relevant relation, and validate the final substitution against every constraint |
| Covariant differences are rejected or invariant differences are incorrectly merged | Preserve subtype/equality direction and add separate invariant-conflict and covariant-merge tests |
| Dependencies are finalized before annotated evidence arrives | Revisit dependent bounds to a fixed point; apply defaults only afterward |
| Hidden cache writes retain unresolved variables | Audit all cache paths; keep dependent results session-local |
| Javac shape recovery differs across attribution forms | Isolate recovery and test captures, varargs, references, and anonymous diamonds early |
| Missing or misplaced compiler annotations remain trusted | Separate shape recovery from annotation evidence; test removal as well as restoration |
| Shared mutable javac types corrupt later checks | Use detached reconstruction and extend existing copy-isolation tests |
| Recursive bounds or growing bound sets increase cost | Deduplicate bounds, guard cycles, and use change-driven dependency propagation |
| Bound reconciliation proves insufficient for a required case | Isolate a verified counterexample before adding nested inference variables; do not silently drop evidence or constraints |
| New nested conflicts change diagnostics | Preserve existing negative reports and review each changed expectation individually |
| Repairs are removed prematurely | Delete them only after independent regression gates pass |
| Parent branch changes or lands during implementation | Keep a child branch; synchronize separately and rerun the full acceptance suite |

Constructor-reference support and broader Java type inference remain outside scope. An eager graph of inference variables for every nested nullness position is not part of the proposed implementation.

The branch facts above were obtained by read-only inspection and must be rechecked before implementation. Only this plan document has been changed; no implementation changes or tests have been performed.
