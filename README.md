# MWV Script

Android上でRhino ES5 JavaScriptを実行し、JavaクラスをrjsスクリプトからPackages経由で直接叩けるセルフホスト型自動化プラットフォーム。

---

## 概要

- **アプリID**: `com.mwvscript.app`
- **スクリプト配置**: `/storage/emulated/0/Download/MWV-Script/`
- **init.rjs配置**: `/storage/emulated/0/Android/data/com.mwvscript.app/files/init.rjs`
- **エンジン**: Rhino ES5 (rhino-android 1.6.0 / rhino-runtime 1.7.13)

### 設計思想

rjsから`Packages.android.*`等のJavaクラスを直接叩けることがこのアプリの真骨頂。  
Kotlin側は「rjsからは絶対に届かない」システムコールバックの受口のみを担保し、「後からrjsで実装できる」ものはKotlinに書かない。

---

## アーキテクチャ

```
HubService.kt              Rhinoエンジン中枢・全ブリッジ登録
WebViewService.kt          WindowManager管理タブ型WebView（最大10タブ）
OverlayService.kt          ドロワーUI・ターミナル
MainActivity.kt            起動・権限取得
MWVAccessibilityService.kt 不審操作検知・a11yブリッジ
MWVTileService.kt          クイック設定タイル
MWVNotificationListener.kt 通知受信ブリッジ
MWVDeviceAdminReceiver.kt  DevicePolicyManagerブリッジ
MediaProjectionActivity.kt スクリーンキャプチャ権限取得
BootReceiver.kt            起動時自動実行（AlarmManager経由）
CryptoService.kt           Extended AES暗号化 + ASDA安全消去
```

---

## rjs API リファレンス

### Rhinoの制約

| 制約 | 詳細 |
|------|------|
| ES5のみ | `const`/`let`/アロー関数/Promise/async-await 不可 |
| `typeof` 禁止 | Javaオブジェクトに使うとクラッシュ → `try-catch`で代替 |
| スレッド | `bThread()`内では必ず`RhinoContext.enter()`が必要 |
| グローバル登録 | IIFE内では`var`なし代入でグローバルスコープに登録 |

---

### コア

```javascript
ctx                          // HubService本体（Androidコンテキスト）
actCtx                       // MainActivity（AlertDialog等Activity必須操作）
print(msg)                   // ターミナル・OverlayService両方に出力
popup(msg)                   // Toast表示
alert(msg, title?)           // AlertDialogブロッキング表示
prompt(msg, default?, title?) // 入力ダイアログ（戻り値あり）
confirm(msg, title?)         // 確認ダイアログ（boolean返却）
load(path)                   // rjsファイルを読み込んで実行（MWV-Script/基準）
```

---

### 非同期・スレッド

```javascript
setTimeout(fn, ms)           // 遅延実行
setInterval(fn, ms)          // 定期実行 → handle.stop() で停止
bThread(fn)                  // バックグラウンドスレッド実行
bTask(bg, post?)             // バックグラウンド → UIスレッド連鎖実行
runOnUIThread(fn)            // UIスレッドで実行
```

---

### オーバーレイUI

```javascript
overlay.show()               // ドロワー表示
overlay.hide()               // ドロワー非表示
overlay.print(msg)           // OverlayServiceターミナルに出力
terminal.setInput(text)      // MainActivityの入力欄にテキストセット
terminal.run(script)         // スクリプト文字列を直接実行
```

---

### WebView（WindowManager常駐・最大10タブ）

```javascript
web.open(url, sessionId?)          // WebViewタブを開く
web.eval(js, sessionId?, cb?)      // WebViewにJSを評価させる
web.evalOn(sessionId, js, cb?)     // evalの糖衣構文
web.close(sessionId?)              // タブを閉じる
web.url(sessionId?)                // 現在のURLを返す
web.cookies(sessionId?)            // Cookie文字列を返す
web.sessions()                     // 起動中セッションID一覧
web.set(key, val) / web.get(key)   // セッション間共有変数
```

---

### シェル・システム操作

```javascript
shell.exec(cmd, cb?)         // sh経由でコマンド実行
shell.termux(cmd)            // Termuxにコマンドを送る
shell.tap(x, y)              // アクセシビリティ経由でタップ
shell.swipe(x1,y1,x2,y2,ms?) // スワイプ
```

---

### アクセシビリティ（a11y）

