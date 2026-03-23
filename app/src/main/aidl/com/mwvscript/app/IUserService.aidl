package com.mwvscript.app;

interface IUserService {
    // シェルコマンドを実行し、結果（標準出力）を返す
    String exec(String command);

    // サービスの生存確認用（Shizukuの予約番号）
    void destroy() = 16777114;
}
