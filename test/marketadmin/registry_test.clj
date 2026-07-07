(ns marketadmin.registry-test
  (:require [clojure.test :refer [deftest is]]
            [marketadmin.registry :as r]))

;; ----------------------------- listing-standard-met? -----------------------------

(deftest listing-standard-met-when-market-cap-at-or-above-minimum
  (is (r/listing-standard-met? {:market-cap r/minimum-market-cap}))
  (is (r/listing-standard-met? {:market-cap (+ r/minimum-market-cap 1)}))
  (is (not (r/listing-standard-met? {:market-cap (- r/minimum-market-cap 1)}))))

(deftest listing-standard-not-met-when-market-cap-missing
  (is (not (r/listing-standard-met? {}))))

;; ----------------------------- register-listing-admission -----------------------------

(deftest listing-admission-is-a-draft-not-a-real-admission
  (let [result (r/register-listing-admission "listing-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest listing-admission-assigns-admission-number
  (let [result (r/register-listing-admission "listing-1" "JPN" 7)]
    (is (= (get result "admission_number") "JPN-LIST-000007"))
    (is (= (get-in result ["record" "listing_id"]) "listing-1"))
    (is (= (get-in result ["record" "kind"]) "listing-admission-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest listing-admission-validation-rules
  (is (thrown? Exception (r/register-listing-admission "" "JPN" 0)))
  (is (thrown? Exception (r/register-listing-admission "listing-1" "" 0)))
  (is (thrown? Exception (r/register-listing-admission "listing-1" "JPN" -1))))

(deftest admission-history-is-append-only
  (let [a1 (r/register-listing-admission "listing-1" "JPN" 0)
        hist (r/append [] a1)
        a2 (r/register-listing-admission "listing-2" "JPN" 1)
        hist2 (r/append hist a2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-LIST-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-LIST-000001" (get-in hist2 [1 "record_id"])))))

;; ----------------------------- register-halt-lift -----------------------------

(deftest halt-lift-is-a-draft-not-a-real-lift
  (let [result (r/register-halt-lift "listing-5" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest halt-lift-assigns-lift-number
  (let [result (r/register-halt-lift "listing-5" "JPN" 7)]
    (is (= (get result "lift_number") "JPN-HALT-000007"))
    (is (= (get-in result ["record" "listing_id"]) "listing-5"))
    (is (= (get-in result ["record" "kind"]) "halt-lift-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest halt-lift-validation-rules
  (is (thrown? Exception (r/register-halt-lift "" "JPN" 0)))
  (is (thrown? Exception (r/register-halt-lift "listing-5" "" 0)))
  (is (thrown? Exception (r/register-halt-lift "listing-5" "JPN" -1))))

(deftest lift-history-is-append-only
  (let [l1 (r/register-halt-lift "listing-5" "JPN" 0)
        hist (r/append [] l1)
        l2 (r/register-halt-lift "listing-6" "JPN" 1)
        hist2 (r/append hist l2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-HALT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-HALT-000001" (get-in hist2 [1 "record_id"])))))
