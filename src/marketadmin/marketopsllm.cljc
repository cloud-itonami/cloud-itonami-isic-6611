(ns marketadmin.marketopsllm
  "MarketOps-LLM client -- the *contained intelligence node* for the
  market-administration actor.

  It normalizes listing intake, drafts a per-jurisdiction exchange-
  registration/listing-rule checklist, screens listings for an open
  surveillance-flag signal, drafts the listing-admission action, and
  drafts the trade-halt-lift action. CRITICAL: it is a smart-but-
  untrusted advisor. It returns a *proposal* (with a rationale + the
  fields it cited), never a committed record or a real listing
  admission/halt lift. Every output is censored downstream by
  `marketadmin.governor` before anything touches the SSoT, and
  `:listing/admit`/`:trade-halt/lift` proposals NEVER auto-commit at
  any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/admit-listing | :actuation/lift-halt | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [marketadmin.facts :as facts]
            [marketadmin.registry :as registry]
            [marketadmin.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the issuer, security type/market-cap or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "銘柄記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :listing/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction exchange-registration/listing-rule checklist draft.
  `:no-spec?` injects the failure mode we must defend against:
  proposing a checklist for a jurisdiction with NO official spec-basis
  in `marketadmin.facts` -- the Market Administration Governor must
  reject this (never invent a jurisdiction's law)."
  [db {:keys [subject no-spec?]}]
  (let [l (store/listing db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction l))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "marketadmin.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-surveillance
  "Surveillance screening draft. `:surveillance-flag?` on the listing
  record injects the failure mode: the Market Administration Governor
  must HOLD, un-overridably, on any open surveillance flag."
  [db {:keys [subject]}]
  (let [l (store/listing db subject)]
    (cond
      (nil? l)
      {:summary "対象listingが見つかりません" :rationale "no listing record"
       :cites [] :effect :surveillance/set :value {:listing-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (:surveillance-flag? l)
      {:summary    (str (:issuer l) ": 未解決の審査フラグを検出")
       :rationale  "スクリーニングが未解決の審査フラグを検出。人手確認とホールドが必須。"
       :cites      [:surveillance-check]
       :effect     :surveillance/set
       :value      {:listing-id subject :verdict :open}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:issuer l) ": 審査フラグなし")
       :rationale  "審査スクリーニング非該当。"
       :cites      [:surveillance-check]
       :effect     :surveillance/set
       :value      {:listing-id subject :verdict :clear}
       :stake      nil
       :confidence 0.9})))

(defn- propose-listing-admission
  "Draft the actual listing-ADMISSION action -- admitting a real
  security to trading. ALWAYS `:stake :actuation/admit-listing` --
  this is a REAL-WORLD act (investors will trade on the listing),
  never a draft the actor may auto-run. See README `Actuation`: no
  phase ever adds this op to a phase's `:auto` set (`marketadmin.
  phase`); the governor also always escalates on `:actuation/admit-
  listing`. Two independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [l (store/listing db subject)
        standard-met? (and l (registry/listing-standard-met? l))]
    {:summary    (str subject " 向け上場承認提案"
                      (when l (str " (issuer=" (:issuer l) ")")))
     :rationale  (if l
                   (str "market-cap=" (:market-cap l) " minimum=" registry/minimum-market-cap)
                   "listingが見つかりません")
     :cites      (if l [subject] [])
     :effect     :listing/mark-admitted
     :value      {:listing-id subject}
     :stake      :actuation/admit-listing
     :confidence (if standard-met? 0.9 0.3)}))

(defn- propose-halt-lift
  "Draft the actual trade-HALT-LIFT action -- lifting a real trade
  halt and resuming trading in a security. ALWAYS `:stake :actuation/
  lift-halt` -- this is a REAL-WORLD act (trading resumes), never a
  draft the actor may auto-run. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`marketadmin.phase`); the
  governor also always escalates on `:actuation/lift-halt`. Two
  independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [l (store/listing db subject)]
    {:summary    (str subject " 向け取引再開提案"
                      (when l (str " (halt-reason=" (:halt-reason l) ")")))
     :rationale  (if l
                   (str "halt-active?=" (:halt-active? l))
                   "listingが見つかりません")
     :cites      (if l [subject] [])
     :effect     :listing/mark-halt-lifted
     :value      {:listing-id subject}
     :stake      :actuation/lift-halt
     :confidence (if (and l (:halt-active? l)) 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :listing/intake         (normalize-intake db request)
    :jurisdiction/assess       (assess-jurisdiction db request)
    :surveillance/screen         (screen-surveillance db request)
    :listing/admit                 (propose-listing-admission db request)
    :trade-halt/lift                 (propose-halt-lift db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは取引所の上場審査・取引再開エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:listing/upsert|:assessment/set|:surveillance/set|"
       ":listing/mark-admitted|:listing/mark-halt-lifted) "
       ":stake(:actuation/admit-listing か :actuation/lift-halt か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess  {:listing (store/listing st subject)}
    :surveillance/screen  {:listing (store/listing st subject)}
    :listing/admit        {:listing (store/listing st subject)}
    :trade-halt/lift      {:listing (store/listing st subject)}
    {:listing (store/listing st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Market Administration
  Governor escalates/holds -- an LLM hiccup can never auto-admit a
  listing or auto-lift a halt."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :marketopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
