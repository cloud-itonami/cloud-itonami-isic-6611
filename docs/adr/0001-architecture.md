# ADR-0001: cloud-itonami-isic-6611 -- MarketOps-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612`/`6492`/`6920` ADR-0001s (the pattern this ADR
  ports); ADR-2607071250/ADR-2607071320/ADR-2607071351 (`6612`/`6492`/
  `6920`, the first three verticals built outside ADR-2607032000's
  original insurance/real-estate batch -- this is the fourth)
- Context: Continuing the standing "pick a new ISIC blueprint vertical"
  direction past `6612`/`6492`/`6920`, this ADR deepens `cloud-itonami-
  isic-6611` (administration of financial markets) from `:blueprint` to
  `:implemented`, the twelfth actor in this fleet -- a return to the
  finance division (64/66) after `6920`'s excursion into professional
  services.

## Problem

Exchange/market administration bundles several distinct concerns under
one governed workflow:

1. **Jurisdiction exchange-registration/listing-rule correctness** --
   is the required evidence for admitting a listing based on an
   official regulator, or invented?
2. **Listing-standard correctness** -- does a listing candidate's own
   market capitalization actually satisfy a minimum listing standard?
   A pure ground-truth recompute (the SAME shape `credit.governor/
   affordability-exceeded-violations`/`accounting.governor/trial-
   balance-out-of-balance-violations` establish), but the FIRST check
   in this fleet to enforce a MINIMUM threshold (must not fall below)
   rather than every prior cap-check's MAXIMUM ceiling (must not
   exceed).
3. **Surveillance-flag resolution** -- does a listing carry an
   unresolved surveillance flag (a market-manipulation investigation,
   etc.)? Reuses the unconditional-evaluation discipline for a further
   application in this fleet.
4. **Real actuation, twice** -- admitting a real listing and lifting a
   real trade halt are both irreversible acts investors will rely on.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run exchange administration with an LLM" but
"seal the LLM inside a trust boundary and layer evidence-sufficiency,
listing-standard correctness, surveillance-flag resolution, audit and
human-approval on top of it, while structurally fixing both real
actuation events as human-only."

## Decision

### 1. MarketOps-LLM is sealed into the bottom node; it never admits or lifts directly

`marketadmin.marketopsllm` returns exactly five kinds of proposal:
intake normalization, jurisdiction exchange-registration checklist,
surveillance screening, listing-admission draft, and halt-lift draft.
No proposal writes the SSoT or commits a real listing admission / halt
lift directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 market-administration operation

`marketadmin.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim.

### 3. `listing-standard-not-met-violations` is the FIRST minimum-threshold check in this fleet

`marketadmin.registry/minimum-market-cap`/`listing-standard-met?`
independently recompute whether a listing's own market capitalization
clears a fixed reference threshold -- the SAME "pure ground-truth
recompute, no proposal inspection, no stored-verdict lookup" shape
`credit.governor`'s/`accounting.governor`'s checks establish. But where
every prior cap-check in this fleet (`casualty.governor/claim-exceeds-
coverage-violations`, `realty.governor/contract-exceeds-authorization-
violations`, `credit.governor/affordability-exceeded-violations`) is a
MAXIMUM ceiling a value must not exceed, this is a MINIMUM a value
must not fall below -- the first inversion of the inequality direction
in this fleet's shared check vocabulary.

### 4. An active halt lives inline on the listing, mirroring `realty`'s pending-contract pattern

Like `realty.store`'s pending contract, an active trade halt is a
field on the listing itself (`:halt-active?`/`:halt-reason`), not a
separate collection -- a listing has at most ONE active halt at a
time. Lifting it clears the field, which doubles as the double-lift
guard (a repeat lift attempt falls into the SAME `halt-not-active`
check a never-halted listing would also trigger).

### 5. Double-admission guard checks a dedicated boolean, not `:status`

`already-admitted-violations` checks `:admitted?`, a dedicated boolean
set once and never cleared, rather than a `:status` value that could
legitimately advance past a checked state -- the SAME design choice
`accounting.governor`'s guards make, informed by `cloud-itonami-isic-
6492`'s status-lifecycle bug rather than reused by direct analogy.

### 6. Dual actuation events

`marketadmin.governor`'s `high-stakes` set has two members
(`:actuation/admit-listing` and `:actuation/lift-halt`), matching
`6512`'s/`6622`'s/`6520`'s/`6530`'s/`6820`'s/`6920`'s dual-actuation
shape -- this domain genuinely has two distinct real-world legal acts.

### 7. No fabricated international listing/halt-lift-number standard

Same discipline as every sibling's registry: there is no single
international check-digit standard for a listing-admission or trade-
halt-lift reference number. `marketadmin.registry` therefore does not
invent one; it validates required fields and assigns a jurisdiction-
scoped sequence number only.

### 8. No real bug this time -- both established lessons applied correctly from the first draft AND first demo run

Following `6920`'s guard-gap lesson (defensive field access for type-
specific data), `listing-standard-met?` defaults `:market-cap` to `0`
when absent, avoiding any analogous crash risk. The demo and full test
suite passed clean on the FIRST attempt.

### 9. Relationship to `kotoba-lang/securities`

Shares `kotoba-lang/securities` (position/trade/fund-NAV/mandate
contracts) with `cloud-itonami-isic-6612` (securities brokerage) -- but
the same self-contained-sibling posture holds: no code dependency.

## Consequences

- (+) Exchange/market administration gets the same governed,
  auditable-actor treatment as the eleven prior actors -- any licensed
  exchange operator can fork and run their own instance.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/marketadmin/phase_test.clj`'s `listing-
  admit-never-auto-at-any-phase`/`trade-halt-lift-never-auto-at-any-
  phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/
  marketadmin/store_contract_test.clj`, the same `:db-api`-driven swap
  pattern every sibling actor uses.
- (+) `listing-standard-not-met-violations` is a genuine structural
  contribution: the first MINIMUM-threshold check in this fleet's
  shared vocabulary of governor-check shapes, proving the pure-ground-
  truth-recompute family generalizes to both inequality directions.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `marketadmin.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `minimum-market-cap` models only a single representative
  threshold, not a full listing review (public-float/shareholder-count
  analysis, corporate-governance standards, operating-history
  requirements are out of scope -- see that constant's own docstring);
  real trading/matching-engine integration and ongoing market
  surveillance are all out of scope for this OSS actor -- each
  operator's responsibility (see README's coverage table).
- 37 tests / 171 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Keep `cloud-itonami-isic-6611` at `:blueprint` only | ❌ | The standing direction continues past `6612`/`6492`/`6920`; exchange administration is a natural, well-precedented next finance-adjacent domain |
| Model a full listing review (public float, shareholder count, corporate governance) for conformance-test rigor | ❌ | Genuinely more complex real-world listing standards that this R0 does not claim to model correctly -- honestly scoped to a single representative market-cap threshold instead, same as every sibling's "starting catalog, not exhaustive" posture |
| Add a separate collection for trade halts, for consistency with `pension`'s/`brokerage`'s sub-record actors | ❌ | A listing has at most one active halt at a time; a separate collection would be an unused indirection, the same "no premature abstraction" judgment `realty`'s pending-contract design already made |
| Require `kotoba.securities` (the capability lib) directly from `marketadmin.*` | ❌ | No sibling actor requires its capability lib directly; keeping the actor self-contained matches the established pattern |
