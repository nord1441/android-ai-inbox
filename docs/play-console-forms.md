# Play Console フォーム入力ガイド (Content rating / Target audience / Permission)

Play Console で**フォーム入力するだけ**の項目のうち、コードに手は入らないが申告内容を予め固めておきたいもの。Data Safety フォームは [`data-safety-guide.md`](data-safety-guide.md) に分けてある。Internal testing track へのアップロード手順は本ドキュメントの末尾に。

調査日: 2026-05-09 / `applicationId = uk.nordtek.aiinbox` / `targetSdk = 35`

---

## 1. Content rating questionnaire (IARC)

Play Console: **Setup → App content → Content rating**。IARC が運営する自動分類アンケート。本アプリのコンテンツは「ユーザーが他アプリから共有した任意のテキスト・画像を端末内で要約・分類するだけ」で、アプリ側からプッシュするコンテンツは存在しない。よって全ての「アプリが提供するコンテンツ」系の質問に "No" 回答。

### カテゴリ選択

最初に「Category」を選ぶ。本アプリは **「Reference, News, or Educational」** が最も近い（Productivity を含むカテゴリ）。実際の選択肢はバージョンによって変わる可能性あり、Productivity 寄り / Tools 寄りのいずれかで OK。

### 設問への回答（推奨）

| 設問カテゴリ | 設問例 | 回答 | 根拠 |
|---|---|---|---|
| Violence | グラフィック / リアル / 戯画的暴力 | No | アプリ自体に暴力表現なし |
| Sexual content | 性的コンテンツ / ヌード / 性的言及 | No | なし |
| Profanity | 粗野な言葉 / 侮辱 | No | アプリ自体は提供しない（ユーザー入力は端末内処理のみ） |
| Drugs, alcohol, tobacco | 違法/合法薬物・アルコール・タバコ表示 | No | なし |
| Gambling | 賭博 / 換金可能 | No | なし |
| Crude humor / horror | 下品なユーモア / ホラー | No | なし |
| User-generated content (others can see) | 他ユーザーがアクセスできる UGC 機能 | No | 本アプリは個人用、共有・コメント・公開機能なし |
| Online interactions / chat | ユーザー間チャット / 対戦 / マッチング | No | なし |
| Sharing of personal info with users | 他ユーザーと個人情報を共有する機能 | No | なし |
| Real-time communication | リアルタイム通信機能 | No | なし |
| In-app purchases / digital goods | 課金 / 仮想アイテム | No | 完全無料、IAP なし |
| Location sharing | 他ユーザーへの位置共有 | No | なし |

### 想定される判定

ほぼ全 "No" → IARC 判定で **「Everyone」 / 「3+」 / 「全年齢」** 相当の最低レーティングが付く。日本固有のレーティングも付与される（CERO 相当ではないが Play 独自カテゴリ）。

---

## 2. Target audience

Play Console: **Setup → App content → Target audience and content**。

### 推奨選択

- **Target age groups**: `13-15`, `16-17`, `18 and over` をチェック
- **Designed for Families に登録するか?**: No（子供向け（under 13）を含めない）

### 根拠

- 本アプリは生産性ツール（メモ・スクショの AI 整理）。13 歳未満特有の配慮（保護者制御、教育的コンテンツ等）は不要
- 13 歳未満を含めるとデータ収集の追加規制（COPPA 相当）が適用される。本アプリはデータ収集ゼロなので技術的には問題ないが、申告と運用が複雑化するため除外推奨
- 18+ を含めるのは「Android 13 以上 + RAM 8GB 推奨」の要件で実質大人向けデバイスになるため、誇張ではなく自然

### "Appeals to children" 設問

Play は「アプリは 13 歳未満にも appeal するか?」と聞いてくる。**No** で回答（生産性ツールであり、子供を引きつけるキャラクター・ゲーム要素・教育コンテンツがない）。

---

## 3. Permission usage descriptions

Play Console は **危険権限（dangerous permissions）と一部の特殊権限**について理由説明を要求する。本アプリで申告が必要なのは下記。

### 3.1 `FOREGROUND_SERVICE_DATA_SYNC` (Android 14+ で targetSdk 34+ 必須申告)

Play Console: **Policy → App content → Foreground service permissions** で「dataSync を使う理由」を入力。

**入力推奨文**:

