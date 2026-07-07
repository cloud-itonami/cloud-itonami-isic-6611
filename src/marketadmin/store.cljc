(ns marketadmin.store
  "SSoT for the market-administration actor, behind a `Store` protocol
  so the backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/marketadmin/store_contract_test.clj), which is the whole
  point: the actor, the Market Administration Governor and the audit
  ledger never know which SSoT they run on.

  Like `realty.store`'s pending contract, an active trade halt lives
  INLINE on the listing record (`:halt-active?`/`:halt-reason`) rather
  than in a separate collection -- a listing has at most ONE active
  halt at a time. Lifting a halt CLEARS `:halt-active?` to false, which
  doubles as the double-lift guard: a repeat lift attempt naturally
  falls into the SAME `halt-not-active` check a never-halted listing
  would also trigger.

  The ledger stays append-only on every backend: 'which listing was
  screened for an open surveillance flag, which listing was admitted to
  trading, which trade halt was lifted, on what jurisdictional basis,
  approved by whom' is always a query over an immutable log -- the
  audit trail an issuer trusting an exchange needs, and the evidence an
  operator needs if an admission or a halt-lift is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [marketadmin.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (listing [s id])
  (all-listings [s])
  (surveillance-of [s listing-id] "committed surveillance screening verdict for a listing, or nil")
  (assessment-of [s listing-id] "committed jurisdiction market-rule assessment, or nil")
  (ledger [s])
  (admission-history [s] "the append-only listing-admission history (marketadmin.registry drafts)")
  (lift-history [s] "the append-only trade-halt-lift history (marketadmin.registry drafts)")
  (next-sequence [s jurisdiction] "next listing-admission-number sequence for a jurisdiction")
  (lift-sequence [s jurisdiction] "next halt-lift-number sequence for a jurisdiction")
  (listing-already-admitted? [s listing-id] "has this listing already been admitted?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-listings [s listings] "replace/seed the listing directory (map id->listing)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained listing set covering both actuation
  lifecycles (admission, halt-lift) so the actor + tests run offline."
  []
  {:listings
   {"listing-1" {:id "listing-1" :issuer "Sakura Technologies K.K." :security-type :equity
                 :market-cap 10000000 :surveillance-flag? false
                 :halt-active? false :halt-reason nil :admitted? false
                 :jurisdiction "JPN" :status :intake}
    "listing-2" {:id "listing-2" :issuer "Atlantis Commodities Ltd." :security-type :commodity
                 :market-cap 8000000 :surveillance-flag? false
                 :halt-active? false :halt-reason nil :admitted? false
                 :jurisdiction "ATL" :status :intake}
    "listing-3" {:id "listing-3" :issuer "鈴木マイクロキャップ" :security-type :equity
                 :market-cap 2000000 :surveillance-flag? false
                 :halt-active? false :halt-reason nil :admitted? false
                 :jurisdiction "JPN" :status :intake}
    "listing-4" {:id "listing-4" :issuer "田中疑義商事" :security-type :equity
                 :market-cap 10000000 :surveillance-flag? true
                 :halt-active? false :halt-reason nil :admitted? false
                 :jurisdiction "JPN" :status :intake}
    "listing-5" {:id "listing-5" :issuer "佐藤重工業" :security-type :equity
                 :market-cap 10000000 :surveillance-flag? false
                 :halt-active? true :halt-reason "unusual trading volume" :admitted? true
                 :jurisdiction "JPN" :status :active}
    "listing-6" {:id "listing-6" :issuer "高橋商事" :security-type :equity
                 :market-cap 10000000 :surveillance-flag? false
                 :halt-active? false :halt-reason nil :admitted? true
                 :jurisdiction "JPN" :status :active}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- admit-listing!
  "Backend-agnostic `:listing/mark-admitted` -- looks up the listing
  via the protocol and drafts the listing-admission record, and returns
  {:result .. :listing-patch ..} for the caller to persist."
  [s listing-id]
  (let [l (listing s listing-id)
        seq-n (next-sequence s (:jurisdiction l))
        result (registry/register-listing-admission listing-id (:jurisdiction l) seq-n)]
    {:result result
     :listing-patch {:admitted? true :status :active
                     :admission-number (get result "admission_number")}}))

(defn- lift-halt!
  "Backend-agnostic `:listing/mark-halt-lifted` -- looks up the listing
  via the protocol and drafts the halt-lift record, and returns
  {:result .. :listing-patch ..} for the caller to persist.
  `:listing-patch` CLEARS `:halt-active?` to false -- see ns docstring
  for why this doubles as the double-lift guard instead of a separate
  check."
  [s listing-id]
  (let [l (listing s listing-id)
        seq-n (lift-sequence s (:jurisdiction l))
        result (registry/register-halt-lift listing-id (:jurisdiction l) seq-n)]
    {:result result
     :listing-patch {:halt-active? false :halt-reason nil
                     :lift-number (get result "lift_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (listing [_ id] (get-in @a [:listings id]))
  (all-listings [_] (sort-by :id (vals (:listings @a))))
  (surveillance-of [_ id] (get-in @a [:surveillance id]))
  (assessment-of [_ listing-id] (get-in @a [:assessments listing-id]))
  (ledger [_] (:ledger @a))
  (admission-history [_] (:admissions @a))
  (lift-history [_] (:lifts @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (lift-sequence [_ jurisdiction] (get-in @a [:lift-sequences jurisdiction] 0))
  (listing-already-admitted? [_ listing-id] (boolean (get-in @a [:listings listing-id :admitted?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :listing/upsert
      (swap! a update-in [:listings (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :surveillance/set
      (swap! a assoc-in [:surveillance (first path)] payload)

      :listing/mark-admitted
      (let [listing-id (first path)
            {:keys [result listing-patch]} (admit-listing! s listing-id)
            jurisdiction (:jurisdiction (listing s listing-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences jurisdiction] (fnil inc 0))
                       (update-in [:listings listing-id] merge listing-patch)
                       (update :admissions registry/append result))))
        result)

      :listing/mark-halt-lifted
      (let [listing-id (first path)
            {:keys [result listing-patch]} (lift-halt! s listing-id)
            jurisdiction (:jurisdiction (listing s listing-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:lift-sequences jurisdiction] (fnil inc 0))
                       (update-in [:listings listing-id] merge listing-patch)
                       (update :lifts registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-listings [s listings] (when (seq listings) (swap! a assoc :listings listings)) s))

(defn seed-db
  "A MemStore seeded with the demo listing set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :surveillance {} :ledger [] :sequences {}
                           :admissions [] :lift-sequences {} :lifts []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/surveillance payloads, ledger facts,
  admission/lift records) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities -- the same convention every
  sibling actor's store uses."
  {:listing/id                {:db/unique :db.unique/identity}
   :assessment/listing-id      {:db/unique :db.unique/identity}
   :surveillance/listing-id    {:db/unique :db.unique/identity}
   :ledger/seq                 {:db/unique :db.unique/identity}
   :admission/seq               {:db/unique :db.unique/identity}
   :lift/seq                    {:db/unique :db.unique/identity}
   :sequence/jurisdiction       {:db/unique :db.unique/identity}
   :lift-sequence/jurisdiction  {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- listing->tx [{:keys [id issuer security-type market-cap surveillance-flag? halt-active? halt-reason
                           admitted? jurisdiction status admission-number lift-number]}]
  (cond-> {:listing/id id}
    issuer                        (assoc :listing/issuer issuer)
    security-type                  (assoc :listing/security-type security-type)
    market-cap                      (assoc :listing/market-cap market-cap)
    (some? surveillance-flag?)       (assoc :listing/surveillance-flag? surveillance-flag?)
    (some? halt-active?)              (assoc :listing/halt-active? halt-active?)
    halt-reason                        (assoc :listing/halt-reason halt-reason)
    (some? admitted?)                   (assoc :listing/admitted? admitted?)
    jurisdiction                         (assoc :listing/jurisdiction jurisdiction)
    status                                (assoc :listing/status status)
    admission-number                       (assoc :listing/admission-number admission-number)
    lift-number                             (assoc :listing/lift-number lift-number)))

(def ^:private listing-pull
  [:listing/id :listing/issuer :listing/security-type :listing/market-cap :listing/surveillance-flag?
   :listing/halt-active? :listing/halt-reason :listing/admitted? :listing/jurisdiction
   :listing/status :listing/admission-number :listing/lift-number])

(defn- pull->listing [m]
  (when (:listing/id m)
    {:id (:listing/id m) :issuer (:listing/issuer m) :security-type (:listing/security-type m)
     :market-cap (:listing/market-cap m) :surveillance-flag? (boolean (:listing/surveillance-flag? m))
     :halt-active? (boolean (:listing/halt-active? m)) :halt-reason (:listing/halt-reason m)
     :admitted? (boolean (:listing/admitted? m)) :jurisdiction (:listing/jurisdiction m)
     :status (:listing/status m) :admission-number (:listing/admission-number m)
     :lift-number (:listing/lift-number m)}))

(defrecord DatomicStore [conn]
  Store
  (listing [_ id]
    (pull->listing (d/pull (d/db conn) listing-pull [:listing/id id])))
  (all-listings [_]
    (->> (d/q '[:find [?id ...] :where [?e :listing/id ?id]] (d/db conn))
         (map #(pull->listing (d/pull (d/db conn) listing-pull [:listing/id %])))
         (sort-by :id)))
  (surveillance-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?lid
                :where [?k :surveillance/listing-id ?lid] [?k :surveillance/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ listing-id]
    (dec* (d/q '[:find ?p . :in $ ?lid
                :where [?a :assessment/listing-id ?lid] [?a :assessment/payload ?p]]
              (d/db conn) listing-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (admission-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :admission/seq ?s] [?e :admission/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (lift-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :lift/seq ?s] [?e :lift/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (lift-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :lift-sequence/jurisdiction ?j] [?e :lift-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (listing-already-admitted? [s listing-id]
    (boolean (:admitted? (listing s listing-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :listing/upsert
      (d/transact! conn [(listing->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/listing-id (first path) :assessment/payload (enc payload)}])

      :surveillance/set
      (d/transact! conn [{:surveillance/listing-id (first path) :surveillance/payload (enc payload)}])

      :listing/mark-admitted
      (let [listing-id (first path)
            {:keys [result listing-patch]} (admit-listing! s listing-id)
            jurisdiction (:jurisdiction (listing s listing-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(listing->tx (assoc listing-patch :id listing-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:admission/seq (count (admission-history s)) :admission/record (enc (get result "record"))}])
        result)

      :listing/mark-halt-lifted
      (let [listing-id (first path)
            {:keys [result listing-patch]} (lift-halt! s listing-id)
            jurisdiction (:jurisdiction (listing s listing-id))
            next-n (inc (lift-sequence s jurisdiction))]
        (d/transact! conn
                     [(listing->tx (assoc listing-patch :id listing-id))
                      {:lift-sequence/jurisdiction jurisdiction :lift-sequence/next next-n}
                      {:lift/seq (count (lift-history s)) :lift/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-listings [s listings]
    (when (seq listings) (d/transact! conn (mapv listing->tx (vals listings)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:listings ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [listings]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-listings s listings))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo listing set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
