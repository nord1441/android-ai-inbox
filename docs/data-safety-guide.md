# Play Console Data Safety フォーム入力ガイド

Play Console の「Data Safety」フォーム（App content → Data safety）に何を入力すべきかを、本アプリの実装と整合した形でまとめた**内部ガイド**。Play Console 上の質問項目に沿って、推奨回答と根拠を併記する。

このドキュメントは Pages サイトには公開せず、リポジトリ内部のリファレンスとして保持する。実際のユーザー向け説明は [`privacy-policy.md`](privacy-policy.md) を参照。

調査日: 2026-05-09 / `applicationId = uk.nordtek.aiinbox`

---

## Data Safety の判定キー

Play Console の "data collection" は **「ユーザーデータが端末から外部サーバーに送信される」** ことを指す。本アプリの実装は次の通り:

- 共有 intent で受け取ったテキスト・画像、OCR 結果、要約結果、タグ等は **端末ローカル DB に保存** される
- 一切外部送信しない（クラッシュ解析・利用統計・広告 SDK 不在）
- 外部通信は **モデル/モジュールのダウンロードのみ**（ユーザーデータのアップロードは伴わない）

→ Play の定義上、**Data collection = No**、**Data sharing = No** が一貫した回答になる。

---

## セクション 1: Data collection and security

### 1.1 Does your app collect or share any of the required user data types?

**回答**: **No**

**根拠**:
- ユーザーが入れた情報（テキスト・画像・OCR 結果・要約・タグ）は端末ローカルにのみ保存。外部送信なし
- HF / Google Play Services への通信はモデル/モジュール DL のダウンロード方向のみ。標準 HTTP メタデータ（IP, User-Agent）は付随するが、これらは Play の collection 定義から除外される（送信目的が「自分のサーバーに自分のデータを送る」ものではない）

→ "No, my app doesn't collect or share any user data" を選択。

### 1.2 Is all of the user data collected by your app encrypted in transit?

**回答**: **Yes**

**根拠**:
- アプリ自身がユーザーデータを送信しないので「データ in transit」は厳密には存在しない
- ただし、モデル DL は HTTPS（Hugging Face、Google Play Services）。Play の質問は「モバイルから外部に送るユーザーデータが暗号化されているか」を問うているが、本アプリではユーザーデータの送信が無い → "Yes" を選択するのが安全

### 1.3 Do you provide a way for users to request that their data is deleted?

**回答**: **Yes**

**根拠**:
- アプリ内で各アイテムを個別削除可能（Undo 付きソフト削除 → 確定後にファイル実体まで消去）
- アプリのアンインストールで `/data/data/uk.nordtek.aiinbox/` 配下が OS により全消去される
- 外部サーバーにデータが無いため、「サーバー側削除リクエスト」の概念は不要

### 1.4 Has your app been independently reviewed against a global security standard?

**回答**: **No**

**根拠**: 個人開発、第三者監査なし。No で問題なし。

---

## セクション 2: Data types — 各カテゴリ "No" の根拠

Play Console は以下のカテゴリそれぞれに対し「収集するか」「共有するか」を聞く。本アプリの回答は **すべて No**。各カテゴリで誤解されやすい点を補足する。

### 2.1 Personal info (Name, Email, User ID, Address, Phone number, Race/ethnicity, etc.)

**回答**: 全項目 No

- アプリは個人情報を取得・要求しない
- 共有テキストに人名等が含まれる可能性はあるが、それは「ユーザーが他アプリで作成したコンテンツ」であり、本アプリは外部送信しない

### 2.2 Financial info

**回答**: No
- 一切扱わない

### 2.3 Health and fitness

**回答**: No

### 2.4 Messages (Emails, SMS or MMS, Other in-app messages)

**回答**: No

**注意点**: 共有 intent でメールやメッセージのテキストを受け取ることはある（ユーザーが他のメールアプリから「共有」した場合）が、Play の定義では「アプリが外部に送信する」ことが collection の条件。本アプリは送信しない → No。

