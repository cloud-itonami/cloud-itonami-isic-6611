# wasm/ — kotoba-wasm deployment of the listing-standard check

`listing_standard.kotoba` is a port of `marketadmin.registry/listing-
standard-met?` + `minimum-market-cap` (the USD 4,000,000 minimum-market-
capitalization listing standard, a REAL, representative reference point
mirroring the order of magnitude of common exchange initial-listing
thresholds such as NYSE American's ~USD 4,000,000 net tangible assets and
Nasdaq Capital Market's ~USD 4,000,000-5,000,000 stockholders' equity —
see `src/marketadmin/registry.cljc`) into the minimal `.kotoba` language
subset, compiled to a real WASM module via `kotoba wasm emit`, and hosted
via `kototama.tender` (`test/wasm/listing_standard_test.clj`).

This follows the same `kotoba wasm emit` → `kototama.tender` pipeline
`cloud-itonami-isic-6492`'s `affordability.kotoba` and
`cloud-itonami-isic-6511`'s `underwriting_decision.kotoba` already proved
(ADR-2607062330 addendum 5) — the third cloud-itonami actor to port one
pure hot-path decision function this way, and (unlike 6511's multi-branch
`llm-infer`-consulting decision) the fleet's simplest case yet: a single
`>=` comparison against one baked-in constant, zero host imports, zero
branches beyond the comparison itself.

## Why the source differs from `marketadmin.registry`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` — no
`pos?`/`neg?`/`and`/`or`/`when`, unlike the broader tree-walking
interpreter). The port therefore:

- Uses a plain positional `market-cap` arg instead of `{:keys []}` map
  destructuring on a `listing` map (no maps in the wasm-compilable
  subset) — the same simplification `affordability.kotoba` and
  `underwriting_decision.kotoba` make for their own inputs.
- Drops the `(double ...)` casts on both sides of the `>=` — `market-cap`
  and `minimum-market-cap` are already exact integers (whole USD
  dollars), so, unlike `affordability.kotoba`'s 43%-ratio ceiling, this
  check needs no cross-multiplication to avoid floating point: `(>=
  market-cap 4000000)` is already exact integer comparison, matching
  `marketadmin.kernels.gate/standard-below-minimum`'s own in-subset
  restatement of the same constant (pinned equal to the registry's by
  `marketadmin.kernels.gate-test/minimum-market-cap-pinned-to-registry-
  constant`).
- No precondition guards to convert to a `0` return — `listing-standard-
  met?`'s own default (`(:market-cap listing 0)`) means the JVM original
  never throws for a missing market-cap; the wasm port similarly never
  fails, it just returns 0 for `market-cap` at or below any value under
  the threshold.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` — the
compiler only ever exports a 0-arity `main`, see `compile-wasm-expr` in
`kotoba-lang/kotoba/src/kotoba/runtime.clj`), so the real input is passed
through the guest's exported linear memory instead — the same convention
`cloud-itonami-isic-6492`'s `affordability.kotoba` and
`cloud-itonami-isic-6511`'s `underwriting_decision.kotoba` use. A host
writes one little-endian i32 value (whole USD dollars) before calling
`main()`:

| offset | field        |
|--------|--------------|
| 0      | `market-cap` |

`main()` returns `1` (listing standard met) or `0` (not met). Offset 0 is
well below `heap-base` (2048), so it never collides with anything the
compiler itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6611/wasm/listing_standard.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6611/wasm/listing_standard.wasm --json
```

Note: if `kotoba-lang/kotoba` is reached through a symlink (e.g. an
isolated agent worktree's sibling `kotoba-lang/` directory pointing at the
real checkout), `cd`-ing through the symlink lands the shell's *physical*
working directory in the real, shared checkout — so the relative paths
above resolve against the real checkout's siblings, not the worktree's.
Use absolute paths for the source/`--output` arguments in that case; the
compiler itself running from the shared, read-only `kotoba-lang/kotoba`
checkout is fine (it's a dependency, not something this port writes to).

## Fleet deployment: not attempted in this pass — see cloud-itonami-isic-6492/6511 for the established pattern.
