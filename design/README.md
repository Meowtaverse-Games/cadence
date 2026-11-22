# Cadence の共通デザイン

ポストサイバーパンク。暗い紺の地、琥珀の集中、青緑の自由、等幅の時刻、控えめな光。
iOS と Windows の共通資料。SVG は静的な標準色版、Windows の実行時描画は
`windows/Cadence.Tray.Logic/Design.cs` の幾何を使う。幾何の変更時は SVG もそろえる。

| 名前 | 値 | 用途 |
|---|---|---|
| ground | `#0B1216` | 窓の地、アイコンの板 |
| surface | `#131D22` | カード、入力欄 |
| surface2 | `#1A272D` | ボタン、輪の下地 |
| line | `#2A3A41` | 枠線、区切り |
| text | `#E9F1EF` | 本文、アプリのひし形 |
| muted | `#9DB0AE` | 補足、ラベル |
| focus | `#FFB648` | 集中（文字を載せる場合は ground） |
| free | `#2FB9A5` | 自由 |
| alert | `#FF8A7A` | 警告、エラー |

| 文字 | Windows のフォント（左が優先） |
|---|---|
| 日本語本文 | Yu Gothic UI / Segoe UI |
| 時刻、exe 名、小さな英字 | Cascadia Mono / Consolas |
| FOCUS / FREE / ALERT | Segoe UI Semibold、大文字、字間を空ける |

フォントは Windows にあるものだけを使用し、同梱・ダウンロードしない。
本文と muted の ground / surface 上のコントラストは 4.5:1 以上。
高コントラストでは Windows のシステム色を優先し、形の違いを残す。

| マーク | 幾何 |
|---|---|
| アプリ（viewBox 100） | ground の地、中心 (50,50)、半径30、線幅9、下地 surface2 |
| アプリの弧 | 12時から時計回り。focus 38.9 → 隙間2.5 → free 18.93 → 隙間2.5 を3周期（円周約188.5） |
| アプリの中心 | text、14×14、角丸1.5、45度回転 |
| トレイ（viewBox 32） | (1,1)–(31,31)、角丸7、ground の板、輪の中心 (16,16)、半径10 |
| FOCUS | focus、線幅4、弧17.94 / 隙間3 を3周期。中心は focus の塗り |
| FREE | free、線幅2.6、弧17.94 / 隙間3 を3周期。中心は ground、free の縁取り1.6 |
| ALERT | alert、線幅4、弧40 / 隙間22.83。中心は alert の塗り |
| トレイの中心 | 6×6、角丸0.8、45度回転 |

弧の長さは SVG の絶対長（線幅の倍数ではない）。すべて12時から時計回り、端は平ら。
丸め誤差を累積させないため GDI+ の3周期の開始角は120度ずつ進める。
16 / 20 / 24 / 32px でアンチエイリアス描画する。

Windows ではぼかしの光を集中時の枠線に、角丸カードを矩形に、タグの字間を通常の空白に置き換える。
今日のバーは集中を斜線、自由を不透明度約55%で示し、現在位置に縦線を引く。

通知領域はタスクバーの DPI に応じた小アイコンのサイズを使用する。
[Microsoft のアイコンサイズの説明](https://learn.microsoft.com/en-us/windows/win32/menurc/about-icons)に従い、
`GetDpiForWindow` と `GetSystemMetricsForDpi(SM_CXSMICON)` を使う。
設定画面が閉じている間も既存の2秒の表示更新でサイズと高コントラストを再確認し、変更時に描き直す。
