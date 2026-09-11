(ns marketadmin.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean listing through
  intake -> jurisdiction exchange-registration assessment ->
  surveillance screening -> listing-admission proposal (always
  escalates) -> human approval -> commit, then a clean already-listed
  security through surveillance screening -> trade-halt-lift proposal
  (always escalates) -> human approval -> commit, then shows five HARD
  holds (a jurisdiction with no spec-basis, a listing whose own market
  capitalization falls below the minimum listing standard, an
  unresolved surveillance flag, a halt-lift attempt against a listing
  with no active halt, and a double-admission of an already-admitted
  listing) that never reach a human at all, and prints the audit
  ledger + the draft listing-admission and halt-lift records."
  (:require [langgraph.graph :as g]
            [marketadmin.store :as store]
            [marketadmin.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :exchange-administrator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== listing/intake listing-1 (JPN, equity, clean; market-cap 10,000,000 above the 4,000,000 minimum) ==")
    (println (exec! actor "t1" {:op :listing/intake :subject "listing-1"
                                :patch {:id "listing-1" :issuer "Sakura Technologies K.K."}} operator))

    (println "== jurisdiction/assess listing-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "listing-1"} operator))
    (println (approve! actor "t2"))

    (println "== surveillance/screen listing-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :surveillance/screen :subject "listing-1"} operator))
    (println (approve! actor "t3"))

    (println "== listing/admit listing-1 (always escalates -- actuation/admit-listing) ==")
    (let [r (exec! actor "t4" {:op :listing/admit :subject "listing-1"} operator)]
      (println r)
      (println "-- human exchange administrator approves --")
      (println (approve! actor "t4")))

    (println "== surveillance/screen listing-5 (already listed, active halt; clean; escalates -- human approves) ==")
    (println (exec! actor "t5" {:op :surveillance/screen :subject "listing-5"} operator))
    (println (approve! actor "t5"))

    (println "== trade-halt/lift listing-5 (always escalates -- actuation/lift-halt) ==")
    (let [r (exec! actor "t6" {:op :trade-halt/lift :subject "listing-5"} operator)]
      (println r)
      (println "-- human exchange administrator approves --")
      (println (approve! actor "t6")))

    (println "== jurisdiction/assess listing-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t7" {:op :jurisdiction/assess :subject "listing-2" :no-spec? true} operator))

    (println "== jurisdiction/assess listing-3 (escalates -- human approves; sets up the listing-standard test below) ==")
    (println (exec! actor "t8" {:op :jurisdiction/assess :subject "listing-3"} operator))
    (println (approve! actor "t8"))

    (println "== listing/admit listing-3 (market-cap 2,000,000 below the 4,000,000 minimum listing standard -> HARD hold) ==")
    (println (exec! actor "t9" {:op :listing/admit :subject "listing-3"} operator))

    (println "== surveillance/screen listing-4 (unresolved surveillance flag -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t10" {:op :surveillance/screen :subject "listing-4"} operator))

    (println "== trade-halt/lift listing-6 (no active halt -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t11" {:op :trade-halt/lift :subject "listing-6"} operator))

    (println "== listing/admit listing-1 AGAIN (double-admission of an already-admitted listing -> HARD hold) ==")
    (println (exec! actor "t12" {:op :listing/admit :subject "listing-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft listing-admission records ==")
    (doseq [r (store/admission-history db)] (println r))

    (println "== draft trade-halt-lift records ==")
    (doseq [r (store/lift-history db)] (println r))))
