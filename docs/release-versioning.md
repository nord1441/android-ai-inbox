# Release Versioning Policy

Android Play Store は同一 `versionCode` の AAB を弾く。本プロジェクトでは **versionCode を git commit 数から自動導出** することで「うっかり同じ versionCode で上げて Play Console に弾かれる」事故を構造的に不可能にする。

調査日: 2026-05-09

---

## 採番ルール

| フィールド | 採番方法 | 例 |
|---|---|---|
| `versionCode` | `git rev-list --count HEAD` で導出（自動） | 168, 169, 170, ... |
| `versionName` | `app/build.gradle.kts` に手動で semver を記載 | `0.1.0`, `0.2.0`, `1.0.0` |

### versionCode を自動化した理由

- **Play は `versionCode` の単調増加を強制**。重複は即リジェクト
- commit 数は単調増加・一意・不可逆。手動採番より堅牢
- リリースのたびに人間が `versionCode = N+1` と書き換える運用は、ブランチを切り替えた瞬間にミスを誘発しやすい
- git tag 連動方式は「tag を打ち忘れたまま release ビルド」で破綻する。commit 数なら HEAD さえ進んでいれば常に有効

### versionName を手動にした理由

- ユーザーに見せる文字列なので、人間の意図（メジャー/マイナー/パッチの判断）が必要
- semver は機械的に決まるものではない（破壊的変更の有無等は人間判断）

---

## 実装箇所

`app/build.gradle.kts` の冒頭で `gitCommitCount` を計算し、`defaultConfig.versionCode` に注入:

```kotlin
val gitCommitCount: Int = try {
    ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
        .inputStream
        .bufferedReader()
        .readText()
        .trim()
        .toIntOrNull()
        ?: 1
} catch (_: Exception) {
    1
}

android {
    defaultConfig {
        versionCode = gitCommitCount
        versionName = "0.1.0"
        // ...
    }
}
```

`ProcessBuilder` を直接使っているのは Gradle の configuration cache を壊さないため。`providers.exec { }` はビルドごとに re-evaluate されてしまうケースがある。

---

## リリース手順

通常のリリースで `versionCode` を触る必要はない。**何もしなくても commit が進めば `versionCode` も上がる**。

`versionName` は意図のある変更時のみ更新:

1. `app/build.gradle.kts` を開き `versionName = "X.Y.Z"` を更新
2. コミット（このコミット自体も `versionCode` を +1 する）
3. `./gradlew :app:bundleRelease` で AAB 生成
4. Play Console にアップロード

### 現在の versionCode を確認したい

```bash
git rev-list --count HEAD
```

ビルド済み AAB の versionCode を確認したい場合は `aapt`/`bundletool`:

```bash
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging \
  app/build/outputs/bundle/release/app-release.aab 2>/dev/null \
  | grep "versionCode"
```

---

## 失敗ケースと対処

| 症状 | 原因 | 対処 |
|---|---|---|
| `versionCode = 1` のまま AAB が出来る | git が PATH に無い、または `.git` が無い場所でビルド | git をインストール / git clone した状態でビルドする |
| Play Console から「`versionCode = N` は既存」と弾かれる | 本当に同一 commit の状態で 2 回 release した | 何か小さい変更を commit して再ビルド（commit 数 +1） |
| CI で versionCode が想定より小さい | shallow clone（`actions/checkout@v4` のデフォルトは `fetch-depth: 1`）で commit 履歴が 1 個だけ | CI 設定で `fetch-depth: 0` を指定して全履歴取得 |
| ローカルブランチが他開発者の origin/main より進んでいて、merge 後に versionCode が逆戻りに見える | merge commit ではなく rebase した | 単独開発のため通常は問題ない。複数人なら main を信頼する運用にする |

---

## 将来の拡張

- **release tag 連動**: 必要になったら `git describe --tags` から `versionName` を自動導出する方式に切替可能
- **CI 自動化**: `fetch-depth: 0` で checkout し、`./gradlew bundleRelease` を実行すれば versionCode は自動で正しくなる
- **versionCode を 100,000 オフセットで管理**: 万一 commit 数で足りなくなったら `versionCode = 100_000 + gitCommitCount` のような底上げ。Play Console の versionCode 上限は 2,100,000,000 なので実害的には心配無用