### 2.5 Photos and videos

**回答**: No

**注意点**: 共有 intent で画像を受け取り、端末内 OCR 後に暗号化保存する。Play の定義では送信があれば collection だが、本アプリは送信しない → No。

### 2.6 Audio files

**回答**: No

### 2.7 Files and docs

**回答**: No

**注意点**: 取り込んだテキストや画像は「ファイル」とも捉えられるが、外部送信なし → No。

### 2.8 Calendar

**回答**: No

**注意点**: 端末内 LLM が「予定」を抽出するが、これは取り込みテキストから推論した情報で、システムカレンダーへのアクセスではない。ユーザーが「カレンダーに追加」アクションを押すと標準の `Intent.ACTION_INSERT` をカレンダーアプリに投げるだけで、本アプリがカレンダーデータを取得するわけではない → No。

### 2.9 Contacts

**回答**: No

### 2.10 App activity (App interactions, In-app search history, Installed apps, Other user-generated content, Other actions)

**回答**: 全項目 No

**注意点**: ユーザーの「検索クエリ」や「タグ操作」「閲覧履歴」は端末内に記録されるが、外部送信なし → No。

### 2.11 Web browsing history

**回答**: No

### 2.12 App info and performance (Crash logs, Diagnostics, Other app performance data)

**回答**: 全項目 No

**根拠**:
- Crashlytics 等のクラッシュ解析 SDK を組み込んでいない
- Firebase Performance / Analytics も未導入
- 完全にゼロ

### 2.13 Device or other IDs

**回答**: No

**根拠**: Android ID、広告 ID、デバイス識別子の取得・送信はしない。

---

## セクション 3: Privacy policy URL

**入力値**: `https://nord1441.github.io/android-ai-inbox/privacy-policy.html`

公開済みであることを確認: HTTP 200 で配信中（GitHub Pages from /docs）。

---

## セクション 4: Security practices (情報補足、フォーム外でも記述箇所あり)

| 項目 | 値 |
|---|---|
| 保存時の暗号化 | SQLCipher AES-256（DB）+ AndroidX EncryptedFile AES-256-GCM（添付画像） |
| 鍵管理 | Android Keystore で保護、端末から取り出し不可 |
| 通信時の暗号化 | HTTPS（モデル DL のみ。ユーザーデータの送信なし） |
| データ削除 | アプリ内個別削除 (Undo 付きソフト削除 → ファイル実体消去) / アンインストールで全消去 |
| 外部 SDK | 広告・Analytics・Crashlytics 等は不在 |

---

## セクション 5: フォーム送信前の自己チェック

- [ ] privacy-policy.html が HTTP 200 で配信されている
- [ ] privacy-policy.html の本文と本ガイドの回答が齟齬していない
- [ ] 「No data collected」を選んだ後、Play Console の review preview で「This app does not collect or share any user data」と明示されることを確認
- [ ] 暗号化 in transit は "Yes" にチェック
- [ ] データ削除手段は "Yes" にチェック、説明欄に「アプリ内個別削除 + アンインストールで全消去」と明記
- [ ] 第三者監査は "No"

---

## 想定される審査からの差し戻しと対処

| 差し戻し理由 | 原因 | 対処 |
|---|---|---|
| 「Permission と申告内容が不整合」 | INTERNET 権限を持つのに data collection = No | 質問に補足: モデル/モジュール DL のみで、ユーザーデータの送信は伴わない（policy 4.1, 4.2 で説明済み） |
| 「Privacy policy URL not accessible」 | Pages 公開直後でキャッシュ未反映 | 数時間待って再提出。確実なのは別ブラウザの incognito で確認 |
| 「Data deletion method not described in privacy policy」 | policy 6 の記述不足 | 既に記述済（policy セクション 6） |
| 「Foreground service の用途が不明」 | FOREGROUND_SERVICE_DATA_SYNC の用途説明なし | Play Console の Permission 説明欄に「LLM 推論 / モデル DL 中の OS kill 防止」と記載 |
