# cloud-itonami-isic-6611

Open Business Blueprint for **ISIC Rev.5 6611**: Administration of
financial markets. This repository publishes a market-administration
actor -- listing intake, surveillance screening, listing admission and
trade-halt lifting -- as an OSS business that any qualified, licensed
exchange operator can fork, deploy, run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920)).
Here it is **MarketOps-LLM ⊣ Market Administration Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> listing-review summary, normalizing intake, and checking whether a
> listing's market capitalization clears a minimum threshold -- but it
> has **no notion of which jurisdiction's exchange-registration/
> listing-rule requirements are official, no license to admit a real
> listing or lift a real trade halt, and no way to know on its own
> whether a listing carries an unresolved surveillance flag**. Letting
> it admit a listing or lift a halt directly invites fabricated
> jurisdiction citations, listings that don't actually meet the
> exchange's own listing standard, and unresolved market-manipulation
> concerns being quietly waved through -- and liability for whoever
> runs it. This project seals the MarketOps-LLM into a single node and
> wraps it with an independent **Market Administration Governor**, a
> human **approval workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers listing intake through surveillance screening,
listing admission and trade-halt lifting. It does **not**, by itself,
hold a license to operate an exchange in any jurisdiction, and it does
not claim to. It also does **not** model a full listing review -- no
public-float/shareholder-count analysis, no corporate-governance
standards review, no operating-history requirement (see `marketadmin.
registry/minimum-market-cap`'s own docstring for the honest
simplification this makes: a single representative market-
capitalization threshold, not a full listing standard). Whoever
deploys and operates a live instance (a licensed exchange operator)
supplies the jurisdiction-specific license, the real market-
surveillance expertise and the real trading/matching-engine
integrations, and bears that jurisdiction's liability -- the software
supplies the governed, spec-cited, audited execution scaffold so that
operator does not have to build the compliance layer from scratch for
every new market.

### Actuation

