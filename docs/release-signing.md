# Release Signing 手順とバックアップ要件

Play Console に AAB をアップロードするための署名設定。**この文書に書かれた鍵・パスワードは絶対にリポジトリに入れない**。

調査日: 2026-05-09 / `applicationId = uk.nordtek.aiinbox`, `versionCode = 1`

---

## 鍵の構成（Play App Signing 前提）

Play Console で **Play App Signing** を有効化することを前提に、鍵は 2 階層になる:

| 鍵 | 用途 | 保管場所 | 紛失時 |
|---|---|---|---|
| **Upload key**（このリポジトリで扱う） | AAB を Play Console にアップロードする際の署名 | ローカル `~/keystores/aiinbox-release.jks` + パスワードマネージャ | Google Play Console から「upload key reset」フォームでリセット可能 |
| **App signing key** | Play Store 配信版 APK を最終署名する鍵 | Google が管理（HSM） | Google が管理しているので失わない。devが触ることはない |

**重要**: Play App Signing を使う限り、**upload key を失っても署名済みアプリの配信は止まらない**。ユーザーへの更新も継続できる（Google が新しい upload key を受け入れるよう設定変更してくれる）。これが「単一鍵で永久署名」方式と比べた最大の利点。

ただし「リセット手続きには数日〜1 週間かかる」「身分証明が必要」なので、**できる限り upload key を失わない**前提で運用する。

---

## バックアップしておかなければならないもの

以下 3 点を**別々の場所**に保管する。すべて揃わないと release アップロードができない:

1. **`aiinbox-release.jks`**（keystore ファイル本体）
   - サイズは数 KB。バイナリ
   - 推奨保管: 暗号化 USB / 暗号化外付 SSD / 自宅 NAS の暗号化ボリューム / クラウドの E2E 暗号化ストレージ
   - 平文で同期サービス（無印 Dropbox 等）に置かない

2. **store password と key password**
   - パスワードマネージャ（1Password / Bitwarden / KeePassXC など）に「**AI Inbox release signing**」エントリで保存
   - エントリ内に: store password, key password, key alias, keystore file の保管場所メモ
   - **複雑かつユニーク**にする（24 文字以上、英数記号混在）。鍵自体は再発行不能（Play upload key reset は可能だが手間）

3. **Play Console 側のリカバリ情報**
   - Play Console で Google アカウント自体に紐付くので、**そのアカウントの 2FA 復旧コード**を別途保管
   - アカウントを失うと Play Console 全体にアクセスできなくなる

---

## 初回セットアップ手順（手作業）

> **このセクションのコマンドは開発者本人が一度だけ手で実行する。Claude や CI が代行してはいけない**（パスワードを生成 / 入力する人物 = 鍵の管理責任者であるべき）。

### 1. Keystore を生成する

リポジトリ外のディレクトリに保管する:

```bash
mkdir -p ~/keystores
keytool -genkeypair -v \
  -keystore ~/keystores/aiinbox-release.jks \
  -storetype JKS \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -alias aiinbox \
  -dname "CN=AI Inbox, OU=Personal, O=nordtek, L=Tokyo, ST=Tokyo, C=JP"
```

- `-keysize 4096`: 推奨。2048 でも Play は受け付けるが 4096 が現代の規範
- `-validity 10000`: 約 27 年（upload key は十分長く）
- `-alias aiinbox`: 後で `gradle.properties` の `AI_INBOX_RELEASE_KEY_ALIAS` と一致させる
- `-dname` の値は Play Console 上で公開されない（upload key の場合）。ただし keystore に埋め込まれるので**個人を特定したくない情報は入れない**

実行中に **store password** と **key password** を訊かれる。両方とも長くてランダムなものにし、即座にパスワードマネージャへ保存。

> NOTE: 一部 `keytool` ビルドでは `-storetype JKS` を指定しないと PKCS12 形式（`.p12`/`.pkcs12` 互換）になる。Play Console は両方受け付けるので問題ないが、本ドキュメントは JKS 前提で書いている。

### 2. `~/.gradle/gradle.properties` に資格情報を書く

ファイルが無ければ作成:

```properties
# AI Inbox release signing — set up per docs/release-signing.md
# DO NOT COMMIT this file. It lives at $HOME and is outside any git repo.
AI_INBOX_RELEASE_STORE_FILE=/home/<USER>/keystores/aiinbox-release.jks
AI_INBOX_RELEASE_STORE_PASSWORD=<store password>
AI_INBOX_RELEASE_KEY_ALIAS=aiinbox
AI_INBOX_RELEASE_KEY_PASSWORD=<key password>
```

