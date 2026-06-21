# backend-ruby

Ruby、Sinatra、Rack で共通ログ検索 API を提供するコンテナです。Rackup 経由で内部ポート `5000` に待ち受けます。

## ファイル構成

```text
backend-ruby/
├── Dockerfile    # gem を導入し rackup を起動
├── Gemfile       # Sinatra、Puma、Rackup とテスト依存関係
├── Readme.md     # このファイル
├── app.rb        # Sinatra アプリと Elasticsearch クライアント
└── config.ru     # Rack の起動点
```

## `app.rb` 内の主な責務

- `ElasticsearchClient`: Elasticsearch の info・検索リクエスト
- `LogSearchApp`: HTTP ルート、入力正規化、クエリ生成、結果整形

次に整理する場合は `ElasticsearchClient` を `lib/elasticsearch_client.rb`、検索 DSL と整形処理を `lib/log_search.rb` に分けると、Sinatra のルート定義が読みやすくなります。今回は起動方式を変えない範囲で構成を記録しています。

## 設定

- `ELASTICSEARCH_URL`
- `ELASTICSEARCH_INDEX`
- `ELASTICSEARCH_LIMIT`
