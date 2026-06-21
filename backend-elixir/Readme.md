# backend-elixir

Elixir、Plug、Cowboy で共通ログ検索 API を提供する OTP アプリケーションです。Mix release を作成し、実行用 Debian イメージへ配置します。

## ファイル構成

```text
backend-elixir/
├── Dockerfile
├── Readme.md
├── mix.exs
├── mix.lock
├── config/
│   └── runtime.exs
└── lib/
    └── elixir_elastic/
        ├── application.ex
        ├── elastic_search.ex
        ├── query.ex
        ├── log_formatter.ex
        └── router.ex
```

- `Dockerfile`: production release のビルドと実行
- `mix.exs` / `mix.lock`: OTP アプリと依存関係
- `config/runtime.exs`: 環境変数から接続先とポートを設定
- `application.ex`: Cowboy を supervision tree に登録
- `router.ex`: HTTP ルート、入力正規化、JSON レスポンス
- `elastic_search.ex`: Elasticsearch 通信と検索実行
- `query.ex`: 検索 DSL と時刻条件の生成
- `log_formatter.ex`: 検索結果の整形と完全一致判定

OTP 起動、HTTP、Elasticsearch 通信、クエリ生成、結果整形を分離しています。

## 設定

- `PORT`（既定値 `5000`）
- `ELASTICSEARCH_URL`
- `ELASTICSEARCH_INDEX`
