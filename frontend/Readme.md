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
├── search.js           # 画面遷移、検索、ヘルス監視、CSV・詳細表示
└── styles.css          # 全画面のスタイルとレスポンシブ定義
```

## 処理の流れ

1. `build.sh` が `styles.css` と `search.js` のハッシュ付きコピーを生成します。
2. nginx が生成済みファイルと `index.html` を配信します。
3. `/api/{backend}/...` と `/health/{backend}` を `nginx.conf` が対応するコンテナへ転送します。

## 変更時の目安

- 画面や操作を変える: `index.html`、`search.js`
- 見た目を変える: `styles.css`
- API の転送先や URL を変える: `nginx.conf`
- 共通の転送ヘッダーを変える: `proxy_params.conf`
- アセットの生成方法を変える: `build.sh`

`search.js` は画面描画、API 通信、状態管理が同居しています。今後機能が増える場合は、ES Modules を使える配信構成にしたうえで `api`、`views`、`state`、`utils` へ分けるのが次の整理候補です。

## 確認

プロジェクト直下で次を実行します。

```bash
docker compose build frontend
docker compose up -d frontend
```