**Admitting a real listing to trading and lifting a real trade halt
are never autonomous, at any phase, by construction.** Two independent
layers enforce this (`marketadmin.governor`'s `:actuation/admit-
listing`/`:actuation/lift-halt` high-stakes gate and `marketadmin.
phase`'s phase table, which never puts `:listing/admit`/`:trade-halt/
lift` in any phase's `:auto` set) -- see `marketadmin.phase`'s
docstring and `test/marketadmin/phase_test.clj`'s `listing-admit-
never-auto-at-any-phase`/`trade-halt-lift-never-auto-at-any-phase`.
The actor may draft, check and recommend; a human exchange
administrator is always the one who actually admits a listing or lifts
a halt. Like `6512`/`6622`/`6520`/`6530`/`6820`/`6920`, this actor has
TWO actuation events.

## The core contract

```
listing intake + jurisdiction facts (marketadmin.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ MarketOps-   │ ─────────────▶ │ Market Administration      │  (independent system)
   │ LLM (sealed) │  + citations    │ Governor: spec-basis ·      │
   └──────────────┘                 │ evidence-incomplete ·        │
                             commit ◀────┼──────────▶ hold │ listing-standard-not-met
                                 │             │           │ (MINIMUM threshold, not a
                           record + ledger  escalate ─▶ human   maximum cap) ·
                                             (ALWAYS for         surveillance-flag-unresolved ·
                                              :listing/admit /    halt-not-active · already-admitted
                                              :trade-halt/lift)
```

**The MarketOps-LLM never admits a listing or lifts a trade halt the
Market Administration Governor would reject, and never does so without
a human sign-off.** Hard violations (fabricated jurisdiction
requirements; unsupported listing evidence; a listing whose own market
capitalization falls below the minimum listing standard; an unresolved
surveillance flag; a halt-lift attempt against a listing with no
active halt; a double admission) force **hold** and *cannot* be
approved past; a clean admission/lift proposal still always routes to
a human.

## Run

```bash
clojure -M:dev:run     # walk two clean lifecycles (listing admission, trade-halt lift) + five HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a facility-access control
robot manages physical trading-floor access where one exists, under the
actor, gated by the independent **Market Administration Governor**. The
governor never dispatches hardware itself; `:high`/`:safety-critical`
actions require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Market Administration Governor, listing-admission + halt-lift draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6611`). Related capability contracts (position/trade/fund-NAV/mandate
shapes) are published as [`kotoba-lang/securities`](https://github.com/kotoba-lang/securities);
this actor's `marketadmin.*` namespaces are a self-contained governed
implementation -- it does not require the capability lib directly, the
same "self-contained sibling" relationship every prior actor has toward
its own capability lib.

## Layout

| File | Role |
|---|---|
| `src/marketadmin/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + listing-admission/halt-lift history. No dynamically-filed sub-record -- both actuation ops act directly on a pre-seeded listing; an active halt lives inline on the listing, and lifting it clears the field (doubling as the double-lift guard) |
| `src/marketadmin/registry.cljc` | Listing-admission + halt-lift draft records, plus `minimum-market-cap`/`listing-standard-met?` -- the FIRST check in this fleet to enforce a MINIMUM threshold rather than a maximum ceiling |
| `src/marketadmin/facts.cljc` | Per-jurisdiction exchange-registration/listing-rule catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/marketadmin/marketopsllm.cljc` | **MarketOps-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/surveillance-screening/admission/halt-lift proposals |
| `src/marketadmin/governor.cljc` | **Market Administration Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · listing-standard-not-met, pure ground-truth MINIMUM-threshold recompute · surveillance-flag-unresolved, unconditional evaluation) + halt-not-active/already-admitted guards + 1 soft (confidence/actuation gate) |
| `src/marketadmin/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (admission/lift always human; listing intake is the ONLY auto-eligible op, no capital risk) |
| `src/marketadmin/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/marketadmin/sim.cljc` | demo driver |
| `test/marketadmin/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |
| `wasm/listing_standard.kotoba` | PoC: a WASM-compiled (`kotoba-lang/kotoba` -> `kotoba-lang/kototama`'s `actor:host` ABI) port of `marketadmin.registry/listing-standard-met?` (the MINIMUM-market-cap listing standard) -- see `wasm/README.md` for scope, the input/output ABI, and what's out of scope |

## Business-process coverage (honest)

This actor covers listing intake through surveillance screening,
listing admission and trade-halt lifting -- the core governed
lifecycle this blueprint's own `docs/business-model.md` names as its
Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Listing intake + per-jurisdiction exchange-registration/listing-rule checklisting, HARD-gated on an official spec-basis citation (`:listing/intake`/`:jurisdiction/assess`) | Public-float/shareholder-count analysis, corporate-governance standards review, operating-history requirements (see `minimum-market-cap`'s docstring) |
| Surveillance screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:surveillance/screen`) | Real trading/matching-engine integration, tax/regulatory reporting |
| Listing admission, HARD-gated on a minimum market-capitalization listing standard and a double-admission guard (`:listing/admit`) | Ongoing market surveillance / trade-pattern analysis itself |
| Trade-halt lifting, HARD-gated on the listing actually having an active halt (`:trade-halt/lift`) | |
| Immutable audit ledger for every intake/assessment/screening/admission/lift decision | |

Extending coverage is additive: add the next gate (e.g. a public-float
check) as its own governed op with its own HARD checks and tests,
following the SAME "an independent governor re-verifies against the
actor's own records before any real-world act" pattern this repo's
flagship op already establishes.

## Jurisdiction coverage (honest)

`marketadmin.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `marketadmin.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `marketadmin.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `MarketOps-LLM` + `Market Administration Governor`
run as real, tested code (see `Run` above), promoted from the
originally-published `:blueprint`-tier scaffold, modeled closely on
the eleven prior actors' architecture. See `docs/adr/0001-
architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
