(ns rescare.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300)
  for `cloud-itonami-isic-879`: this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`rescare.operation` -> `rescare.advisor` -> `rescare.governor` ->
  `rescare.store`) over this repo's own seeded resident directory
  (`rescare.store/demo-data`: `resident-1`, `resident-2`, `resident-3`)
  and renders whatever that run actually produced.

  NOTE on the pipeline shape: unlike most sibling `cloud-itonami-isic-*`
  actors, this repo is NOT wired into a langgraph `StateGraph` -- its
  `rescare.operation/run-proposal` is a plain `->` pipeline over
  intake/advise/govern/decide/commit/escalate/hold nodes (the ns docstring
  of `rescare.operation` says so itself). There is therefore no
  `langgraph.graph/run*`, no checkpointer and no resume/approve path, so
  this renderer calls `run-proposal` directly. When that ns is wired into
  a real StateGraph with `interrupt-before #{:request-approval}`, this
  file should switch to `g/run*` -- it deliberately reads approver
  attribution by *walking the registers* (see `approval-disclosure`)
  rather than hardcoding \"no approvals exist\", so the page will
  self-correct the moment a HITL path lands.

  NOTE on ledger vocabulary: this repo names its HARD-hold audit fact
  `:proposal-held` (with the governor's `:violations` attached), not
  `:governor-hold` as some sibling repos do. `hard-hold-facts` below is
  the equivalent, and `-main` THROWS if a run produces zero of them --
  the HARD-hold demonstration is a build-time invariant, not a
  convention, so this page can never silently degrade into a page that
  only shows the happy path.

  Determinism: `rescare.operation` stamps `(now-ms)` into every record
  and audit fact. Those timestamps are deliberately NOT rendered, so two
  consecutive runs are byte-identical (verified by diffing two runs into
  separate scratch dirs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [jp-go-dds.skin]
            [rescare.advisor :as advisor]
            [rescare.governor :as governor]
            [rescare.operation :as operation]
            [rescare.phase :as phase]
            [rescare.store :as store]))

;; ----------------------------- red-team advisors -----------------------------
;;
;; The production advisor (`rescare.advisor/mock-advisor`) is
;; well-behaved by construction: it always sets `:effect :propose` and
;; always draws its op from the closed allowlist. Two of the governor's
;; own gates therefore CANNOT be reached end-to-end through it -- they
;; exist precisely for the case where the intelligence node is confused
;; or compromised. These two reify the REAL `rescare.advisor/Advisor`
;; protocol so their proposals travel the REAL pipeline (advise ->
;; govern -> decide -> hold) and land in the REAL audit ledger. They are
;; adversarial *implementations*, not hand-written output: every field
;; below is fed through `rescare.governor/check` and only the governor
;; decides what happens to it. Rows they produce are labelled as such on
;; the page so a reader is never led to believe the production advisor
;; emits them.

(defn- compromised-advisor
  "An advisor that has been tampered with so that it claims a DIRECT
  actuation (`:effect :commit`) instead of a proposal. Everything else
  about the draft is clean, so the governor's `:effect-not-propose` gate
  is the only thing standing between it and the SSoT."
  []
  (reify advisor/Advisor
    (-advise [_ _store {:keys [resident-id]}]
      {:op          :log-resident-note
       :resident-id resident-id
       :summary     (str resident-id " の日常ノートを直接確定する")
       :rationale   "ガバナーを迂回して直接コミットしようとする、改ざんされた助言ノードの再現"
       :cites       [resident-id]
       :effect      :commit
       :value       {:resident-id resident-id :mood :settled}
       :confidence  0.93})))

(defn- unsure-advisor
  "An advisor that is honestly unsure. Nothing about the draft is
  forbidden, so this is NOT a HARD hold -- it exercises the SOFT
  confidence floor (`rescare.governor/confidence-floor`) and must come
  out as an escalation to a human, not as a block."
  []
  (reify advisor/Advisor
    (-advise [_ _store {:keys [resident-id]}]
      {:op          :coordinate-supply-request
       :resident-id resident-id
       :summary     (str resident-id " の消耗品リクエスト(確信度低)")
       :rationale   "在庫記録が古く、必要数を確信できないため人間の確認を求める"
       :cites       [resident-id]
       :effect      :propose
       :value       {:resident-id resident-id :linens 6}
       :confidence  0.42})))

;; ----------------------------- scenario -----------------------------

