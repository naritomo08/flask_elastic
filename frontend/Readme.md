# frontend

静的 SPA を nginx で配信し、選択された言語のバックエンドへ API とヘルスチェックを中継するコンテナです。ホストに公開される入口はこのコンテナの `8080` 番ポートだけです。

## ファイル構成

```text
frontend/
├── Dockerfile          # 静的ファイルをビルドし、nginx イメージへ配置
├── Readme.md           # このファイル
├── build.sh            # CSS / JS に内容ハッシュを付けて index.html の参照を書き換える
├── index.html          # SPA の HTML シェル
├── nginx.conf          # SPA 配信、キャッシュ、API・health のルーティング
├── proxy_params.conf   # 各バックエンドへ渡す共通プロキシヘッダー
├── search.js           # 画面遷移、状態管理、ページ別のデータ取得
├── js/
│   ├── api.js          # API 通信とヘルスチェック
│   ├── config.js       # バックエンド表示設定
│   ├── health-page.js  # 稼働状況ページの描画と自動更新
│   ├── log-dialog.js   # ログ詳細ダイアログ
│   ├── utils.js        # エスケープ、強調表示、CSV、数値処理
│   └── views.js        # フォーム、カード、ページ部品のHTML生成
├── styles.css          # 共通レイアウトとトップ画面
├── search.css          # 検索結果画面
├── health-dialog.css   # 稼働状況、ログ詳細、共通状態表示
└── responsive.css      # モバイル向け上書き
```

## 処理の流れ

1. `build.sh` が各 CSS と `search.js` のハッシュ付きコピーを生成し、ES Modulesを配置します。
2. nginx が生成済みファイルと `index.html` を配信します。
3. `/api/{backend}/...` と `/health/{backend}` を `nginx.conf` が対応するコンテナへ転送します。

## 変更時の目安

- 画面や操作を変える: `index.html`、`search.js`
- API 通信を変える: `js/api.js`
- バックエンド一覧を変える: `js/config.js`
- 稼働状況ページを変える: `js/health-page.js`
- カードや検索フォームのHTMLを変える: `js/views.js`
- ログ詳細を変える: `js/log-dialog.js`
- 共通表示処理やCSVを変える: `js/utils.js`
- 見た目を変える: 対応する CSS（共通・トップは `styles.css`）
- API の転送先や URL を変える: `nginx.conf`
- 共通の転送ヘッダーを変える: `proxy_params.conf`
- アセットの生成方法を変える: `build.sh`

ES Modulesを使い、API通信・設定・HTML生成・ダイアログ・汎用処理を画面制御から分離しています。画面数がさらに増える場合は、`search.js` のページ別処理を `pages/` へ分けるのが次の整理候補です。

## 確認

プロジェクト直下で次を実行します。

```bash
docker compose build frontend
docker compose up -d frontend
```
