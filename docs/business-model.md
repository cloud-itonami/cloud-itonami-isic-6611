# Business Model: Administration of financial markets

## Classification

- Repository: `cloud-itonami-isic-6611`
- ISIC Rev.5: `6611`
- Activity: administration of financial markets -- operating an exchange or organized trading venue, listing and market-surveillance administration
- Social impact: financial inclusion, data sovereignty, transparent audit

## Customer

- independent/community exchanges
- cooperative trading venues
- regional commodity exchanges

## Offer

- listing intake
- market-rule disclosure proposal
- trade-halt/surveillance-action proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per venue
- support: monthly retainer with SLA
- migration: import from an incumbent exchange system
- listing fee

## Trust Controls

- no listing is admitted and no trade-halt is lifted without human
  sign-off
- a fabricated jurisdiction market-rule citation, incomplete listing
  evidence, a listing whose own market capitalization falls below the
  minimum listing standard, an unresolved surveillance flag, or a
  halt-lift attempt against a listing with no active halt -- each
  forces a hold, not an override
- a listing cannot be admitted twice: a double-admission attempt is
  held off this actor's own listing facts alone, with no upstream
  comparison needed
- every intake, assessment, screening, admission and halt-lift path is
  auditable
- emergency manual override paths remain outside LLM control
