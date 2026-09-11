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
            [marketadmin.kernels.gate :as gate]
            [marketadmin.registry :as registry]
            [marketadmin.store :as store]))

(def confidence-floor
  "Documented threshold. The DECIDING copy is
  `marketadmin.kernels.gate/confidence-floor-x100` (integer x100 in
  the safety kernel); this def is kept for callers/docs and pinned
  equal by `marketadmin.kernels.gate-test`."
  0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Admitting a real listing to trading and lifting a real trade halt
  are the two real-world actuation events this actor performs."
  #{:actuation/admit-listing :actuation/lift-halt})

(defn- confidence->x100
  "Host bridge (façade-side, not kernel vocabulary): scale a 0.0..1.0
  advisor confidence to the kernel's integer x100 wire code."
  [c]
  (Math/round (* 100.0 (double c))))

(defn- market-cap->int
  "Host bridge (façade-side, not kernel vocabulary): floor a market
  capitalization to the kernel's integer wire value. Floor is exact
  for a MINIMUM-threshold check against an integral constant:
  mc >= min iff floor(mc) >= min."
  [mc]
  (long (Math/floor (double mc))))

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
  threshold rather than a maximum ceiling. The DECIDING comparison is
  in-kernel (`gate/standard-below-minimum` against
  `gate/minimum-market-cap`, pinned equal to
  `registry/minimum-market-cap` by `marketadmin.kernels.gate-test`);
  this façade keeps the human-readable evidence."
  [{:keys [op subject]} st]
  (when (= op :listing/admit)
    (let [l (store/listing st subject)]
      (when (= 1 (gate/standard-below-minimum (market-cap->int (:market-cap l 0))))
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
  (let [spec-v (spec-basis-violations request proposal)
        evid-v (evidence-incomplete-violations request st)
        std-v  (listing-standard-not-met-violations request st)
        surv-v (surveillance-flag-unresolved-violations request proposal st)
        halt-v (halt-not-active-violations request st)
        adm-v  (already-admitted-violations request st)
        hard (into [] (concat spec-v evid-v std-v surv-v halt-v adm-v))
        conf (:confidence proposal 0.0)
        stakes? (boolean (high-stakes (:stake proposal)))
        admit? (= (:op request) :listing/admit)
        mcap (if admit?
               (market-cap->int (:market-cap (store/listing st (:subject request)) 0))
               0)
        ;; The decision itself is delegated to the safety kernel
        ;; (marketadmin.kernels.gate, integer-coded fail-closed core);
        ;; this façade only gathers evidence (violation lists with
        ;; human-readable details) and maps codes back to keywords.
        ;; Kernel is stricter than the old inline logic on ONE case by
        ;; design: an out-of-range confidence (< 0 or > 1.0) now
        ;; escalates instead of counting as high confidence.
        code (gate/verdict-code (if (seq spec-v) 1 0)
                                (if (seq evid-v) 1 0)
                                (if admit? 1 0)
                                mcap
                                (if (seq surv-v) 1 0)
                                (if (seq halt-v) 1 0)
                                (if (seq adm-v) 1 0)
                                (confidence->x100 conf)
                                (if stakes? 1 0))]
    {:ok?          (= 0 code)
     :violations   hard
     :confidence   conf
     :hard?        (= 2 code)
     :escalate?    (= 1 code)
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
