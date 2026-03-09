# MWV Script API Reference

MWV ScriptはAndroid上でRhino.js（ECMAScript 5）エンジンを使ってスクリプトを実行するアプリです。
`.rjs`ファイルはJavaScriptですが、AndroidのJavaクラスを直接呼び出せます。

---

## 目次

1. [基本的な書き方](#1-基本的な書き方)
2. [Javaクラスの呼び出し方](#2-javaクラスの呼び出し方)
3. [組み込み関数](#3-組み込み関数)
4. [overlay - オーバーレイターミナル](#4-overlay---オーバーレイターミナル)
5. [web - WebView操作](#5-web---webview操作)
6. [shell - シェル・操作自動化](#6-shell---シェル操作自動化)
7. [notify - 通知](#7-notify---通知)
8. [tile - クイック設定タイル](#8-tile---クイック設定タイル)
9. [alarm - スケジュール実行](#9-alarm---スケジュール実行)
10. [screen - スクリーンショット](#10-screen---スクリーンショット)
11. [a11y - アクセシビリティ](#11-a11y---アクセシビリティ)
12. [terminal - ターミナル操作](#12-terminal---ターミナル操作)
13. [drawer - スクリプトドロワー](#13-drawer---スクリプトドロワー)
14. [サンプルコード集](#14-サンプルコード集)

---

## 1. 基本的な書き方

### ES5のみ使用可能
RhinoはECMAScript 5ベースです。以下は**使えません**：

```js
// ❌ 使えない
const x = 1;
let y = 2;
(x) => x + 1;        // アロー関数
Promise.resolve();   // Promise
async function f(){} // async/await
`template ${str}`;   // テンプレートリテラル
```

```js
// ✅ 使える
var x = 1;
function add(a, b) { return a + b; }
[1,2,3].forEach(function(i) { print(i); });
JSON.stringify({a: 1});
```

### ファイルの読み込み

```js
load("/sdcard/Download/MWV-Script/Script/accounts.rjs");
```

### スクリプトディレクトリの推奨構成

```
/sdcard/Download/
├── drawer.rjs       ← ドロワー（常駐）
├── accounts.rjs     ← アカウント管理
└── MWV-Script/
    └── Script/
        ├── war.rjs      ← 抗争スクリプト
        └── recovery.rjs ← 回復スクリプト
```

---

## 2. Javaクラスの呼び出し方

`Packages.`プレフィックスでAndroid/JavaのクラスをJSから直接使えます。

```js
// クラス取得
var Intent  = Packages.android.content.Intent;
var File    = Packages.java.io.File;
var Color   = Packages.android.graphics.Color;
var Handler = Packages.android.os.Handler;
var Looper  = Packages.android.os.Looper;
```

### インスタンス生成

```js
var file = new Packages.java.io.File("/sdcard/test.txt");
print(file.exists()); // true/false
```

### 定数アクセス

```js
var MATCH_PARENT = Packages.android.view.ViewGroup.LayoutParams.MATCH_PARENT;
var GRAVITY_END  = Packages.android.view.Gravity.END;
```

### Intentを使って他アプリを起動

```js
var intent = new Packages.android.content.Intent();
intent.setAction(Packages.android.content.Intent.ACTION_VIEW);
intent.setData(Packages.android.net.Uri.parse("https://tantora.jp"));
intent.addFlags(Packages.android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
ctx.startActivity(intent);
```

### SharedPreferencesでデータ保存

```js
// 保存
var sp = ctx.getSharedPreferences("my_prefs", 0);
sp.edit().putString("key", "value").commit();

// 読み込み
var val = sp.getString("key", "default");
print(val);
```

### ファイル操作

```js
// 読み込み
var file = new Packages.java.io.File("/sdcard/Download/test.txt");
if (file.exists()) {
    var br = new Packages.java.io.BufferedReader(
        new Packages.java.io.FileReader(file));
    var line, text = "";
    while ((line = br.readLine()) !== null) text += line + "\n";
    br.close();
    print(text);
}

// 書き込み
var fw = new Packages.java.io.FileWriter("/sdcard/Download/output.txt");
fw.write("Hello World\n");
fw.close();
```

### UIスレッドで実行

AndroidのUI操作はメインスレッドで行う必要があります。

```js
var handler = new Packages.android.os.Handler(
    Packages.android.os.Looper.getMainLooper());

handler.post(new Packages.java.lang.Runnable({
    run: function() {
        // UI操作をここに書く
    }
}));
```

---

## 3. 組み込み関数

アプリに最初から用意されている関数です。

### print(msg)
ターミナルに出力します。

```js
print("Hello");
print("値: " + JSON.stringify({a: 1}));
```

### popup(msg)
Toastで短いメッセージを表示します。

```js
popup("完了しました");
```

### alert(msg, title?)
確認ダイアログを表示します（OKを押すまで処理が止まります）。

```js
alert("エラーが発生しました", "エラー");
```

### prompt(msg, default?, title?)
入力ダイアログを表示します（入力値を返します）。

```js
var name = prompt("名前を入力してください", "", "入力");
print(name);
```

### confirm(msg, title?)
Yes/Noダイアログを表示します（true/falseを返します）。

```js
if (confirm("実行しますか？")) {
    print("実行開始");
}
```

### load(path)
別のrjsファイルを読み込んで実行します。

```js
load("/sdcard/Download/accounts.rjs");
```

### setTimeout(fn, ms)
指定ミリ秒後に関数を実行します。

```js
setTimeout(function() {
    print("3秒後");
}, 3000);
```

### setInterval(fn, ms)
指定ミリ秒ごとに関数を繰り返し実行します。`.stop()`で停止できます。

```js
var timer = setInterval(function() {
    print("1秒ごと");
}, 1000);

// 5秒後に停止
setTimeout(function() {
    timer.stop();
    print("停止");
}, 5000);
```

### bThread(fn)
バックグラウンドスレッドで関数を実行します。

```js
bThread(function() {
    // 重い処理をバックグラウンドで
    var result = 0;
    for (var i = 0; i < 1000000; i++) result += i;
    print("結果: " + result);
});
```

### bTask(fn, afterFn?)
バックグラウンドで処理して完了後にUIスレッドで後処理します。

```js
bTask(
    function() { /* バックグラウンド処理 */ },
    function() { print("完了"); } // UIスレッドで実行
);
```

### runOnUIThread(fn)
UIスレッドで関数を実行します。

```js
runOnUIThread(function() {
    popup("UIスレッドから");
});
```

### ctx
`HubService`のContextです。AndroidのAPIを呼ぶときに使います。

```js
var pm = ctx.getPackageManager();
var packages = pm.getInstalledPackages(0);
print("インストール数: " + packages.size());
```

---

## 4. overlay - オーバーレイターミナル

画面上に常駐するターミナルウィンドウを操作します。

```js
overlay.show()        // ターミナルを表示
overlay.hide()        // ターミナルを非表示
overlay.print("msg")  // ターミナルに出力
```

---

## 5. web - WebView操作

複数のWebViewをセッションIDで管理できます。

### web.open(url, sessionId?)
WebViewを開きます。sessionIdを指定すると複数のWebViewを同時に管理できます。

```js
web.open("https://tantora.jp", "account1");
web.open("https://tantora.jp", "account2");
```

### web.eval(js, sessionId?, callback?)
指定セッションのWebViewでJSを実行します。

```js
web.eval("document.title", "account1", function(result) {
    print("タイトル: " + result);
});
```

### web.evalOn(sessionId, js, callback?)
`web.eval`の引数順違い版（sessionId先指定）。

```js
web.evalOn("account1", "document.body.innerHTML.length", function(len) {
    print("HTML長: " + len);
});
```

### web.close(sessionId?)
WebViewを閉じます。

```js
web.close("account1");
```

### web.url(sessionId?)
現在のURLを取得します。

```js
print(web.url("account1"));
```

### web.cookies(sessionId?)
現在のCookieを取得します。

```js
print(web.cookies("account1"));
```

### web.sessions()
起動中のセッションID一覧を返します。

```js
var sessions = web.sessions();
for (var i = 0; i < sessions.length; i++) {
    print(sessions[i]);
}
```

### web.set(key, value) / web.get(key)
セッション間で値を共有します。

```js
// account1のWebViewからセット
web.set("a1_hp", 50);

// account2のWebViewから取得
var hp = web.get("a1_hp");
```

---

## 6. shell - シェル・操作自動化

### shell.exec(cmd, callback?)
シェルコマンドを実行します。

```js
shell.exec("ls /sdcard/Download", function(output) {
    print(output);
});
```

### shell.termux(cmd)
Termuxにコマンドを送ります（Termuxがインストールされている場合）。

```js
shell.termux("python3 /sdcard/script.py");
shell.termux("curl https://example.com");
```

### shell.tap(x, y)
アクセシビリティ経由で画面をタップします（アクセシビリティサービスが必要）。

```js
shell.tap(540, 960);
```

### shell.swipe(x1, y1, x2, y2, duration?)
スワイプ操作を行います。

```js
shell.swipe(540, 1200, 540, 400, 500); // 上スワイプ
```

---

## 7. notify - 通知

### notify.send(title, text, id?)
通知を出します。idを指定すると同じidで上書きできます。

```js
notify.send("抗争開始", "account1が攻撃されました");

// idを指定して後でキャンセルできる
var id = notify.send("実行中", "スクリプト動作中", 9001);
```

### notify.cancel(id)
通知を消します。

```js
notify.cancel(9001);
```

### notify.cancelAll()
全通知を消します。

```js
notify.cancelAll();
```

### notify.listen(callback)
他アプリからの通知を受け取ります（通知リスナー権限が必要）。
callbackには `{title, text, package, id}` が渡されます。

```js
notify.listen(function(n) {
    print(n.package + ": " + n.title + " / " + n.text);

    // タントラの通知を検知してスクリプトを起動
    if (n.package === "jp.tantora" && n.title.indexOf("攻撃") >= 0) {
        load("/sdcard/Download/MWV-Script/Script/defense.rjs");
    }
});
```

### notify.clearListeners()
登録したリスナーを全て解除します。

```js
notify.clearListeners();
```

### notify.getActive()
現在表示中の通知一覧を返します。

```js
var list = notify.getActive();
for (var i = 0; i < list.length; i++) {
    print(list[i].package + ": " + list[i].title);
}
```

---

## 8. tile - クイック設定タイル

通知バーのクイック設定タイルにスクリプトを登録します。
タイルをタップするとスクリプトが実行され、もう一度タップすると停止します。

### tile.set(script, label?)
タイルに実行するスクリプトを登録します。

```js
tile.set("load('/sdcard/Download/MWV-Script/Script/war.rjs')", "抗争開始");
```

### tile.setStop(fn)
タイルOFF時（停止時）に呼ばれる関数を登録します。

```js
var timer = null;

tile.set("timer = setInterval(function(){ print('loop'); }, 1000); tile.setStop(function(){ timer.stop(); });", "ループ");
```

### tile.start() / tile.stop()
rjsからタイルのON/OFFを操作します。

```js
tile.start();
tile.stop();
```

---

## 9. alarm - スケジュール実行

### alarm.set(delayMs, script)
指定ミリ秒後にスクリプトを実行します。端末がスリープ中でも起動します。

```js
// 10分後に実行
alarm.set(10 * 60 * 1000, "load('/sdcard/Download/MWV-Script/Script/war.rjs')");

// 毎時0分に実行する場合はDate計算を使う
var now   = new Date();
var next  = new Date(now);
next.setHours(now.getHours() + 1, 0, 0, 0);
var delay = next.getTime() - now.getTime();
alarm.set(delay, "load('/sdcard/Download/MWV-Script/Script/hourly.rjs')");
```

### alarm.cancel(script)
登録済みのアラームをキャンセルします。

```js
alarm.cancel("load('/sdcard/Download/MWV-Script/Script/war.rjs')");
```

---

## 10. screen - スクリーンショット

### screen.capture(callback)
スクリーンショットをBase64 JPEG文字列でcallbackに渡します。
初回はMediaProjectionの権限ダイアログが出ます。

```js
screen.capture(function(base64) {
    if (!base64) {
        print("キャプチャ失敗");
        return;
    }
    // Base64をファイルに保存
    var bytes = Packages.android.util.Base64.decode(base64, 0);
    var fos = new Packages.java.io.FileOutputStream("/sdcard/Download/screenshot.jpg");
    fos.write(bytes);
    fos.close();
    print("保存完了");
});
```

---

## 11. a11y - アクセシビリティ

アクセシビリティサービスが有効な場合に使えます。
設定 → ユーザー補助 → MWV Script Accessibility → オン

### a11y.findByText(text)
テキストでUI要素を検索します。

```js
var nodes = a11y.findByText("ログイン");
if (nodes.length > 0) {
    print("発見: " + nodes[0].text);
    a11y.click(nodes[0]);
}
```

### a11y.findById(id)
リソースIDでUI要素を検索します。

```js
var node = a11y.findById("com.example.app:id/button_login");
if (node) a11y.click(node);
```

### a11y.click(node)
ノードをクリックします。

```js
var nodes = a11y.findByText("攻撃");
if (nodes.length > 0) a11y.click(nodes[0]);
```

### a11y.tap(x, y)
座標をタップします。

```js
a11y.tap(540, 960);
```

### a11y.swipe(x1, y1, x2, y2, duration?)
スワイプします。

```js
a11y.swipe(540, 1500, 540, 500); // 上スワイプ
```

### a11y.back() / a11y.home()
戻るボタン・ホームボタンを押します。

```js
a11y.back();
a11y.home();
```

### a11y.getLastEvent()
最後に検知したアクセシビリティイベントを返します。

```js
var ev = a11y.getLastEvent();
print(ev.package + " / " + ev.class + " / " + ev.text);
```

### a11y.isConnected
サービスが接続済みかを返します。

```js
if (!a11y.isConnected) {
    alert("アクセシビリティサービスを有効にしてください");
}
```

---

## 12. terminal - ターミナル操作

### terminal.setInput(text)
メインアクティビティの入力欄にテキストをセットします。

```js
terminal.setInput("load('/sdcard/Download/MWV-Script/Script/war.rjs')");
```

### terminal.run(script)
スクリプトを直接実行します。

```js
terminal.run("print('Hello from terminal')");
```

---

## 13. drawer - スクリプトドロワー

`load('/sdcard/Download/drawer.rjs')`で読み込み後に使えます。

```js
drawer.open()    // ドロワーを開く
drawer.close()   // ドロワーを閉じる
drawer.toggle()  // 開閉トグル
drawer.show()    // 右端の◀ボタンを表示
drawer.hide()    // 右端の◀ボタンを非表示
```

---

## 14. サンプルコード集

### アカウントをSharedPreferencesに保存して使う

```js
// accounts.rjs
var sp = ctx.getSharedPreferences("mwv_accounts", 0);

var accounts = {
    add: function(session, email, password) {
        sp.edit().putString(session, JSON.stringify({
            session: session, email: email, password: password
        })).commit();
    },
    get: function(session) {
        var raw = sp.getString(session, null);
        return raw ? JSON.parse(raw) : null;
    },
    login: function(session) {
        var a = accounts.get(session);
        if (!a) return print("未登録: " + session);
        web.open(
            "https://tantora.jp/nologin/login/try?login_id=" +
            encodeURIComponent(a.email) +
            "&password=" + encodeURIComponent(a.password),
            session
        );
    }
};
```

### 通知を検知してスクリプトをトリガー

```js
notify.listen(function(n) {
    if (n.package === "jp.tantora") {
        print("[通知] " + n.title + ": " + n.text);
        if (n.text.indexOf("入院") >= 0) {
            load("/sdcard/Download/MWV-Script/Script/recovery.rjs");
        }
    }
});
```

### WebViewのDOMを読んで状態を判断

```js
web.evalOn("account1", "document.querySelector('#hp').textContent", function(hp) {
    hp = parseInt(hp);
    print("HP: " + hp);
    if (hp < 30) {
        web.set("account1_low_hp", true);
        notify.send("HP低下", "account1のHPが" + hp + "%です");
    }
});
```

### 定期ポーリングループ

```js
var CHECK_INTERVAL = 30 * 1000; // 30秒

var loop = setInterval(function() {
    web.evalOn("account1", "document.querySelector('#status').textContent", function(status) {
        print("[状態] " + status);
        if (status === "入院中") {
            loop.stop();
            load("/sdcard/Download/MWV-Script/Script/recovery.rjs");
        }
    });
}, CHECK_INTERVAL);

tile.setStop(function() { loop.stop(); });
print("ポーリング開始（30秒間隔）");
```

### タイルにスクリプトを登録して通知バーから実行

```js
// init.rjsに書いておくと起動時に自動設定される
tile.set(
    "load('/sdcard/Download/MWV-Script/Script/war.rjs')",
    "抗争"
);
```

### アクセシビリティでボタンを自動クリック

```js
bThread(function() {
    var nodes = a11y.findByText("攻撃する");
    if (nodes.length > 0) {
        a11y.click(nodes[0]);
        print("攻撃ボタンをクリック");
    } else {
        print("攻撃ボタンが見つかりません");
    }
});
```

---

## 注意事項

- **ES5のみ**: `const`・`let`・アロー関数・Promiseは使えません
- **UIは必ずUIスレッドで**: `handler.post()`か`runOnUIThread()`を使う
- **アクセシビリティ**: 設定から手動で有効にする必要があります
- **通知リスナー**: 設定 → 通知へのアクセス から許可が必要です
- **MediaProjection**: 初回`screen.capture()`時に権限ダイアログが出ます
- **MANAGE_EXTERNAL_STORAGE**: 設定 → アプリ → MWV Script から許可が必要です