(def scenarios
  "The demo scenario, as data, so every row on the rendered page traces
  back to exactly one entry here and one real pipeline result.

  `:advisor` selects which advisor implementation drafts the proposal;
  `:phase` is the rollout phase the decision is taken under. Every
  `:resident-id` is a real key of `rescare.store/demo-data` --
  `resident-1` and `resident-2` are registered AND verified,
  `resident-3` is registered but NOT verified.

  Covers: four clean phase-3 auto-commits (one per non-safety op), the
  always-escalate safety op, a phase-gated escalation, the soft
  confidence floor, and all four of the governor's HARD rules."
  [{:label   "日常ノートの記録(清潔な提案)"
    :expect  "phase 3 で自動コミット"
    :advisor :production
    :phase   3
    :request {:op :log-resident-note :resident-id "resident-1"
              :patch {:mood :settled :activity :reading}}}

   {:label   "家族・保護者の面会予定"
    :expect  "phase 3 で自動コミット"
    :advisor :production
    :phase   3
    :request {:op :schedule-family-or-guardian-visit :resident-id "resident-2"
              :patch {:visitor :appointed-guardian :date :2026-08-22}}}

   ;; 期待は「自動コミット」だが、実測では HARD hold になる。ガバナーの
   ;; ブロックリストの限定されていない「薬」が、助言ノード自身の定型文
   ;; 「非医薬品消耗品の…投薬なし。」に一致するため。下の
   ;; `op-reachability` が毎回測り直す。ページの表示を期待に合わせるために
   ;; シナリオを外したりせず、実際に起きることをそのまま見せる。
   {:label   "消耗品の調達調整"
    :expect  "本来は phase 3 で自動コミットのはずだが、下記の欠陥により常に HARD hold"
    :advisor :production
    :phase   3
    :request {:op :coordinate-supply-request :resident-id "resident-1"
              :patch {:linens 12 :hygiene-kit 4}}}

   {:label   "ケア担当者のシフト提案"
    :expect  "phase 3 で自動コミット"
    :advisor :production
    :phase   3
    :request {:op :schedule-staff-shift-proposal :resident-id "resident-2"
              :patch {:shift :night :carer "staff-7"}}}

   {:label   "安全懸念のフラグ(転落の目撃)"
    :expect  "常に人間へエスカレーション(phase 3 でも自動化されない)"
    :advisor :production
    :phase   3
    :request {:op :flag-safety-concern :resident-id "resident-1"
              :patch {:concern :witnessed-fall}}}

   {:label   "面会予定を phase 1 で提案"
    :expect  "phase gate によりエスカレーション"
    :advisor :production
    :phase   1
    :request {:op :schedule-family-or-guardian-visit :resident-id "resident-2"
              :patch {:visitor :family :date :2026-08-23}}}

   {:label   "確信度の低い消耗品リクエスト"
    :expect  "SOFT: 確信度フロア未満でエスカレーション"
    :advisor :unsure
    :phase   3
    :request {:op :coordinate-supply-request :resident-id "resident-1" :patch {}}}

   {:label   "未検証の入居者へのノート記録"
    :expect  "HARD hold: resident-unverified"
    :advisor :production
    :phase   3
    :request {:op :log-resident-note :resident-id "resident-3"
              :patch {:mood :quiet}}}

   {:label   "投薬変更を含むノート記録"
    :expect  "HARD hold: scope-excluded"
    :advisor :production
    :phase   3
    :request {:op :log-resident-note :resident-id "resident-1"
              :patch {:medication-change :increased}}}

   {:label   "身体拘束の承認要求(許可リスト外の操作)"
    :expect  "HARD hold: op-not-allowed"
    :advisor :production
    :phase   3
    :request {:op :authorize-restraint-use :resident-id "resident-2"
              :patch {:duration-min 30}}}

   {:label   "直接コミットを主張する改ざん助言ノード"
    :expect  "HARD hold: effect-not-propose"
    :advisor :compromised
    :phase   3
    :request {:op :log-resident-note :resident-id "resident-1"
              :patch {:mood :settled}}}])

(defn- advisor-for [kind]
  (case kind
    :production  (advisor/mock-advisor)
    :compromised (compromised-advisor)
    :unsure      (unsure-advisor)))

(defn- advisor-label [kind]
  (case kind
    :production  "production"
    :compromised "red-team: 改ざん"
    :unsure      "red-team: 低確信度"))

