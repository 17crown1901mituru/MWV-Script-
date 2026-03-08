# MWV Script

Android上でJavaScriptを直接実行できる、Rhinoエンジン搭載のスクリプト実行環境。

---

## 概要

- **ターミナル画面**からJS式・スクリプトファイルをその場で実行
- **Rhino JSエンジン**がバックグラウンド常駐し、Android APIを直接操作可能
- **WebViewActivity**でDOM操作・JS注入が可能な`.js`スクリプトにも対応
- 再起動後も**BootReceiver**で自動起動

---

## ファイル構成

```
app/src/main/java/com/mwvscript/app/
├── MainActivity.kt         ターミナル画面
├── ScriptEngineService.kt  Rhinoエンジン常駐サービス
├── WebViewActivity.kt      JS注入専用WebView
└── BootReceiver.kt         再起動後自動起動
```

---

## スクリプトの配置場所

### CD（デフォルト実行ディレクトリ）

```
/storage/emulated/0/Android/data/com.mwvscript.app/files/
```

ファイル名だけで実行できる基準ディレクトリ。

---

## 起動時の自動実行

CDに`init.rjs`を置くと、エンジン起動時に自動で読み込まれる。

`init.rjs`の中身は自由に書き換え可能。例：

```js
// init.rjs の例
load("setup.rjs");
print("初期化完了");
```

---

## ターミナルの使い方

| 入力 | 動作 |
|------|------|
| JS式をそのまま入力 | 即時評価・実行 |
| `hoge.rjs` | CDから実行 |
| `hoge.js` | CDから実行 |
| `load("パス")` | フルパス・相対パス指定で実行 |

---

## ファイル種別

| 拡張子 | 用途 |
|--------|------|
| `.rjs` | Rhino、Android API、デバイス操作 |
| `.js` | WebView、DOM操作、JS注入 |

---

## 組み込み関数

| 関数 | 動作 |
|------|------|
| `print(msg)` | ターミナルに出力 |
| `popup(msg)` | Toast表示 |
| `alert(msg)` | ダイアログ |
| `prompt(msg)` | 入力ダイアログ |
| `confirm(msg)` | 確認ダイアログ |
| `setTimeout(fn, ms)` | 遅延実行 |
| `setInterval(fn, ms)` | 繰り返し実行 |
| `bThread(fn)` | バックグラウンドスレッド |
| `runOnUIThread(fn)` | UIスレッド実行 |
| `openWebView(url, js?)` | WebViewActivity起動 |
| `load(path)` | ファイル読み込み・実行 |
| `ctx` | Serviceインスタンス |

---

## 技術メモ

- RhinoのContextはスレッドバインド → 各スレッドで`Context.enter()`が必要
- `Packages.android.`からAndroid APIを直接呼び出し可能
- CookieManagerも`.rjs`から操作可能

---

## ビルド

GitHub Actionsが自動でdebug APKをビルド。  
ActionsタブからAPKをダウンロードしてインストール。

---

## 動作要件

- Android 8.0 (API 26) 以上
