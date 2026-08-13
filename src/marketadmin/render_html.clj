(ns marketadmin.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  This is NOT a hand-written page. Every listing, verdict, hold reason,
  ledger fact, registry draft and coverage number below is read back out
  of a REAL run of this repo's own actor stack --
  `marketadmin.operation` (langgraph StateGraph) -> `marketadmin.governor`
  -> `marketadmin.store` -- driven by `run-demo!` against
  `marketadmin.store/seed-db`. Nothing on the page is typed in by hand:
  if you change `store/demo-data`, `facts/catalog`, `registry/minimum-
  market-cap` or any governor rule, the page changes on the next
  `clojure -M:dev:render-html`.

  The scenario is an extension of this repo's own demo driver
  (`marketadmin.sim`, `clojure -M:dev:run`, run and read BEFORE writing
  this file so the ids used here are the real seeded `listing-1`..
  `listing-6`), widened to exercise ALL SIX of the governor's HARD rules
  rather than sim's five -- `:evidence-incomplete` is added by attempting
  a `:listing/admit` BEFORE the jurisdiction assessment is on file, and
  `:halt-not-active` is exercised twice: once against a listing that
  never had a halt, and once as the double-lift guard against a listing
  whose halt this same run already lifted.

  Deterministic by construction: no timestamps, no randomness, every map
  iterated in sorted or run order. Two consecutive runs are byte-
  identical.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [marketadmin.facts :as facts]
            [marketadmin.governor :as governor]
            [marketadmin.operation :as op]
            [marketadmin.phase :as phase]
            [marketadmin.registry :as registry]
            [marketadmin.store :as store]))

(def ^:private operator
  "The same injected operator context `marketadmin.sim` uses: a licensed
  exchange administrator at the fully rolled-out phase 3."
  {:actor-id "op-1" :actor-role :exchange-administrator :phase 3})

;; ----------------------------- the real run -----------------------------

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by (:actor-id operator)}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives one fresh seeded store through a scenario that reaches every
  disposition and every HARD rule this actor has.

  Returns `{:db <store> :runs [<run> ..]}` where each run keeps the
  langgraph result maps themselves, so the renderer reads the actor's
  own `:proposal` / `:verdict` / `:disposition` / `:audit` channels
  rather than a hand-copied summary.

  Clean lifecycles:
    listing-1 -- intake (phase-3 auto-commit, the ONLY auto cell in the
                 phase table), jurisdiction assessment (approved),
                 surveillance screening (approved), listing admission
                 (ALWAYS escalates -- `:actuation/admit-listing`,
                 approved -> JPN-LIST-000000)
    listing-5 -- surveillance screening (approved), trade-halt lift
                 (ALWAYS escalates -- `:actuation/lift-halt`, approved
                 -> JPN-HALT-000000)

  HARD holds (all six governor rules, none of which ever reaches a
  human -- a human approver cannot override any of them):
    :evidence-incomplete          listing-1 admission attempted before
                                  the jurisdiction assessment is on file
    :no-spec-basis                listing-2's jurisdiction (ATL) is not
                                  in `marketadmin.facts/catalog`
    :listing-standard-not-met     listing-3's own market cap (2,000,000)
                                  is below `registry/minimum-market-cap`
    :surveillance-flag-unresolved listing-4's screening finds its own
                                  open flag
    :halt-not-active              listing-6 never had an active halt
    :already-admitted             listing-1 admitted a second time
    :halt-not-active (again)      listing-5's halt was lifted earlier in
                                  THIS run -- the double-lift guard"
  []
  (let [db (store/seed-db)
        actor (op/build db)
        runs (atom [])
        step! (fn [tid label request approve?]
                (let [r (exec! actor tid request)
                      a (when approve? (approve! actor tid))]
                  (swap! runs conj {:tid tid :label label :request request
                                    :run r :approval a})
                  nil))]
    (step! "t01" "listing directory intake (normalize only)"
           {:op :listing/intake :subject "listing-1"
            :patch {:id "listing-1" :issuer "Sakura Technologies K.K."}} false)
    (step! "t02" "admission attempted before the jurisdiction assessment is on file"
           {:op :listing/admit :subject "listing-1"} false)
    (step! "t03" "jurisdiction exchange-registration / listing-rule assessment"
           {:op :jurisdiction/assess :subject "listing-1"} true)
    (step! "t04" "surveillance screening"
           {:op :surveillance/screen :subject "listing-1"} true)
    (step! "t05" "listing admission (real act -- always a human call)"
           {:op :listing/admit :subject "listing-1"} true)
    (step! "t06" "surveillance screening of an already-listed security"
           {:op :surveillance/screen :subject "listing-5"} true)
    (step! "t07" "trade-halt lift (real act -- always a human call)"
           {:op :trade-halt/lift :subject "listing-5"} true)
    (step! "t08" "assessment of a jurisdiction with no official spec-basis"
           {:op :jurisdiction/assess :subject "listing-2" :no-spec? true} false)
    (step! "t09" "jurisdiction assessment (sets up the listing-standard test)"
           {:op :jurisdiction/assess :subject "listing-3"} true)
    (step! "t10" "admission of a listing below the minimum listing standard"
           {:op :listing/admit :subject "listing-3"} false)
    (step! "t11" "surveillance screening that finds its own open flag"
           {:op :surveillance/screen :subject "listing-4"} false)
    (step! "t12" "halt lift against a listing that never had an active halt"
           {:op :trade-halt/lift :subject "listing-6"} false)
    (step! "t13" "second admission of an already-admitted listing"
           {:op :listing/admit :subject "listing-1"} false)
    (step! "t14" "second lift of a halt this run already lifted (double-lift guard)"
           {:op :trade-halt/lift :subject "listing-5"} false)
    {:db db :runs @runs}))