- パス `<USER>` は実際のユーザ名に置き換える
- ファイルパーミッションを `chmod 600 ~/.gradle/gradle.properties` で絞る（他ユーザから読めないように）
- このファイルは **`$HOME`** にあり、いかなるリポジトリ内にも存在しない。git に乗ることはない

### 3. Play Console 側の登録（Play App Signing）

1. Play Console を開く
2. 対象アプリ（新規作成時）の **「Setup」 → 「App integrity」 → 「App signing」**
3. 「Use Play App Signing」を有効化（デフォルト推奨）
4. 「Upload key certificate」のセクションで、生成した keystore の証明書を登録
   - 証明書 PEM をエクスポート: `keytool -export -rfc -keystore ~/keystores/aiinbox-release.jks -alias aiinbox -file aiinbox-upload-cert.pem`
   - この PEM ファイルを Play Console 画面にアップロード
5. 「App signing key」は Google が新規生成（推奨フロー）

---

## ビルドと検証

### Release AAB をビルド

```bash
./gradlew :app:bundleRelease
```

成功すると `app/build/outputs/bundle/release/app-release.aab` が出力される。

### 署名されているか確認

```bash
jarsigner -verify -verbose -certs \
  app/build/outputs/bundle/release/app-release.aab | head -20
```

- `jar verified.` が出れば OK
- 一番下の `Signed by` 行で alias と CN が `aiinbox` / `AI Inbox` になっていることを確認

または `apksigner`（Android SDK 同梱）:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs \
  app/build/outputs/bundle/release/app-release.aab
```

### 資格情報なしで実行した場合

`~/.gradle/gradle.properties` に `AI_INBOX_RELEASE_*` プロパティが無い状態で `bundleRelease` を実行すると、**unsigned AAB** が出力される（ビルド自体は成功）。これを Play Console にアップロードすると弾かれる。

意図せず unsigned AAB を作ってしまうのを避けたい場合、ビルド前に確認:

```bash
./gradlew :app:properties | grep AI_INBOX_RELEASE_
```

4 行揃って表示されれば signing 設定は読み込まれている。

---

## バージョン管理

`versionCode` は AAB ごとに +1 しないと Play は同一 versionCode を弾く（`app/build.gradle.kts` の `defaultConfig.versionCode`）。release ごとに手動でインクリメント、Git tag と連動させる方針は別途検討。

---

## 失敗ケースと対処

| 症状 | 原因 | 対処 |
|---|---|---|
| `bundleRelease` が unsigned AAB を出す | `AI_INBOX_RELEASE_*` プロパティ未設定 | `~/.gradle/gradle.properties` を確認 |
| `Keystore was tampered with, or password was incorrect` | パスワード違い、または keystore ファイル破損 | パスワードマネージャ確認 → ダメならバックアップから復元 |
| `Failed to read key aiinbox from store` | `AI_INBOX_RELEASE_KEY_ALIAS` の値が keystore 内の alias と不一致 | `keytool -list -keystore ~/keystores/aiinbox-release.jks` で alias を確認 |
| Play Console が「This APK was signed with the wrong upload certificate」 | 別の keystore で署名した | 正しい keystore で再ビルド。どうしても元の keystore を失った場合は upload key reset 申請 |
| Keystore ファイル自体を失った | 災害・誤削除 | バックアップから復元 → 無い場合は Play Console から upload key reset 申請（数日〜1 週間） |

---

## チェックリスト（公開前に確認）

- [ ] keystore ファイルが `~/keystores/aiinbox-release.jks` に存在する
- [ ] keystore のバックアップを別媒体に取った（暗号化 USB / クラウド E2E など）
- [ ] パスワード 2 種をパスワードマネージャに保存した
- [ ] `~/.gradle/gradle.properties` に 4 プロパティが揃っている
- [ ] `~/.gradle/gradle.properties` のパーミッションが `600`
- [ ] Play Console で Play App Signing を有効化し、upload 証明書を登録した
- [ ] `./gradlew :app:bundleRelease` が成功し、`jarsigner -verify` が通る
- [ ] `git status` で keystore / properties が trackされていないことを確認した
