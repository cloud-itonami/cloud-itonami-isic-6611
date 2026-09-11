(ns marketadmin.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:listing/admit`/`:trade-halt/lift` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [marketadmin.phase :as phase]))

(deftest listing-admit-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real listing admission"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :listing/admit))
          (str "phase " n " must not auto-commit :listing/admit")))))

(deftest trade-halt-lift-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-lifts a real trade halt"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :trade-halt/lift))
          (str "phase " n " must not auto-commit :trade-halt/lift")))))

(deftest surveillance-screen-never-auto-at-any-phase
  (testing "screening moves no capital, but is still never auto-eligible, matching every sibling KYC/conflict/independence screen"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :surveillance/screen))
          (str "phase " n " must not auto-commit :surveillance/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":listing/intake moves no capital -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:listing/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :listing/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :listing/admit} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :trade-halt/lift} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :listing/intake} :commit)))))
