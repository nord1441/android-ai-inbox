# Play Store Listing 素材（提出版）

Play Console の「Main store listing」に貼り付ける本番テキスト。コピペ用にここに集約。

調査日: 2026-05-09 / `applicationId = uk.nordtek.aiinbox`

---

## App name (Play Store 表示名)

```
AI Inbox
```

`R.string.app_name` と一致（ランチャー / 通知 / Play Store すべて同名）。

---

## Short description (80 文字以内)

```
端末内 AI で要約・OCR・検索。クラウド送信なし、機内モードでも動きます。
```

38 文字（半角換算）。機能とプライバシーの両軸を訴求し、オフライン動作も明記。

---

## Full description (4000 文字以内)

```
すべてが端末の中で完結する、プライベートな AI インボックス。

他アプリから共有されたテキストやスクリーンショットを、端末内で動作する
小型 LLM (Gemma 4) と OCR が自動で要約・分類・タグ付け。あなたが入れた
情報は、要約結果も含めて、一切外部サーバに送信されません。

[主な機能]
- 共有メニューからテキスト・画像を1タップ取り込み
- 端末内 OCR（日本語 / Latin script）で画像内の文字を抽出
- 端末内 LLM が要約・タイトル・カテゴリ・タグ・人物・場所・URL・予定を抽出
- SQLCipher AES-256 で全文暗号化、添付画像も AES-256 GCM で暗号化保存
- FTS5 全文検索（要約・OCR・本文・タグを横断）
- 削除は Undo 付きソフト削除 → 確定後にファイル実体まで消去

[ネット接続について — 透明性のために]
本アプリの外部通信は、以下のダウンロードのみです:
  • 初回起動時: Gemma 4 モデル重みを Hugging Face から（数百 MB）
  • OCR 初回利用時: ML Kit のスクリプトモジュール（Latin / Japanese）を
    Google Play Services から（合わせて数〜十数 MB）

これらのダウンロードが完了すると、機内モードでも以下すべてが動作します:
  • 共有取り込み・OCR・要約・分類・検索・削除

ユーザーが本アプリに入れた情報（テキスト・画像・OCR結果・要約・タグ等）が
外部に送信されることは、いかなる場面でもありません。
クラッシュ解析・利用統計・広告 SDK も組み込んでいません。

[こんな人に]
- スクショやメモがどんどん溜まって整理が追いつかない人
- AI に要約してほしいが、データを外部サービスに渡したくない人
- 機内モードや電波の弱い環境でも使えるツールを求めている人

[必要環境]
Android 13 以上 / 推奨 RAM 8GB 以上 / 空き容量 1GB 以上
（Gemma 4 推論のため、一定の SoC 性能が必要です）
```

---

## アイコン

| 用途 | サイズ | 場所 |
|---|---|---|
| アプリ内 (adaptive) | mdpi〜xxxhdpi 自動 | `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (foreground / background レイヤー) |
| Play Console 提出用 hi-res | 512×512 PNG | `art/ic_launcher_512.png` |

---

## Phone screenshots（最低 2 枚、Play 推奨は 4-8 枚）

> 未作成。`art/screenshots/` 以下に配置予定。

撮影予定の画面（プライバシー訴求 → 機能 → UX の順）:

1. **Inbox 一覧**: 共有された複数アイテムが要約 + タグ付きで並んでいる状態
2. **詳細画面**: 共有されたスクショから OCR + 要約 + 抽出された予定が表示されている状態
3. **検索結果**: FTS5 で要約 / OCR 横断検索している状態
4. **設定 + モデル状態**: 「機内モードでも動作」を訴求できる構成（モデル DL 完了状態）

撮影方法 (adb):

```bash
adb shell screencap -p /sdcard/ai_inbox_screen.png
adb pull /sdcard/ai_inbox_screen.png art/screenshots/01-inbox.png
```

---

## Feature graphic (1024×500 PNG, 必須)

`art/feature-graphic.png` に配置済（SVG 元データは `art/feature-graphic.svg`）。

採用デザイン:
- 黒背景 (#0d0d0d) + 白い「lowercase i」モチーフ + 赤の tittle（アイコンの配色を反転）
- 左寄せに大きな「i」、右側にヘッドライン「クラウド送信ゼロ」+ サブコピー 2 行
- フォント: 端末の Hiragino Sans / Noto Sans CJK JP / YuGothic を fallback で指定

文言（PNG 内部の埋め込みテキスト）:
- ヘッドライン: クラウド送信ゼロ
- サブ 1: あなたのデータは端末から出ません
- サブ 2: 端末内 AI が要約・OCR・検索

差し替え方法: SVG を編集 → `rsvg-convert -w 1024 -h 500 art/feature-graphic.svg -o art/feature-graphic.png`

---

## Play ポリシー観点での注意

- 「100% offline」「never connects to the internet」のような断定は **避ける**（モデル DL があるため、文字通りに取れば事実と異なり、審査リジェクトや誤認広告クレームの火種）
- 「ユーザーデータが外部送信されない」「処理は端末内で完結する」「初回ダウンロード以降はオフラインで動作する」のような **正確な表現** に統一（本ドキュメントのテキストは全てこのトーンで揃えてある）
- Data Safety フォームの申告内容（[`data-safety-guide.md`](data-safety-guide.md)）と Listing 文言が一致していること（不一致は審査で指摘される）
