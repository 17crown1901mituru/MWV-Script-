# MWV Script

**M**ulti **W**eb**V**iew **S**cript

Androidアプリ。複数アカウントを独立したCookieStoreで同時ログインできるWebViewブラウザ + バックグラウンドJSエンジン。

## 機能

- **マルチタブ** — タブごとに独立したWebViewとCookieStore
- **複数アカウント同時ログイン** — 同一ドメインに複数アカウントで並列操作
- **JS自動注入** — `/sdcard/Android/data/com.mwvscript.app/files/scripts/` にJSファイルを置くと自動注入
- **バックグラウンド常駐** — Foreground ServiceでアプリをバックグラウンドにしてもJS実行継続
- **タブ間通信** — `MWVScript.postToAccount(id, msg)` でタブ間メッセージング

## スクリプトの書き方

```javascript
// @name    My Script
// @match   https://example.com/*
// @version 1.0.0

// MWVScriptブリッジが使える
const accountId = MWVScript.getAccountId();
MWVScript.log('Running on account: ' + accountId);
MWVScript.toast('Hello from ' + accountId);

// ファイル読み書き
MWVScript.writeFile('data/result.json', JSON.stringify({status: 'ok'}));
const saved = MWVScript.readFile('data/result.json');

// タブ間通信
MWVScript.postToAccount('2', JSON.stringify({action: 'attack'}));
const msgs = JSON.parse(MWVScript.pollMessages());
```

## スクリプト配置場所

```
/sdcard/Android/data/com.mwvscript.app/files/
└── scripts/
    ├── tora-engine.js
    └── another-script.js
```

## ビルド

GitHub Actionsが自動でdebug APKをビルドします。
ActionsタブからAPKをダウンロードしてインストール。

## 要件

- Android 8.0 (API 26) 以上
- インターネット権限
- 通知権限（Foreground Service用）
