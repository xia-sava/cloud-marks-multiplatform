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
