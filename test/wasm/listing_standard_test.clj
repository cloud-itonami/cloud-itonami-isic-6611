(ns wasm.listing-standard-test
  "Hosts wasm/listing_standard.wasm (compiled from
  wasm/listing_standard.kotoba, see wasm/README.md) via kototama.tender --
  proves marketadmin.registry's minimum-market-cap listing-standard check
  runs as a real WASM guest, not just as JVM Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the one real i32 input is written into the guest's
  exported linear memory at a fixed offset before calling main() -- see
  wasm/listing_standard.kotoba's ns docstring for the offset layout."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/listing_standard.wasm"))))

(defn- run-listing-standard-met? [market-cap]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 market-cap)
    (tender/call-main instance)))

(deftest listing-standard-wasm-admits-above-minimum
  (testing "market cap above the USD 4,000,000 minimum -> standard met"
    (is (= 1 (run-listing-standard-met? 5000000)))))

(deftest listing-standard-wasm-rejects-below-minimum
  (testing "market cap below the USD 4,000,000 minimum -> standard not met"
    (is (= 0 (run-listing-standard-met? 1000000)))))

(deftest listing-standard-wasm-admits-at-exact-minimum
  (testing "market cap exactly at the USD 4,000,000 minimum (>=, boundary) -> standard met"
    (is (= 1 (run-listing-standard-met? 4000000)))))