(defn run-demo!
  "Runs every scenario through the REAL `rescare.operation/run-proposal`
  against one freshly seeded store. Returns `{:db .. :runs [..]}` where
  each run carries its scenario and the actual final pipeline state --
  no field rendered below is hand-authored."
  []
  (let [db (store/seed-db)
        runs (mapv (fn [{:keys [request advisor phase] :as scenario}]
                     {:scenario scenario
                      :result (operation/run-proposal request db (advisor-for advisor) phase)})
                   scenarios)]
    {:db db :runs runs}))

;; ----------------------------- ledger helpers -----------------------------

(defn- fact-proposal
  "The proposal a ledger fact is about. `:proposal-committed` nests it
  under `:record`; `:proposal-escalated` / `:proposal-held` carry it
  directly."
  [fact]
  (or (:proposal fact) (-> fact :record :proposal)))

(defn hard-hold-facts
  "Every HARD-hold audit fact this run produced. This repo names the
  fact `:proposal-held` and attaches the governor's `:violations`; a
  hold is only ever written by `rescare.operation/hold-node`, which only
  fires when `rescare.governor/check` returned `:hard? true`."
  [db]
  (filter #(= :proposal-held (:op %)) (store/ledger db)))

(defn- hold-rules
  "Distinct governor rules that actually fired across this run's holds."
  [db]
  (->> (hard-hold-facts db)
       (mapcat :violations)
       (map :rule)
       distinct
       sort))

;; ----------------------------- approver attribution -----------------------------

(def ^:private approver-key-candidates
  "Keys any sibling actor in this fleet uses to attribute a human
  approval. Checked by NAME across the whole committed record so this
  page reports what the store actually retained rather than asserting a
  known-good or known-broken shape."
  #{:approved-by :approver :approval :approved :by :operator :actor-id :signed-by})

(defn- approver-in
  "Walks an arbitrary nested structure looking for any approver key with
  a non-nil value. Returns the first [k v] found, or nil."
  [x]
  (cond
    (map? x) (or (some (fn [[k v]]
                         (when (and (contains? approver-key-candidates k)
                                    (some? v))
                           [k v]))
                       x)
                 (some approver-in (vals x)))
    (sequential? x) (some approver-in x)
    :else nil))

(defn approval-disclosure
  "DERIVES what this build can honestly say about human-approval
  attribution, by walking the registers the run actually wrote -- the
  committed coordination log and the audit ledger -- instead of
  hardcoding a verdict.

  Three outcomes, so the page self-corrects if the pipeline changes:
    :retained    -- committed records carry the approver
    :audit-only  -- an audit fact names an approver but the committed
                    record dropped it (the scaffold defect seen in
                    sibling repos)
    :no-approval-path -- nothing in this run was ever human-approved,
                    because this actor has no resume/approve path at all

  Returns a map with the verdict and the counts backing it."
  [db]
  (let [records (vec (store/coordination-log db))
        ledger (vec (store/ledger db))
        escalated (filter #(= :proposal-escalated (:op %)) ledger)
        with-approver (filter approver-in records)
        audit-approver (some approver-in ledger)]
    {:verdict (cond
                (seq with-approver) :retained
                audit-approver :audit-only
                :else :no-approval-path)
     :committed-count (count records)
     :approver-count (count with-approver)
     :escalated-count (count escalated)
     :audit-approver audit-approver}))

;; ----------------------------- structural reachability -----------------------------

(defn- scan-blob
  "Mirror of `rescare.governor`'s private `text-blob` -- the same
  flattening the scope scan runs over. Duplicated (not reused) only
  because it is private there; if the governor's field selection
  changes, this diagnostic must be updated to match."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn op-reachability
  "MEASURES, for every op on the governor's allowlist, whether that op
  can EVER clear the governor when drafted by the PRODUCTION advisor
  against a fully registered+verified resident with an empty patch --
  i.e. the most favourable case that op will ever see.

  This is a derived diagnostic, not an assertion: it drafts with the
  real `rescare.advisor` and judges with the real `rescare.governor`.
  If an op comes back blocked here, no request of that kind can ever
  commit, no matter who files it. It reports the offending blocklist
  term so the finding is actionable, and it disappears by itself once
  the collision is resolved -- nothing about the defect is hardcoded."
  [db]
  (let [rid (:resident-id (first (filter #(and (:registered? %) (:verified? %))
                                         (store/all-residents db))))
        adv (advisor/mock-advisor)]
    (for [op (sort-by name governor/allowed-ops)]
      (let [request {:op op :resident-id rid :patch {}}
            proposal (advisor/advise adv db request)
            check (governor/check request :production proposal db)
            blob (scan-blob proposal)]
        {:op op
         :hard? (boolean (:hard? check))
         :rules (mapv :rule (:violations check))
         :terms (vec (filter #(str/includes? blob %) governor/scope-excluded-terms))
         :always-escalates? (contains? governor/always-escalate-ops op)}))))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-name [k] (if (keyword? k) (name k) (str k)))

(defn- decision-cell [{:keys [decision execution check-result]}]
  (case decision
    :commit "<span class=\"ok\">committed &middot; 自動</span>"
    :escalate "<span class=\"warn\">escalated &middot; 人間の承認待ち</span>"
    :hold (let [rule (-> check-result :violations first :rule)]
            (str "<span class=\"critical\">HARD hold &middot; " (esc (kw-name (or rule :unknown))) "</span>"))
    (str "<span class=\"muted\">" (esc (or (some-> execution kw-name) "in progress")) "</span>")))

(defn- scenario-row [{:keys [scenario result]}]
  (let [{:keys [label expect advisor phase request]} scenario]
    (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td class=\"num\">%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc label)
            (esc (kw-name (:op request)))
            (esc (:resident-id request))
            (esc phase)
            (esc (advisor-label advisor))
            (decision-cell result)
            (esc expect))))

(defn- resident-row [ledger {:keys [resident-id name registered? verified?]}]
  (let [facts (filter #(= resident-id (:resident-id (fact-proposal %))) ledger)
        holds (count (filter #(= :proposal-held (:op %)) facts))
        commits (count (filter #(= :proposal-committed (:op %)) facts))
        escalations (count (filter #(= :proposal-escalated (:op %)) facts))]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td></tr>"
            (esc resident-id) (esc name)
            (if registered? "<span class=\"ok\">registered</span>" "<span class=\"critical\">not registered</span>")
            (if verified? "<span class=\"ok\">verified</span>" "<span class=\"critical\">unverified</span>")
            commits escalations holds)))

(defn- hold-row [fact]
  (let [p (fact-proposal fact)
        v (first (:violations fact))]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td><code>%s</code></td><td>%s</td></tr>"
            (esc (kw-name (:rule v))) (esc (:resident-id p))
            (esc (kw-name (:op p))) (esc (:detail v)))))

(defn- ledger-row [fact]
  (let [p (fact-proposal fact)]
    (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
            (esc (kw-name (:op fact)))
            (esc (kw-name (or (:op p) :n-a)))
            (esc (or (:resident-id p) ""))
            (esc (or (some->> fact :violations (map (comp kw-name :rule)) (str/join ", "))
                     (some-> fact :record :decision kw-name)
                     "")))))

(defn- committed-row [{:keys [proposal decision]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td class=\"num\">%s</td><td>%s</td></tr>"
          (esc (kw-name (:op proposal))) (esc (:resident-id proposal))
          (esc (:summary proposal)) (esc (:confidence proposal))
          (esc (kw-name decision))))

(defn- phase-row [n]
  (let [auto (sort (map kw-name (phase/phase-table n)))]
    (format "        <tr><td class=\"num\">%s</td><td>%s</td><td>%s</td></tr>"
            (esc n) (esc (phase/describe-phase n))
            (if (seq auto)
              (str "<code>" (str/join "</code>, <code>" (map esc auto)) "</code>")
              "<span class=\"muted\">なし(全件エスカレーション)</span>"))))

(defn- reachability-row [{:keys [op hard? rules terms always-escalates?]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (kw-name op))
          (cond
            hard? (str "<span class=\"critical\">到達不能 &middot; 常に HARD hold ("
                       (esc (str/join ", " (map kw-name rules))) ")</span>")
            always-escalates? "<span class=\"warn\">人間の承認を経れば到達可能</span>"
            :else "<span class=\"ok\">到達可能</span>")
          (if (seq terms)
            (str "ブロックリストに一致: <code>" (str/join "</code>, <code>" (map esc terms)) "</code>")
            "<span class=\"muted\">一致なし</span>")))

(defn- reachability-section [report]
  (let [blocked (filter :hard? report)]
    (str
     "  <section class=\"card\">\n"
     "    <h2>操作ごとの構造的な到達可能性</h2>\n"
     "    <p class=\"muted\">許可リスト上の各操作について、"
     "<strong>本番の助言ノードが登録済み・検証済みの入居者に対して空の patch で起案した場合</strong>"
     "(その操作が遭遇しうる最も有利な条件)にガバナーを通過できるかを、"
     "実際に起案・審査して測定したもの。ここで「到達不能」と出た操作は、"
     "誰がどう申請しても永久にコミットできない。</p>\n"
     (when (seq blocked)
       (str
        "    <p><span class=\"critical\">このビルドが検出した欠陥</span> &mdash; "
        (esc (count blocked)) " 件の操作が構造的に到達不能:</p>\n"
        "    <p class=\"muted\">"
        "<code>rescare.governor/scope-excluded-terms</code> は自身の docstring で"
        "「(bare 'medic' ではなく 'medication dosing' のように)慎重に限定してあるので、"
        "この actor の正当な中核ユースケースと衝突することはない」と述べている。"
        "しかし実測ではその主張が成立していない &mdash; ブロックリストに限定されていない "
        "<code>薬</code> が含まれており、"
        "<code>rescare.advisor/propose-supply-request</code> が自ら書く定型文"
        "「非医<strong>薬</strong>品消耗品の調達調整のみ。投<strong>薬</strong>なし。」"
        "&mdash; つまり<em>投薬ではないと否定している文言そのもの</em> &mdash; に一致してしまい、"
        "許可された 5 操作のうち <code>coordinate-supply-request</code> が"
        "100% HARD hold になる。"
        "既存のテストはこれを捕捉していない(32 tests / 109 assertions は全て通る): "
        "<code>advisor_test</code> は助言ノードの出力形状のみ、"
        "<code>phase_test</code> は phase 表のみを検査しており、"
        "<strong>本番の助言ノードの出力をガバナーに通す経路を誰も検査していない</strong>。"
        "この欠陥はこのページを生成するために実パイプラインを端から端まで走らせた結果として現れた。</p>\n"
        "    <p class=\"muted\">この表は決め打ちではなく毎回測り直すので、"
        "衝突が解消されれば警告は自動的に消える。修正は本 item の範囲外"
        "(ガバナーの意味論の変更は別の変更として扱う)。</p>\n"))
     "    <table>\n"
     "      <thead><tr><th>操作</th><th>構造的な到達可能性</th><th>スコープ走査の一致</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map reachability-row report)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n")))

(defn- gate-row [op]
  (let [always? (contains? governor/always-escalate-ops op)
        auto3? (phase/can-auto-commit? op 3)]
    (format "        <tr><td><code>%s</code></td><td>%s</td></tr>"
            (esc (kw-name op))
            (cond
              always? "<span class=\"warn\">ALWAYS 人間の承認 &middot; どの phase でも自動化されない</span>"
              auto3? "<span class=\"ok\">phase 3: 清潔なら自動コミット</span>"
              :else "<span class=\"warn\">phase 3: 人間の承認</span>"))))

(defn- approval-section [{:keys [verdict committed-count approver-count escalated-count audit-approver]}]
  (str
   "    <p class=\"muted\">承認者の帰属はこのページを生成する時点で"
   "<strong>レジスタを実際に走査して判定</strong>している(既知の欠陥を決め打ちしない) &mdash; "
   "コミット済みレコード " (esc committed-count) " 件、エスカレーション " (esc escalated-count) " 件を検査した結果:</p>\n"
   "    <p>"
   (case verdict
     :retained
     (str "<span class=\"ok\">承認者はレコードに保持されている</span> &mdash; "
          (esc approver-count) " / " (esc committed-count) " 件のコミット済みレコードが承認者キーを持つ。")

     :audit-only
     (str "<span class=\"warn\">承認者は監査ログにのみ存在し、レコードには保持されていない</span> "
          "(audit only &mdash; not retained in record) &mdash; "
          "監査ファクトは <code>" (esc (pr-str audit-approver)) "</code> を持つが、"
          "コミット済みレコード " (esc committed-count) " 件のいずれも承認者キーを持たない。"
          "読み手が「誰も承認していない」と「ストアが保持しなかった」を区別できるよう明示する。")

     :no-approval-path
     (str "<span class=\"critical\">この actor には人間の承認経路がまだ存在しない</span> &mdash; "
          "コミット済みレコード " (esc committed-count) " 件はすべて phase 3 の<em>自動</em>コミットであり、"
          "承認者は存在しない。エスカレーション " (esc escalated-count)
          " 件は監査ログに記録されるが、<code>rescare.operation</code> には checkpointer も "
          "<code>interrupt-before</code> も resume 経路も無いため、人間が後から完了させる手段が無い"
          "(同 ns の docstring が自らそう述べている)。"
          "これは「ストアが承認者を捨てた」のではなく「承認という出来事がまだ起こり得ない」ということ &mdash; "
          "この 2 つは読み手にとって別物なので、黙って承認者欄を省略せず明示する。"))
   "</p>\n"))

(defn render
  "Renders the operator-console document from a completed `run-demo!`."
  [{:keys [db runs]}]
  (let [ledger (vec (store/ledger db))
        residents (store/all-residents db)
        holds (vec (hard-hold-facts db))
        committed (vec (store/coordination-log db))
        rules (hold-rules db)
        disclosure (approval-disclosure db)
        report (op-reachability db)]
    (str
     "<!doctype html>\n<html lang=\"ja\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
     "<title>cloud-itonami-isic-879 &middot; residential care operations</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>その他の居住型ケア活動 (ISIC 879) &mdash; Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · 投薬/臨床判断/身体拘束/親権/懲罰/終末期/安全当局の判断は永久に対象外</span>\n"
     "</header>\n"
     "<main class=\"container\">\n"

     "  <section class=\"card\">\n"
     "    <h2>この実行の要約</h2>\n"
     "    <p class=\"muted\">このページは手書きではなく <code>rescare.render-html</code> が"
     " <code>clojure -M:dev:render-html</code> で実行時に生成する。"
     " すべての行は <code>rescare.store/demo-data</code> の実データに対して"
     " <code>rescare.operation/run-proposal</code> を実際に走らせた結果であり、"
     " 決定は <code>rescare.governor</code> だけが下している。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>測定値</th><th>値</th></tr></thead>\n"
     "      <tbody>\n"
     (format "        <tr><td>シナリオ実行数</td><td class=\"num\">%s</td></tr>\n" (count runs))
     (format "        <tr><td>監査ファクト総数</td><td class=\"num\">%s</td></tr>\n" (count ledger))
     (format "        <tr><td>自動コミット</td><td class=\"num\">%s</td></tr>\n" (count committed))
     (format "        <tr><td>エスカレーション(人間へ)</td><td class=\"num\">%s</td></tr>\n"
             (count (filter #(= :proposal-escalated (:op %)) ledger)))
     (format "        <tr><td><strong>HARD hold</strong></td><td class=\"num\"><span class=\"critical\">%s</span></td></tr>\n"
             (count holds))
     (format "        <tr><td>発火した HARD ルール</td><td><code>%s</code></td></tr>\n"
             (str/join "</code>, <code>" (map esc rules)))
     (format "        <tr><td>構造的に到達不能な操作</td><td class=\"num\">%s / %s</td></tr>\n"
             (count (filter :hard? report)) (count report))
     "      </tbody>\n"
     "    </table>\n"
     "    <p class=\"muted\">HARD hold が 0 件の実行はビルドを失敗させる &mdash; "
     "<code>-main</code> は <code>hard-hold-facts</code> が空なら例外を投げるので、"
     "このページが幸福経路だけを見せる状態に静かに劣化することはない。</p>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD hold (この実行で実際に発火したもの)</h2>\n"
     "    <p class=\"muted\">HARD hold は人間が上書きできない。どの承認経路にも到達せず、"
     "<code>rescare.operation/hold-node</code> が監査ログに記録して終わる。"
     "以下の <code>detail</code> は <code>rescare.governor</code> が生成した文字列そのもの。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>ルール</th><th>入居者</th><th>操作</th><th>ガバナーの説明</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map hold-row holds)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>シナリオと判定</h2>\n"
     "    <p class=\"muted\">「助言ノード」列が <code>red-team</code> の行は、"
     "改ざん・低確信度の助言ノードを実際に <code>rescare.advisor/Advisor</code> として実装し、"
     "本物のパイプラインに通したもの &mdash; 本番の助言ノードはこれらを出力しない。"
     "ガバナーの 3 つの HARD 検査のうち <code>effect-not-propose</code> は、"
     "本番助言ノードが常に <code>:effect :propose</code> を設定するため、"
     "これ以外の方法では端から端まで到達できない。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>シナリオ</th><th>操作</th><th>入居者</th><th>Phase</th><th>助言ノード</th><th>判定</th><th>期待</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map scenario-row runs)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>入居者ディレクトリ</h2>\n"
     "    <p class=\"muted\">"
     "<code>rescare.store/demo-data</code> のシード。<code>registered?</code> と "
     "<code>verified?</code> はストア側の事実であり、提案の自己申告ではない &mdash; "
     "ガバナーは毎回ここを引き直す。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>ID</th><th>氏名</th><th>登録</th><th>検証</th><th>commit</th><th>escalate</th><th>hold</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial resident-row ledger) residents)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>操作ゲート (ResCareGovernor + phase table)</h2>\n"
     "    <p class=\"muted\">許可された操作は閉じた allowlist "
     (format "(%s 件)" (count governor/allowed-ops))
     "。これに含まれない操作は、それ自体がスコープ違反として HARD hold になる。"
     (format "確信度フロアは <code>%s</code>" governor/confidence-floor)
     " で、これを下回る提案は(禁止事項が無くても)人間へエスカレーションする。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>操作</th><th>ゲート (phase 3)</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map gate-row (sort-by name governor/allowed-ops)))
     "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     (reachability-section (op-reachability db))

     "  <section class=\"card\">\n"
     "    <h2>段階的ロールアウト (phase table)</h2>\n"
     "    <p class=\"muted\"><code>rescare.phase/phase-table</code> をそのまま描画したもの。"
     "<code>:flag-safety-concern</code> はどの phase の <code>:auto</code> 集合にも属さない &mdash; "
     "phase 表とガバナーの 2 層が同じ不変条件を独立に強制する。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Phase</th><th>説明</th><th>自動コミット可能な操作</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map phase-row [0 1 2 3])) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>コミット済み調整記録</h2>\n"
     (approval-section disclosure)
     "    <table>\n"
     "      <thead><tr><th>操作</th><th>入居者</th><th>要約</th><th>確信度</th><th>決定</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map committed-row committed)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>監査ログ(この実行)</h2>\n"
     "    <p class=\"muted\">追記のみの決定ファクト列。"
     "タイムスタンプは意図的に描画していない &mdash; "
     "<code>rescare.operation</code> が実時刻を刻むため、描画すると同一シードでも"
     "再実行のたびにページが変わってしまう。</p>\n"
     "    <table>\n"
     "      <thead><tr><th>ファクト</th><th>操作</th><th>入居者</th><th>根拠 / 決定</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>永久に対象外の判断領域</h2>\n"
     "    <p class=\"muted\">"
     (format "<code>rescare.governor/scope-excluded-terms</code> の %s 語を、" (count governor/scope-excluded-terms))
     "提案の <code>:op :summary :rationale :cites :value</code> を平坦化した文字列に対して"
     "毎回無条件に走査する。助言ノード自身の意図の説明は信用しない。"
     "これはロールアウトの途中段階ではなく、この actor の憲章が構造的に除外している領域:</p>\n"
     "    <p><code>"
     (str/join "</code> <code>" (map esc (sort governor/scope-excluded-terms)))
     "</code></p>\n"
     "  </section>\n"

     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        {:keys [db runs] :as run} (run-demo!)
        holds (hard-hold-facts db)]
    ;; Build-time invariant, not a convention: a run that demonstrates no
    ;; HARD hold has not demonstrated the governor, so it must not be
    ;; allowed to publish a page.
    (when (zero? (count holds))
      (throw (ex-info (str "refusing to write " out
                           ": the scenario produced 0 HARD governor holds "
                           "(:proposal-held audit facts). The console must "
                           "demonstrate at least one un-overridable block.")
                      {:out out
                       :scenarios (count runs)
                       :ledger-facts (count (store/ledger db))})))
    (spit out (render run))
    (println "wrote" out
             (str "(" (count runs) " scenarios, "
                  (count (store/ledger db)) " ledger facts, "
                  (count holds) " HARD holds ["
                  (str/join ", " (map kw-name (hold-rules db))) "], "
                  (count (store/coordination-log db)) " committed records, approver-attribution="
                  (name (:verdict (approval-disclosure db)))
                  ", structurally-unreachable-ops="
                  (count (filter :hard? (op-reachability db))) ")"))))
