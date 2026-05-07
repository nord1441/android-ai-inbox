# アプリアイコン エクスポート手順

端末用 adaptive icon は Android リソース内に VectorDrawable として組み込み済み:

- `app/src/main/res/drawable/ic_launcher_background.xml` (白ベタ)
- `app/src/main/res/drawable/ic_launcher_foreground.xml` (黒い棒 + 赤い点の小文字 i)
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` / `ic_launcher_round.xml`

Play Console 提出用の **512×512 ハイレゾ PNG** は別途書き出す必要がある。マスタは `art/ic_launcher.svg`。

## 必要環境

ImageMagick + librsvg(SVG delegate)。Fedora 環境想定:

```sh
sudo dnf install ImageMagick librsvg2-tools
```

`magick` は ImageMagick 7、`rsvg-convert` は librsvg2-tools に含まれる。どちらか一方でも良い。

## 推奨: rsvg-convert で書き出し (一番シャープ)

```sh
rsvg-convert -w 512 -h 512 art/ic_launcher.svg -o art/ic_launcher_512.png
```

SVG 側で既に白背景の `<rect>` を引いているので `-b white` 不要。

## 代替: ImageMagick で書き出し

```sh
magick -background white -density 1200 art/ic_launcher.svg -resize 512x512 art/ic_launcher_512.png
```

`-density 1200` は SVG 解析時の DPI。十分大きく取って後でリサイズすることでアンチエイリアスをきれいに出す。`-density` を省くと既定の 72 DPI で粗くなる。

## 検証

```sh
# 寸法とフォーマット確認
identify art/ic_launcher_512.png
# 例: art/ic_launcher_512.png PNG 512x512 512x512+0+0 8-bit sRGB ...
```

期待値: `512x512`, `8-bit sRGB` (または `16-bit`), 角丸なし、白背景。

## Play Console アップロード時の注意

- **角丸を自前で入れない** — Play Store 側でマスキングされる
- **正方形** で、透過なし(白背景)で OK
- **adaptive icon (端末側) と Play Store 用 PNG はサイズ・余白が別物** — 端末側は 108dp 内 72dp safe zone を意識して i を小さめに置いているが、`art/ic_launcher.svg` は safe zone を気にせず canvas を有効活用してロゴを大きめに置いてある(Play Store の小サイズ表示で潰れにくくするため)
- ロゴ自体(黒い棒 + 赤い点 + 白背景)は両方で同一なのでブランディングは揃う

## 仕上げを微調整したい場合

`art/ic_launcher.svg` を編集してから上記コマンドを再実行。Vector のマスタはここ一箇所。端末側 adaptive icon を変えたいときは `app/src/main/res/drawable/ic_launcher_foreground.xml` も併せて編集する(座標系は両方とも `viewBox 0 0 108 108`)。