;; ----------------------------- run accessors -----------------------------

(defn- final-state
  "The state channel map after the run finished -- the resumed state when
  the run went through human approval, otherwise the state the run
  terminated in."
  [{:keys [run approval]}]
  (:state (or approval run)))

(defn- proposal-of [{:keys [run]}] (get-in run [:state :proposal]))
(defn- verdict-of  [{:keys [run]}] (get-in run [:state :verdict]))

(defn- audit-of [r]
  (vec (concat (get-in r [:run :state :audit] [])
               (when (:approval r) (get-in r [:approval :state :audit] [])))))

(defn- approver-in-run
  "Who actually granted approval in THIS run, read out of the actor's own
  `:audit` channel (`:approval-granted`) -- not out of the store."
  [r]
  (some (fn [f] (when (= :approval-granted (:t f)) (:by f))) (audit-of r)))

;; ----------------------------- formatting -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-name [v]
  (cond (keyword? v) (subs (str v) 1)
        (nil? v) ""
        :else (str v)))

(defn- fmt-int [n]
  (when (number? n)
    (->> (str (long n))
         reverse
         (partition-all 3)
         (map #(apply str (reverse %)))
         reverse
         (str/join ","))))

(defn- code [v] (str "<code>" (esc v) "</code>"))
(defn- td [& parts] (str "<td>" (apply str parts) "</td>"))
(defn- row [& cells] (str "        <tr>" (apply str cells) "</tr>"))
(defn- span [cls v] (str "<span class=\"" cls "\">" v "</span>"))
(defn- muted [v] (span "muted" (esc v)))
(defn- dash [] (span "muted" "&mdash;"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>"
       (apply str (map #(str "<th>" (esc %) "</th>") headers))
       "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title lede body]
  (str "  <section class=\"card\">\n"
       "    <h2>" (esc title) "</h2>\n"
       "    <p class=\"muted\">" lede "</p>\n"
       body
       "  </section>\n"))

;; ----------------------------- derived cells -----------------------------

(defn- disposition-cell [d]
  (case d
    :commit   (span "ok" "commit")
    :escalate (span "warn" "escalate")
    :hold     (span "critical" "HOLD")
    (dash)))

(defn- verdict-cell
  "Renders the governor verdict itself -- HARD / escalate / clear -- from
  the `:verdict` channel the `:govern` node wrote."
  [v]
  (cond
    (nil? v) (dash)
    (:hard? v) (span "critical" (str "HARD &middot; "
                                     (esc (str/join ", " (map #(kw-name (:rule %)) (:violations v))))))
    (:escalate? v) (span "warn" (if (:high-stakes? v) "escalate &middot; actuation" "escalate &middot; confidence"))
    :else (span "ok" "clear")))

(defn- last-fact-for [ledger id]
  (last (filter #(= id (:subject %)) ledger)))

(defn- listing-status-cell [ledger {:keys [id]}]
  (let [f (last-fact-for ledger id)]
    (cond
      (nil? f) (muted "no activity")
      (= :governor-hold (:t f))
      (span "critical" (str "HARD hold &middot; " (esc (str/join ", " (map kw-name (:basis f))))))
      (= :committed (:t f)) (span "ok" (str "committed &middot; " (code (kw-name (:op f)))))
      :else (muted (kw-name (:t f))))))

(defn- trading-cell [{:keys [admitted? halt-active? halt-reason status]}]
  (str (if admitted? (span "ok" "admitted") (muted "not admitted"))
       " &middot; " (code (kw-name status))
       (if halt-active?
         (str " &middot; " (span "critical" (str "HALTED: " (esc halt-reason))))
         "")))

;; ----------------------------- sections -----------------------------

(defn- listing-directory-section [db ledger]
  (section
   "Listing directory (SSoT after this run)"
   (str "Read back from " (code "marketadmin.store/all-listings") " after the scenario below "
        "committed. The seed set is " (code "marketadmin.store/demo-data") "; the "
        (code "admission-number") " / " (code "lift-number") " columns are the registry drafts "
        "this run actually produced.")
   (table ["Listing" "Issuer" "Security" "Market cap (USD)" "Jurisdiction"
           "Trading status" "Surveillance" "Registry ref" "Last governed op"]
          (for [l (store/all-listings db)]
            (row (td (code (:id l)))
                 (td (esc (:issuer l)))
                 (td (code (kw-name (:security-type l))))
                 (td (span (if (registry/listing-standard-met? l) "num" "num critical")
                           (esc (fmt-int (:market-cap l)))))
                 (td (code (:jurisdiction l)))
                 (td (trading-cell l))
                 (td (if (:surveillance-flag? l)
                       (span "critical" "flag open")
                       (span "ok" "no flag")))
                 (td (let [refs (remove nil? [(:admission-number l) (:lift-number l)])]
                       (if (seq refs)
                         (str/join " " (map code refs))
                         (dash))))
                 (td (listing-status-cell ledger l)))))))

(defn- phase-table-section []
  (section
   "Rollout phase table"
   (str "Read straight out of " (code "marketadmin.phase/phases") ". Note the "
        (code ":auto") " column: it has exactly ONE member at ANY phase. "
        (code ":listing/admit") " and " (code ":trade-halt/lift") " are absent from every "
        (code ":auto") " set including phase 3 &mdash; a permanent structural fact, not a "
        "rollout milestone still to come.")
   (table ["Phase" "Label" "May write" "May auto-commit when governor-clean"]
          (for [p (sort (keys phase/phases))
                :let [{:keys [label writes auto]} (get phase/phases p)]]
            (row (td (code p)
                     (when (= p phase/default-phase) (str " " (span "badge" "this run"))))
                 (td (esc label))
                 (td (if (seq writes)
                       (str/join " " (map (comp code kw-name) (sort-by str writes)))
                       (muted "nothing")))
                 (td (if (seq auto)
                       (str/join " " (map (comp code kw-name) (sort-by str auto)))
                       (muted "nothing"))))))))

(defn- op-gate-section [runs]
  (let [ops (distinct (map #(get-in % [:request :op]) runs))
        first-run-for (fn [o] (first (filter #(= o (get-in % [:request :op])) runs)))]
    (section
     "Action gate per op (Market Administration Governor + phase gate)"
     (str "The " (code ":effect") " and " (code ":stake") " columns are read off the "
          "MarketOps-LLM proposals THIS run produced (the actor's " (code ":proposal")
          " channel); the phase columns come from " (code "marketadmin.phase/phases")
          " and the actuation column from " (code "marketadmin.governor/high-stakes")
          ". Two independent layers &mdash; the phase table and the governor's "
          "high-stakes gate &mdash; agree that a real listing admission or halt lift is "
          "always a human call.")
     (table ["Op" "SSoT effect" "Stake" "Write-enabled at" "Auto at" "Always human (actuation)?"]
            (for [o ops
                  :let [p (proposal-of (first-run-for o))
                        writes (filter #(contains? (:writes (get phase/phases %)) o)
                                       (sort (keys phase/phases)))
                        autos (filter #(contains? (:auto (get phase/phases %)) o)
                                      (sort (keys phase/phases)))]]
              (row (td (code (kw-name o)))
                   (td (code (kw-name (:effect p))))
                   (td (if (:stake p) (code (kw-name (:stake p))) (dash)))
                   (td (if (seq writes) (str/join ", " (map #(str "phase " %) writes)) (muted "never")))
                   (td (if (seq autos)
                         (span "ok" (str/join ", " (map #(str "phase " %) autos)))
                         (span "warn" "never at any phase")))
                   (td (if (contains? governor/high-stakes (:stake p))
                         (span "warn" "yes &mdash; governor escalates unconditionally")
                         (muted "no")))))))))

(defn- hard-hold-section [ledger]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)]
    (section
     (str "HARD governor holds this run (" (count holds) " holds &middot; "
          (count (distinct (mapcat :basis holds))) " distinct rules)")
     (str "Every row is a " (code ":governor-hold") " fact the governor itself wrote. "
          "HARD means un-overridable: none of these ever reached the "
          (code ":request-approval") " node, so no human had the option to approve past "
          "them. The " (code "detail") " text is the governor's own, not a caption.")
     (table ["Rule" "Op" "Listing" "Advisor confidence" "Governor detail"]
            (for [h holds
                  v (:violations h)]
              (row (td (span "critical" (code (kw-name (:rule v)))))
                   (td (code (kw-name (:op h))))
                   (td (code (:subject h)))
                   (td (span "num" (esc (:confidence h))))
                   (td (esc (:detail v)))))))))

(defn- runs-section [runs]
  (section
   "Operation runs (this scenario, in order)"
   (str "One row per " (code "langgraph") " actor run. " (code "governor verdict")
        " is the " (code ":verdict") " channel written by the " (code ":govern")
        " node; " (code "final disposition") " is the " (code ":disposition")
        " channel after the phase gate and, where reached, human approval.")
   (table ["#" "Op" "Listing" "What is being attempted" "Governor verdict" "Final disposition" "Approved by"]
          (for [r runs
                :let [req (:request r)]]
            (row (td (code (:tid r)))
                 (td (code (kw-name (:op req))))
                 (td (code (:subject req)))
                 (td (esc (:label r)))
                 (td (verdict-cell (verdict-of r)))
                 (td (disposition-cell (:disposition (final-state r))))
                 (td (if-let [by (approver-in-run r)]
                       (esc by)
                       (muted "no human reached"))))))))

(defn- ledger-section [ledger]
  (section
   (str "Audit ledger (" (count ledger) " immutable facts)")
   (str "Append-only, read back from " (code "marketadmin.store/ledger") ". This is the "
        "evidence an issuer disputing an admission, or a regulator auditing a halt lift, "
        "would be handed. Note what is NOT here: the " (code ":approval-granted")
        " fact lives only in the run's " (code ":audit") " channel &mdash; the store "
        "ledger records that an op committed, not who approved it.")
   (table ["Fact" "Op" "Listing" "Disposition" "Basis / rules"]
          (for [f ledger]
            (row (td (code (kw-name (:t f))))
                 (td (code (kw-name (:op f))))
                 (td (code (:subject f)))
                 (td (if (= :hold (:disposition f))
                       (span "critical" "hold")
                       (span "ok" (esc (kw-name (:disposition f))))))
                 (td (let [b (:basis f)]
                       (if (seq b)
                         (esc (str/join " / " (map kw-name b)))
                         (dash)))))))))

(def ^:private record-key-order
  ["record_id" "kind" "listing_id" "jurisdiction" "immutable"])

(defn- registry-section [db]
  (let [admissions (store/admission-history db)
        lifts (store/lift-history db)
        rows (concat (map (fn [r] [:admission r]) admissions)
                     (map (fn [r] [:lift r]) lifts))]
    (section
     "Exchange book-of-record drafts"
     (str "Built by " (code "marketadmin.registry") " during the commits above. These are "
          "DRAFTS: " (code "marketadmin.registry/unsigned-certificate") " marks every "
          "certificate " (code "\"status\": \"draft-unsigned\"") " and "
          (code "\"issued_by_registry\": false") " &mdash; signing is the licensed "
          "exchange operator's act, not this actor's. The sequence numbers are "
          "jurisdiction-scoped and start at 0; there is no invented check digit.")
     (table (into ["Kind"] record-key-order)
            (for [[kind r] rows]
              (row (td (code (kw-name kind)))
                   (apply str (for [k record-key-order]
                                (td (let [v (get r k)]
                                      (cond (nil? v) (dash)
                                            (boolean? v) (code (str v))
                                            :else (code v)))))))))) ))

(defn- register-for
  "The store register an effect actually writes into -- what a later
  reader would query when asking 'who approved this?'."
  [db effect subject]
  (case effect
    :listing/upsert            {:label "listing record" :m (store/listing db subject)}
    :assessment/set            {:label "assessment register" :m (store/assessment-of db subject)}
    :surveillance/set          {:label "surveillance register" :m (store/surveillance-of db subject)}
    :listing/mark-admitted     {:label "listing record + admission draft"
                                :m (merge (store/listing db subject)
                                          (first (filter #(= subject (get % "listing_id"))
                                                         (store/admission-history db))))}
    :listing/mark-halt-lifted  {:label "listing record + halt-lift draft"
                                :m (merge (store/listing db subject)
                                          (first (filter #(= subject (get % "listing_id"))
                                                         (store/lift-history db))))}
    {:label (str effect) :m nil}))

(defn- approver-key
  "PROBE, not a claim: scan the register's own keys for anything naming an
  approver. Deliberately generic (any key whose name contains 'approv',
  keyword or string) so that if someone later changes how the store
  persists the approver, this page reports the fix instead of repeating
  a stale accusation."
  [m]
  (->> (seq m)
       (sort-by (fn [[k _]] (str k)))
       (some (fn [[k v]]
               (when (re-find #"(?i)approv" (str (kw-name k)))
                 [k v])))))

(defn- approver-retention-section [db runs]
  (let [approved (filter approver-in-run runs)]
    (section
     "Approver attribution probe (measured, not asserted)"
     (str "For every op a human actually approved, this probe re-reads the store register "
          "that op's " (code ":effect") " writes into and looks for an approver key. "
          "It is derived at render time, so it reports whatever the store does today. "
          (code "marketadmin.operation") "'s " (code ":request-approval") " node attaches "
          (code ":approved-by") " to the record's " (code ":payload") " only &mdash; whether "
          "that survives depends on which key that effect's branch of "
          (code "marketadmin.store/commit-record!") " reads.")
     (table ["Op" "Listing" "Effect" "Register re-read" "Approver in run (audit channel)" "Retained in store?"]
            (for [r approved
                  :let [req (:request r)
                        eff (:effect (proposal-of r))
                        {:keys [label m]} (register-for db eff (:subject req))
                        found (approver-key m)]]
              (row (td (code (kw-name (:op req))))
                   (td (code (:subject req)))
                   (td (code (kw-name eff)))
                   (td (muted label))
                   (td (esc (approver-in-run r)))
                   (td (if found
                         (span "ok" (str "retained &middot; " (code (kw-name (first found)))
                                         " = " (esc (second found))))
                         (span "warn"
                               (str "NOT retained &mdash; audit only. This register has no "
                                    "approver key; the approver is recoverable from this run's "
                                    (code ":approval-granted") " audit fact, not from the "
                                    "committed record."))))))))))

(defn- coverage-section [db]
  (let [listings (store/all-listings db)
        seen (sort (distinct (map :jurisdiction listings)))
        cov (facts/coverage seen)
        all (sort (distinct (concat (keys facts/catalog) seen)))]
    (section
     "Jurisdiction spec-basis catalog (honest coverage)"
     (str "The G2 citation table " (code "marketadmin.governor") " checks every "
          (code ":jurisdiction/assess") " proposal against. A jurisdiction absent from "
          (code "marketadmin.facts/catalog") " has NO spec-basis, full stop &mdash; the "
          "advisor must not fabricate one, and the governor holds if it tries "
          "(that is exactly what happened to listing-2 above). Coverage over the "
          "jurisdictions actually present in this directory: "
          (span "num" (str (:covered cov) " / " (:requested cov)))
          (when (seq (:missing-jurisdictions cov))
            (str " &middot; missing: "
                 (str/join ", " (map code (:missing-jurisdictions cov)))))
          ". " (esc (:note cov)))
     (table ["ISO3" "Jurisdiction" "Owner authority" "Legal basis" "Required evidence" "In this directory"]
            (for [iso3 all
                  :let [sb (facts/spec-basis iso3)
                        used (filter #(= iso3 (:jurisdiction %)) listings)]]
              (row (td (code iso3))
                   (td (if sb (esc (:name sb)) (span "critical" "no spec-basis on file")))
                   (td (if sb (esc (:owner-authority sb)) (dash)))
                   (td (if sb (esc (:legal-basis sb)) (dash)))
                   (td (if sb
                         (str (span "num" (count (:required-evidence sb)))
                              " &middot; "
                              (esc (str/join " / " (:required-evidence sb))))
                         (span "critical" "none &mdash; any checklist here would be invented")))
                   (td (if (seq used)
                         (str/join " " (map (comp code :id) used))
                         (dash)))))))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from a completed `run-demo!`
  result. Every argument to every cell comes out of `db`/`runs`."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        holds (filter #(= :governor-hold (:t %)) ledger)]
    (str
     "<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-6611 &middot; market administration operator console</title>"
     "<style>" (jp-go-dds.skin/dds+skin) "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Administration of financial markets (ISIC 6611) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample &middot; governor-gated &middot; "
     "listing admission &amp; trade-halt lift are always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"banner\">\n"
     "    <p>Generated at build time by <code>marketadmin.render-html</code> "
     "(<code>clojure -M:dev:render-html</code>) from a real run of "
     "<code>marketadmin.operation</code> &rarr; <code>marketadmin.governor</code> &rarr; "
     "<code>marketadmin.store</code>. Nothing below is hand-written: "
     (str (count runs)) " actor runs produced " (str (count ledger))
     " immutable ledger facts, of which " (str (count holds))
     " are HARD governor holds spanning " (str (count (distinct (mapcat :basis holds))))
     " distinct rules. The build fails if that hold count is zero.</p>\n"
     "  </section>\n"
     (listing-directory-section db ledger)
     (runs-section runs)
     (hard-hold-section ledger)
     (op-gate-section runs)
     (phase-table-section)
     (ledger-section ledger)
     (registry-section db)
     (approver-retention-section db runs)
     (coverage-section db)
     "</main>\n"
     "<footer>\n"
     "  <p>cloud-itonami-isic-6611 &middot; AGPL-3.0-or-later &middot; "
     "regenerate with <code>clojure -M:dev:render-html</code>. "
     "This page is a demo against <code>marketadmin.store/seed-db</code>; it is not "
     "connected to any real exchange or trading system, and every certificate this "
     "actor drafts is unsigned.</p>\n"
     "</footer>\n"
     "</body></html>\n")))

;; ----------------------------- build-time invariants -----------------------------

(defn- assert-real-holds!
  "Build-time invariant, NOT a comment: a console that shows no HARD hold
  is not showing this actor. If the governor produced zero
  `:governor-hold` facts, or produced one with no rule basis, or the
  rendered document lost one, the build FAILS."
  [ledger html]
  (let [holds (filter #(= :governor-hold (:t %)) ledger)
        rules (distinct (mapcat :basis holds))]
    (when (zero? (count holds))
      (throw (ex-info "render-html: the governor produced ZERO :governor-hold facts -- refusing to write a console that cannot demonstrate a HARD hold"
                      {:ledger-facts (count ledger)})))
    (when-let [bad (seq (remove (comp seq :basis) holds))]
      (throw (ex-info "render-html: a :governor-hold fact carried no rule basis"
                      {:facts (vec bad)})))
    (when-let [missing (seq (remove #(str/includes? html (kw-name %)) rules))]
      (throw (ex-info "render-html: the rendered document does not mention every HARD rule the governor fired"
                      {:missing (vec missing) :fired (vec rules)})))
    {:holds (count holds) :rules (vec rules)}))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as result} (run-demo!)
        ledger (vec (store/ledger db))
        html (render result)
        {:keys [holds rules]} (assert-real-holds! ledger html)]
    (spit out html)
    (println "wrote" out
             (str "(" (count html) " bytes, " (count runs) " actor runs, "
                  (count ledger) " ledger facts, " holds " HARD holds over "
                  (count rules) " rules: " (str/join " " (map kw-name rules)) ", "
                  (count (store/admission-history db)) " admission drafts, "
                  (count (store/lift-history db)) " halt-lift drafts)"))))
