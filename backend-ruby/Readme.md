# backend-ruby

Ruby、Sinatra、Rack で共通ログ検索 API を提供するコンテナです。Rackup 経由で内部ポート `5000` に待ち受けます。

## ファイル構成

```text
backend-ruby/
├── Dockerfile    # gem を導入し rackup を起動
├── Gemfile       # Sinatra、Puma、Rackup とテスト依存関係
├── Readme.md     # このファイル
├── app.rb        # Sinatra のルート、入力処理、JSON レスポンス
├── config.ru     # Rack の起動点
└── lib/
    ├── elasticsearch_client.rb # Elasticsearch 通信
    └── log_search.rb           # 検索 DSL、結果整形
```

## `app.rb` 内の主な責務

- `app.rb`: HTTP ルート、入力正規化、JSON レスポンス
- `lib/elasticsearch_client.rb`: Elasticsearch の info・検索リクエスト
- `lib/log_search.rb`: 検索 DSL、時刻変換、検索結果整形

起動方式を変えず、Sinatra、Elasticsearch 通信、検索処理を分離しています。

## 設定

- `ELASTICSEARCH_URL`
- `ELASTICSEARCH_INDEX`
- `ELASTICSEARCH_LIMIT`
