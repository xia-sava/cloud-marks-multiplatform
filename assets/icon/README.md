# アイコンの素材

Cloud Marks のアイコン（雲としおり）の元になる素材。各アプリのアイコンはここから作る。

| ファイル | 内容 |
|---|---|
| `icon-512.png` | 512×512 の完成形。背景色（`#80FFC0`）つき。縮小して作る画像の元 |
| `ic_cloud_marks.svg` | 雲としおりの形。背景を含まない |
| `ic_launcher_foreground.xml` | Android のアダプティブアイコンの前景（108dp のベクタードローアブル） |
| `ic_launcher_background.xml` | アダプティブアイコンの背景色。`<color name="ic_launcher_background">` の値だけを持つ |

## 作った画像

- `apps/extension/icons/icon-{16,32,48,128}.png` — `icon-512.png` を LANCZOS で縮小したもの
- `apps/cm/desktopApp/src/main/resources/icon.png` — 窓のアイコン（256px）
- `apps/cm/desktopApp/icons/cloudmarks.ico` — 配布物の exe とインストーラのアイコン（16・24・32・48・64・128・256px）
- `apps/cm/androidApp/src/main/res/drawable/ic_launcher_foreground.xml`・`res/values/ic_launcher_background.xml` — Android の
  アダプティブアイコンの前景と背景色。`ic_launcher_foreground.xml` と `ic_launcher_background.xml` の複製。
  `res/mipmap-anydpi/ic_launcher.xml` と `ic_launcher_round.xml` がこれを組み合わせ、テーマのアイコン（monochrome）にも前景を使う
  （minSdk 30 なので、ラスター画像は置かない）

## 出典

このアイコンは、Flaticon（https://www.flaticon.com/）のアイコンを組み合わせて変えたもの。元のアイコンの形を 1 つの絵に組み合わせ、
背景色を足し、アプリごとの形式と大きさに変えている。元のアイコンのライセンスは
クリエイティブ・コモンズ 表示 3.0 非移植（CC BY 3.0、https://creativecommons.org/licenses/by/3.0/）。

- [Smashicons](https://www.flaticon.com/authors/smashicons) のアイコン（www.flaticon.com）
- [Freepik](http://www.freepik.com) のアイコン（www.flaticon.com）

配布物に入れる表記は、リポジトリ直下の `THIRD-PARTY-NOTICES.md` の「Icon」にある。ここから作った画像を足したり、
作り直したりしたときも、表記はそのまま要る。
