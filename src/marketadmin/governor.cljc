(ns marketadmin.governor
  "Market Administration Governor -- the independent compliance layer
  that earns the MarketOps-LLM the right to commit. The LLM has no
  notion of jurisdictional exchange-registration/listing-rule law,
  whether a listing's own market capitalization actually satisfies a
  minimum listing standard, whether a listing carries an unresolved
  surveillance flag, whether a listing actually has an active trade
  halt to lift, or when an act stops being a draft and becomes a real-
  world listing admission or trade-halt lift, so this MUST be a
  separate system able to *reject* a proposal and fall back to HOLD --
  the market-administration analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Four checks, in priority order, ALL HARD violations: a human approver
  CANNOT override them (you don't get to approve your way past a
  fabricated jurisdiction spec-basis, incomplete listing evidence, a
  listing whose own market capitalization falls below the minimum
  listing standard, an unresolved surveillance flag, or lifting a halt
  that was never active). The confidence/actuation gate is SOFT: it
  asks a human to look (low confidence / actuation), and the human may
  approve -- but see `marketadmin.phase`: for `:stake :actuation/admit-
  listing`/`:actuation/lift-halt` (a real listing admission or a real
  trade-halt lift) NO phase ever allows auto-commit either. Two
  independent layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`marketadmin.
                                       facts`), or invent one? Like
                                       `credit.governor`'s/`accounting.
                                       governor`'s actuation ops, both
                                       act directly on a pre-seeded
                                       listing (see `marketadmin.
                                       store`'s own docstring) -- there
                                       is no 'listing is missing'
                                       failure mode to guard against
                                       here.
    2. Evidence incomplete         -- for `:listing/admit`, are the
                                       jurisdiction's required listing-
                                       application/market-rule-
                                       disclosure/surveillance-
                                       certification docs actually
                                       satisfied?
    3. Listing standard not met    -- for `:listing/admit`, does the
                                       listing's OWN market
                                       capitalization actually satisfy
                                       `marketadmin.registry/minimum-
                                       market-cap`? A pure ground-truth
                                       recompute needing no proposal
                                       inspection or stored-verdict
                                       lookup at all, the SAME shape
                                       `credit.governor/affordability-
                                       exceeded-violations`/`accounting.
                                       governor/trial-balance-out-of-
                                       balance-violations` establish --
                                       but this is the FIRST check in
                                       this fleet to check a MINIMUM
                                       threshold (must not fall BELOW)
                                       rather than a MAXIMUM ceiling
                                       (must not EXCEED).
    4. Surveillance flag
       unresolved                    -- does THIS proposal itself
                                       report an open surveillance flag
                                       (a `:surveillance/screen` that
                                       just found one), or does the
                                       listing already carry one on
                                       file? Evaluated UNCONDITIONALLY
                                       (not scoped to a specific op),
                                       the SAME discipline `casualty.
                                       governor/sanctions-violations`
                                       established, reused here for
                                       BOTH `:listing/admit` and
                                       `:trade-halt/lift` -- an
                                       unresolved surveillance flag
                                       blocks both real-world acts this
                                       actor performs.
    5. Halt not active              -- for `:trade-halt/lift`, does the
                                       listing actually have an active
                                       trade halt (`:halt-active?
                                       true`)? Doubles as the double-
                                       lift guard -- see `marketadmin.
                                       store`'s own docstring for why
                                       clearing `:halt-active?` on lift
                                       makes a repeat attempt fall into
                                       this SAME check.
    6. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:listing/admit`/
                                       `:trade-halt/lift` (REAL legal
                                       acts) -> escalate.

  One more guard, double-admission prevention, is enforced but NOT
  listed as a numbered HARD check above because it needs no upstream
  comparison at all -- `already-admitted-violations` refuses to admit
  the SAME listing twice, off a dedicated `:admitted?` fact (never a
  `:status` value) -- the SAME 'check a dedicated boolean, not status'
  discipline `accounting.governor`'s guards establish, informed by
  `cloud-itonami-isic-6492`'s status-lifecycle bug."
  (:require [marketadmin.facts :as facts]
            [marketadmin.registry :as registry]
            [marketadmin.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Admitting a real listing to trading and lifting a real trade halt
  are the two real-world actuation events this actor performs."
  #{:actuation/admit-listing :actuation/lift-halt})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:listing/admit`) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's exchange-registration/listing-rule requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :listing/admit} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:listing/admit`, the jurisdiction's required listing-
  application/market-rule-disclosure/surveillance-certification
  evidence must actually be satisfied -- do not trust the advisor's
  self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (= op :listing/admit)
    (let [l (store/listing st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction l) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(上場申請書/市場規則開示書面等)が充足していない状態での上場提案"}]))))

(defn- listing-standard-not-met-violations
  "For `:listing/admit`, INDEPENDENTLY recompute whether the listing's
  own market capitalization satisfies `marketadmin.registry/minimum-
  market-cap` -- needs no proposal inspection or stored-verdict lookup
  at all, since its input is a permanent ground-truth field already on
  the listing. The FIRST check in this fleet to enforce a MINIMUM
  threshold rather than a maximum ceiling."
  [{:keys [op subject]} st]
  (when (= op :listing/admit)
    (let [l (store/listing st subject)]
      (when-not (registry/listing-standard-met? l)
        [{:rule :listing-standard-not-met
          :detail (str subject " の時価総額(" (:market-cap l)
                      ")が上場基準の最低額(" registry/minimum-market-cap ")を下回っている")}]))))

(defn- surveillance-flag-unresolved-violations
  "An unresolved surveillance flag -- reported by THIS proposal (e.g. a
  `:surveillance/screen` that itself just found one), or already on
  file in the store for the listing (`:listing/admit`/`:trade-halt/
  lift`) -- is a HARD, un-overridable hold. Evaluated UNCONDITIONALLY
  (not scoped to a specific op) so the screening op itself can HARD-
  hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :open (get-in proposal [:value :verdict]))
        listing-id (when (contains? #{:surveillance/screen :listing/admit :trade-halt/lift} op) subject)
        hit-on-file? (and listing-id (= :open (:verdict (store/surveillance-of st listing-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :surveillance-flag-unresolved
        :detail "未解決の審査フラグのある銘柄を含む提案は進められない"}])))

(defn- halt-not-active-violations
  "For `:trade-halt/lift`, the listing must actually have an active
  trade halt (`:halt-active? true`) -- refuses to lift a halt that was
  never active. Doubles as the double-lift guard: `marketadmin.store/
  lift-halt!` clears `:halt-active?` to false on lift, so a repeat
  attempt falls into this SAME check."
  [{:keys [op subject]} st]
  (when (= op :trade-halt/lift)
    (when-not (:halt-active? (store/listing st subject))
      [{:rule :halt-not-active
        :detail (str subject " には現在有効な取引停止措置が無い")}])))

(defn- already-admitted-violations
  "For `:listing/admit`, refuses to admit the SAME listing twice, off a
  dedicated `:admitted?` fact (never a `:status` value) -- see ns
  docstring for why this sidesteps the status-lifecycle risk `cloud-
  itonami-isic-6492`'s ADR-0001 documents."
  [{:keys [op subject]} st]
  (when (= op :listing/admit)
    (when (store/listing-already-admitted? st subject)
      [{:rule :already-admitted
        :detail (str subject " は既に上場承認済み")}])))

(defn check
  "Censors a MarketOps-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (listing-standard-not-met-violations request st)
                           (surveillance-flag-unresolved-violations request proposal st)
                           (halt-not-active-violations request st)
                           (already-admitted-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