> 本アプリには 2 種類の foreground service があります。
>
> 1. `LlmInferenceService`: 端末内で動作する小型 LLM (Gemma 4) による要約・分類・予定抽出を実行します。1 件あたり数十秒〜数分の推論処理で、途中で OS に kill されるとユーザーが取り込んだ情報の要約が完了せず UX が大きく損なわれるため、foreground service として処理が完走するまでアプリを保持します。
>
> 2. WorkManager 経由の `ModelDownloadWorker`: Hugging Face から Gemma モデル重み（数百 MB）と Google Play Services から ML Kit OCR モジュール（数〜十数 MB）をダウンロードします。これらは初回 1 回のみで、長時間ダウンロードが OS 側のメモリ圧で中断されることを防ぐため foreground service として実行します。
>
> どちらも `dataSync` カテゴリが最も適切です（バックグラウンド転送 / 端末内データ処理が継続することの宣言）。

### 3.2 `POST_NOTIFICATIONS` (runtime permission)

Play Console での declaration form 入力は通常不要だが、UI 上で表示される説明テキスト（Compose の rationale dialog 等）として用意しておく:

**ユーザーへの説明文**（アプリ初回起動時に表示する rationale 用）:

> モデルのダウンロード進捗、要約完了、抽出された予定の検出、をお知らせするために通知を使います。通知をオフにしてもアプリは動作しますが、バックグラウンド処理の状況がわかりにくくなります。

実装方針: 初回起動時に Activity から `requestPermissions(POST_NOTIFICATIONS)` を投げる。拒否されてもアプリは動く（通知が出ないだけ）。

### 3.3 `INTERNET`

Play Console での declaration 不要。本アプリの利用目的は「モデル / OCR モジュールのダウンロードのみ。ユーザーデータの送信なし」（Data Safety / プライバシーポリシーで既に記述）。

### 3.4 `FOREGROUND_SERVICE`

Play Console での declaration 不要（normal permission）。

---

## 4. Internal testing track アップロード手順

### 4.1 事前準備

- [ ] release-signed AAB が手元にある (`./gradlew :app:bundleRelease`)
- [ ] AAB の versionCode が前回 upload と異なる（commit 数自動導出なので通常は問題なし）
- [ ] Play App Signing が有効化されている（Play Console 側設定）
- [ ] 「アプリのプライバシーポリシー」フィールドに `https://nord1441.github.io/android-ai-inbox/privacy-policy.html` がセット済み
- [ ] Data Safety フォーム提出済み
- [ ] Content rating questionnaire 提出済み
- [ ] Target audience 設定済み
- [ ] Foreground service permissions declaration 提出済み
- [ ] Store listing 素材すべて提出済み（icon / feature graphic / phone screenshots × 4 / short / full description）

### 4.2 アップロード

1. Play Console → 対象アプリ → **Testing → Internal testing → Create new release**
2. AAB をドラッグ & ドロップ
3. Release name: `0.1.0 (build 181)` のように versionName + versionCode を併記すると後で見やすい
4. Release notes: 「初回内部テスト版」程度で OK（後日 Closed/Production に進める時に拡充）
5. **Save → Review release → Start rollout to Internal testing**

### 4.3 Tester invite

1. Play Console → **Testing → Internal testing → Testers** タブ
2. 「Create email list」で自分のテスト用 Google アカウントのメールを追加
3. 「Opt-in URL」をコピーしてテスター（自分）の Android デバイスでブラウザから開く
4. 「Become a tester」を承認後、Play Store でアプリ検索 → install

### 4.4 検証

- [ ] アプリが正常に起動
- [ ] モデル DL が完了
- [ ] 共有 intent → 取り込み → 要約 → 削除の通常フロー
- [ ] FS sync 設定 → 同期成功
- [ ] アンインストール → データ全消去確認
- [ ] 機内モード状態でも （DL 後の）取り込み・要約・検索が動く

### 4.5 想定される審査タイミング

- Internal testing は審査がほぼ即時 (~数時間)
- Closed testing は約 1〜数日
- Production への昇格は初回提出だと **数日〜2 週間**かかる場合あり

---

## 5. Closed / Production への昇格時の追加考慮

Internal testing で問題なければ Closed → Production と進む。Production リリース時に追加で気をつけること:

- **Pre-launch report** が自動で動く（Firebase Test Lab の自動テスト）。クラッシュやポリシー違反が検出されると差し戻し
- **App quality** タブで Android Vitals (ANR rate / Crash rate) が監視される。閾値超過は配信制限の対象
- 初回 Production リリース前に「Send a test version to Closed testing for at least 14 days」を求められる場合がある（地域 / 種別による）
