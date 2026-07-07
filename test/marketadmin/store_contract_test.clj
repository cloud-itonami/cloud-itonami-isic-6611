(ns marketadmin.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [marketadmin.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Technologies K.K." (:issuer (store/listing s "listing-1"))))
      (is (= "JPN" (:jurisdiction (store/listing s "listing-1"))))
      (is (= 10000000 (:market-cap (store/listing s "listing-1"))))
      (is (false? (:surveillance-flag? (store/listing s "listing-1"))))
      (is (true? (:surveillance-flag? (store/listing s "listing-4"))))
      (is (true? (:halt-active? (store/listing s "listing-5"))))
      (is (false? (:halt-active? (store/listing s "listing-6"))))
      (is (= ["listing-1" "listing-2" "listing-3" "listing-4" "listing-5" "listing-6"]
             (mapv :id (store/all-listings s))))
      (is (nil? (store/surveillance-of s "listing-1")))
      (is (nil? (store/assessment-of s "listing-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/admission-history s)))
      (is (= [] (store/lift-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (zero? (store/lift-sequence s "JPN")))
      (is (false? (store/listing-already-admitted? s "listing-1")))
      (is (true? (store/listing-already-admitted? s "listing-5"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :listing/upsert
                                 :value {:id "listing-1" :issuer "Sakura Technologies K.K."}})
        (is (= "Sakura Technologies K.K." (:issuer (store/listing s "listing-1"))))
        (is (= 10000000 (:market-cap (store/listing s "listing-1"))) "market-cap preserved"))
      (testing "assessment / surveillance payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["listing-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "listing-1")))
        (store/commit-record! s {:effect :surveillance/set :path ["listing-1"]
                                 :payload {:listing-id "listing-1" :verdict :clear}})
        (is (= {:listing-id "listing-1" :verdict :clear} (store/surveillance-of s "listing-1"))))
      (testing "listing admission drafts an admission record and advances the sequence"
        (store/commit-record! s {:effect :listing/mark-admitted :path ["listing-1"]})
        (is (= "JPN-LIST-000000" (get (first (store/admission-history s)) "record_id")))
        (is (= "listing-admission-draft" (get (first (store/admission-history s)) "kind")))
        (is (true? (:admitted? (store/listing s "listing-1"))))
        (is (= 1 (count (store/admission-history s))))
        (is (= 1 (store/next-sequence s "JPN")))
        (is (true? (store/listing-already-admitted? s "listing-1")))
        (is (false? (store/listing-already-admitted? s "listing-2"))))
      (testing "halt-lift drafts a lift record, CLEARS halt-active?, and advances the lift sequence"
        (store/commit-record! s {:effect :listing/mark-halt-lifted :path ["listing-5"]})
        (is (= "JPN-HALT-000000" (get (first (store/lift-history s)) "record_id")))
        (is (= "halt-lift-draft" (get (first (store/lift-history s)) "kind")))
        (is (false? (:halt-active? (store/listing s "listing-5"))) "halt cleared after lift")
        (is (= 1 (count (store/lift-history s))))
        (is (= 1 (store/lift-sequence s "JPN"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/listing s "nope")))
    (is (= [] (store/all-listings s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/admission-history s)))
    (is (= [] (store/lift-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (is (zero? (store/lift-sequence s "JPN")))
    (store/with-listings s {"x" {:id "x" :issuer "i" :security-type :equity
                                :market-cap 5000000 :surveillance-flag? false
                                :halt-active? false :halt-reason nil :admitted? false
                                :jurisdiction "JPN" :status :intake}})
    (is (= "i" (:issuer (store/listing s "x"))))))
