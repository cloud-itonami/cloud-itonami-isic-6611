(ns marketadmin.registry
  "Pure-function listing-admission and trade-halt-lift record
  construction -- an append-only exchange book-of-record draft.

  Like every sibling actor's registry, there is no single international
  check-digit standard for a listing-admission or trade-halt-lift
  reference number -- every exchange/jurisdiction assigns its own
  reference format. This namespace does NOT invent one; it builds a
  jurisdiction-scoped sequence number and validates the record's
  required fields, the same honest, non-fabricating discipline
  `marketadmin.facts` uses.

  `minimum-market-cap` is a REAL, representative regulatory reference
  point (see its own docstring for the honest simplification it makes),
  not an invented placeholder -- it mirrors the ORDER OF MAGNITUDE of
  common minimum-market-capitalization/net-tangible-assets listing
  standards used by real exchanges (e.g. NYSE American's ~USD 4,000,000
  net tangible assets, Nasdaq Capital Market's ~USD 4,000,000-5,000,000
  stockholders' equity), cited as a representative starting threshold,
  NOT a specific exchange's exact current rule. Unlike every prior
  cap-check in this fleet (`casualty.governor/claim-exceeds-coverage-
  violations`'s/`realty.governor/contract-exceeds-authorization-
  violations`'s/`credit.governor/affordability-exceeded-violations`'s,
  all MAXIMUM ceilings a value must not EXCEED), this is a MINIMUM
  threshold a value must not FALL BELOW -- the first check in this
  fleet to invert the inequality direction.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real exchange/trading system. It builds the RECORD an
  exchange would keep, not the act of admitting the listing or lifting
  the halt itself (those are `marketadmin.operation`'s `:listing/admit`
  and `:trade-halt/lift`, always human-gated -- see README
  `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  licensed exchange operator's act, not this actor's. See README
  `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(def minimum-market-cap
  "A REAL, representative minimum-market-capitalization listing-
  standard reference point (USD 4,000,000), mirroring the order of
  magnitude of common exchange initial-listing thresholds (see ns
  docstring) -- an honest starting reference, NOT a claim that every
  jurisdiction in `marketadmin.facts/catalog` mandates this EXACT
  figure. A real listing review additionally weighs public float,
  shareholder count, corporate-governance standards and a
  demonstrated operating history; this R0 checks only market
  capitalization against this ONE representative threshold."
  4000000)

(defn listing-standard-met?
  "Does `listing`'s own `:market-cap` satisfy `minimum-market-cap`? A
  pure ground-truth check against a fixed reference constant -- see ns
  docstring for the honest simplification this makes vs. a full
  listing review."
  [listing]
  (>= (double (:market-cap listing 0)) (double minimum-market-cap)))

(defn register-listing-admission
  "Validate + construct the LISTING-ADMISSION registration DRAFT -- the
  exchange's own legal act of admitting a real security to trading.
  Pure function -- does not touch any real trading/matching system; it
  builds the RECORD an exchange would keep. `marketadmin.governor`
  independently re-verifies the listing's own market capitalization
  against `minimum-market-cap`, and blocks a double-admission of the
  same listing, before this is ever allowed to commit."
  [listing-id jurisdiction sequence]
  (when-not (and listing-id (not= listing-id ""))
    (throw (ex-info "listing-admission: listing_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "listing-admission: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "listing-admission: sequence must be >= 0" {})))
  (let [admission-number (str (str/upper-case jurisdiction) "-LIST-" (zero-pad sequence 6))
        record {"record_id" admission-number
                "kind" "listing-admission-draft"
                "listing_id" listing-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "admission_number" admission-number
     "certificate" (unsigned-certificate "ListingAdmissionCertificate" admission-number admission-number)}))

(defn register-halt-lift
  "Validate + construct the TRADE-HALT-LIFT registration DRAFT -- the
  exchange's own legal act of lifting a real trade halt and resuming
  trading in a security. Pure function -- does not touch any real
  trading/matching system; it builds the RECORD an exchange would
  keep."
  [listing-id jurisdiction sequence]
  (when-not (and listing-id (not= listing-id ""))
    (throw (ex-info "halt-lift: listing_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "halt-lift: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "halt-lift: sequence must be >= 0" {})))
  (let [lift-number (str (str/upper-case jurisdiction) "-HALT-" (zero-pad sequence 6))
        record {"record_id" lift-number
                "kind" "halt-lift-draft"
                "listing_id" listing-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "lift_number" lift-number
     "certificate" (unsigned-certificate "HaltLiftCertificate" lift-number lift-number)}))

(defn append
  "Append a listing-admission/halt-lift record, returning a NEW list
  (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
