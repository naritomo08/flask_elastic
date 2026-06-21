# backend-go

Go 標準ライブラリで共通ログ検索 API を提供するコンテナです。単一バイナリをマルチステージビルドし、軽量な Alpine イメージで実行します。

## ファイル構成

```text
backend-go/
├── Dockerfile    # Go バイナリをビルドし実行イメージへコピー
├── Readme.md     # このファイル
├── go.mod        # Go モジュール定義
└── main.go       # HTTP、Elasticsearch、検索条件、結果整形の全実装
```

## `main.go` 内の主な領域

- `App` とルートハンドラー: 共通 HTTP API
- `ElasticSearcher` / `ElasticClient`: Elasticsearch 通信
- `Filters` とリクエスト処理: GET・POST の入力正規化
- クエリ生成関数: 全文、完全一致・正規表現、時刻範囲
- 結果整形関数: JST 表示、ログ種別、互換フィールド

現在は 1 ファイルで完結しています。次の整理では `http.go`、`elasticsearch.go`、`query.go`、`model.go` に分けると責務が明確になりますが、型とテストを同時に移す変更になるため、今回は構造の記録に留めています。

## 設定

- `ELASTICSEARCH_URL`
- `ELASTICSEARCH_INDEX`
- `ELASTICSEARCH_LIMIT`

コンテナは内部ポート `5000` で待ち受けます。