```javascript
a11y.findByText(text)        // テキストでノード検索 → ノード配列
a11y.findById(id)            // IDでノード検索 → ノード
a11y.tap(x, y)               // ジェスチャータップ
a11y.swipe(x1,y1,x2,y2,ms?) // ジェスチャースワイプ
a11y.click(node)             // ノードをクリック
a11y.back()                  // 戻るボタン
a11y.home()                  // ホームボタン
a11y.getLastEvent()          // 最後のアクセシビリティイベント
a11y.onSuspicious(cb)        // 不審操作検知コールバック登録
a11y.clearSuspicious()       // コールバック解除
a11y.getSuspiciousLog()      // 蓄積した不審操作ログ取得
a11y.addTrustedPackage(pkg)  // 監視除外パッケージ追加
a11y.isConnected             // サービス接続状態
```

---

### 通知

```javascript
notify.send(title, text, id?) // 通知を出す → id返却
notify.cancel(id)             // 通知を消す
notify.cancelAll()            // 全通知を消す
notify.listen(cb)             // 通知受信コールバック登録
notify.clearListeners()       // リスナー全解除
notify.getActive()            // 現在表示中の通知一覧
```

---

### タイル（クイック設定）

```javascript
tile.set(script, label?)     // タイルにスクリプト登録
tile.setStop(fn)             // タイルOFF時の停止関数登録
tile.start()                 // 手動ON
tile.stop()                  // 手動OFF
tile.isRunning               // 実行状態確認
```

---

### アラーム（スケジュール実行）

```javascript
alarm.set(delayMs, script)   // delay後にscript実行
alarm.cancel(script)         // 登録済みアラームキャンセル
```

---

### スクリーンキャプチャ

```javascript
screen.capture(cb)           // スクリーンショットをBase64でコールバックに渡す
```

---

### 暗号化（ProtectStar™ Extended AES）

ブロックサイズ512bit・ラウンド数24・CTRモード。鍵サイズは128/256/512bit（デフォルト256bit）。

```javascript
// 暗号化 → Base64文字列
var enc = crypto.encrypt("password", "plaintext");
var enc512 = crypto.encrypt("password", "plaintext", 64); // 512bit鍵

// 復号 → plaintext文字列
var dec = crypto.decrypt("password", enc);
```

---

### 安全消去（ASDA - Advanced Secure Delete Algorithm）

ProtectStar™ ASDA準拠の4パス消去。NIST SP 800-88 Rev.1 要件を上回る。

| パス | 内容 |
|------|------|
| 1 | 0xFF で全領域上書き |
| 2 | AES-256暗号化データで上書き（鍵はRAMのみ、消去後破棄） |
| 3 | ビットパターン(0x92/0x49/0x24)で上書き + 書き込み後検証 |
| 4 | CSPRNG乱数(SHA1PRNG)で上書き |

```javascript
// 同期（戻り値: boolean）
var ok = shred.file("/storage/emulated/0/Download/secret.txt");
print(ok); // → true

// 非同期
shred.fileAsync("/path/to/file", function(ok) {
    print("消去結果: " + ok);
});
```

---

### デバイス管理（DPM）

`MWVDeviceAdminReceiver`がデバイスオーナー操作の受口。rjsから直接叩く。

```javascript
var dpm = ctx.getSystemService(
    Packages.android.content.Context.DEVICE_POLICY_SERVICE
);
var comp = new Packages.android.content.ComponentName(
    ctx, Packages.com.mwvscript.app.MWVDeviceAdminReceiver
);
// デバイスオーナー確認
print(dpm.isDeviceOwnerApp("com.mwvscript.app"));
```

---

## セットアップ

### init.rjsのデプロイ

```javascript
shell.exec(
    "cp /storage/emulated/0/Download/MWV-Script/init.rjs " +
    "/storage/emulated/0/Android/data/com.mwvscript.app/files/init.rjs",
    function(out){ print(out || "完了"); }
);
```

### デバイスオーナー設定（Shizuku / ADB）

```bash
adb shell dpm set-device-owner com.mwvscript.app/.MWVDeviceAdminReceiver
```

---

## ビルド

GitHub Actions（`.github/workflows/build_apk.yml`）でmainブランチへのpush時に自動ビルド。  
Actionsタブから `MWV-Script-debug-{番号}.zip` をダウンロード可能（7日間保持）。

### 手動ビルド要件

- JDK 17
- Gradle 8.2
- Android SDK compileSdk 34 / minSdk 26 / targetSdk 34

---

## ライブラリ

| ライブラリ | 用途 |
|-----------|------|
| `com.faendir.rhino:rhino-android:1.6.0` | Android向けRhinoラッパー |
| `org.mozilla:rhino-runtime:1.7.13` | Rhinoランタイム本体 |
| `androidx.multidex:multidex:2.0.1` | MultiDex対応 |
| `com.google.code.gson:gson:2.10.1` | JSON処理 |

---

## ブランチ

| ブランチ | 内容 |
|---------|------|
| `main` | 現行安定版 |
| `legacy` | 再構築前の旧コード（参照用） |
