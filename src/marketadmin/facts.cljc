(ns marketadmin.facts
  "Per-jurisdiction exchange/market-administration regulatory catalog --
  the G2-style spec-basis table the Market Administration Governor
  checks every jurisdiction/assess proposal against ('did the advisor
  cite an OFFICIAL public source for this jurisdiction's exchange-
  registration/listing-rule requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official exchange/
  markets regulator (see `:provenance`); they are a STARTING catalog,
  not a from-scratch survey of all ~194 jurisdictions. Extending
  coverage is additive: add one map to `catalog`, cite a real source,
  done -- never invent a jurisdiction's requirements to make coverage
  look bigger.

  Like `brokerage.facts`'s `USA`, exchange registration in the US IS
  federally regulated (Securities Exchange Act of 1934 §6, SEC-
  registered national securities exchanges) -- so this catalog's US
  entry is `USA`, a genuine national authority.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  listing-application/market-rule-disclosure/surveillance-
  certification evidence set submitted in some form; `:legal-basis` /
  `:owner-authority` / `:provenance` are the G2 citation the governor
  requires before any :jurisdiction/assess proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "金融庁 (Financial Services Agency) / 日本取引所グループ (JPX)"
          :legal-basis "金融商品取引法 -- 取引所開設・上場審査に関する規定"
          :national-spec "日本取引所グループ 有価証券上場規程"
          :provenance "https://www.fsa.go.jp/ https://www.jpx.co.jp/"
          :required-evidence ["上場申請書 (listing application)"
                              "市場規則開示書面 (market-rule disclosure statement)"
                              "審査部門適合性証明 (surveillance/compliance certification)"
                              "取引所開設免許証 (exchange registration certificate)"]}
   "USA" {:name "United States"
          :owner-authority "U.S. Securities and Exchange Commission (SEC)"
          :legal-basis "Securities Exchange Act of 1934 §6 (national securities exchange registration)"
          :national-spec "Exchange listing standards (e.g. NYSE/Nasdaq initial listing requirements)"
          :provenance "https://www.sec.gov/"
          :required-evidence ["Listing application"
                              "Market-rule disclosure statement"
                              "Surveillance/compliance certification"
                              "Exchange registration certificate (Form 1, Exchange Act §6)"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Financial Conduct Authority (FCA)"
          :legal-basis "UK Listing Rules (UKLR)"
          :national-spec "FCA Handbook UKLR -- admission to listing requirements"
          :provenance "https://www.fca.org.uk/"
          :required-evidence ["Listing application"
                              "Market-rule disclosure statement"
                              "Surveillance/compliance certification"
                              "Recognised Investment Exchange (RIE) registration certificate"]}
   "DEU" {:name "Germany"
          :owner-authority "Bundesanstalt für Finanzdienstleistungsaufsicht (BaFin) / Deutsche Börse"
          :legal-basis "Börsengesetz (BörsG) -- Zulassung zum Börsenhandel"
          :national-spec "Deutsche Börse Zulassungsregeln (Listing Rules)"
          :provenance "https://www.bafin.de/ https://www.deutsche-boerse.com/"
          :required-evidence ["Zulassungsantrag (listing application)"
                              "Marktregel-Offenlegung (market-rule disclosure statement)"
                              "Überwachungsbescheinigung (surveillance/compliance certification)"
                              "Börsenzulassungsurkunde (exchange registration certificate)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to admit a listing
  on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-6611 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `marketadmin.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
