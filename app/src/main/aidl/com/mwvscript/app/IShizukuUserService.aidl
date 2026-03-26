// IShizukuUserService.aidl
package com.mwvscript.app;

interface IShizukuUserService {
    // ADB権限（uid=2000）でシェルコマンドを実行して結果を返す
    String exec(String cmd) = 1;
    // サービスを終了する
    void destroy() = 16777114;
}
