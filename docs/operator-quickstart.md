# Operator Quickstart

## Who this is for

This quickstart is for exchange operators and compliance teams evaluating or deploying the Market Administration Governor. If you're running a licensed exchange or starting one, this walks you through running the code locally, understanding the governor contract, and tracing the safeguards that prevent listing admission and trade-halt lift without human sign-off.

## Prerequisites

- Clojure CLI tools (`clojure` command)
- JVM 11+ (or GraalVM for native compilation)
- For monorepo development: workspace checkout with sibling repos (`langgraph`, `langchain`)

For standalone fork: `deps.edn` defaults to GitHub coordinates; local monorepo paths are workspace-only.

## Run tests

```bash
clojure -M:dev:test
```

This runs the complete test suite covering:
- Governor contract (4 HARD checks + soft gates)
- Phase invariants (phases 0→3, auto-eligibility rules)
- Store parity (MemStore ‖ DatomicStore)
- Registry conformance (listing-standard-met?, minimum market cap)
- Facts coverage (jurisdiction exchange-registration/listing-rule catalog)
- Actuation guards (listing admission and trade-halt lift never auto-eligible)

## Run the demo

```bash
clojure -M:dev:run
```

This drives the `OperationActor` through:
- Two clean lifecycles (listing admission + trade-halt lift)
- Five HARD-hold cases (spec-basis violation, evidence incomplete, below minimum market cap, unresolved surveillance flag, already admitted)

Output shows each step, the governor's decision, and audit ledger entries.

## Where the Governor lives

The **Market Administration Governor** is implemented in `src/marketadmin/governor.cljc`, lines 1–250 (approximately).

Key entry points:
- `gate` function: core guard logic (spec-basis, evidence-incomplete, listing-standard-not-met, surveillance-flag-unresolved, halt-not-active, already-admitted, confidence/actuation)
- `admit-listing` and `lift-halt`: high-stakes actuation gates
- See docstring for full contract and hard-hold conditions

Other core namespaces:
- `src/marketadmin/registry.cljc` — listing-admission/halt-lift records, `minimum-market-cap`, `listing-standard-met?`
- `src/marketadmin/store.cljc` — append-only ledger and listing store
- `src/marketadmin/phase.cljc` — phase 0→3, auto-eligibility rules
- `src/marketadmin/marketopsllm.cljc` — MarketOps-LLM advisor (mock + LLM modes)
- `src/marketadmin/operation.cljc` — StateGraph actor orchestration

## Linting

```bash
clojure -M:lint
```

Runs `clj-kondo` (errors fail, CI mirrors this).

## Next steps

1. Read `docs/business-model.md` for customer, offer, and revenue model
2. Review `docs/operator-guide.md` for first-deployment checklist
3. See `README.md` for full scope and architecture
4. Check `docs/adr/0001-architecture.md` for detailed design decisions
