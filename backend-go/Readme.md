# backend-go

Go 標準ライブラリで共通ログ検索 API を提供するコンテナです。単一バイナリをマルチステージビルドし、軽量な Alpine イメージで実行します。

## ファイル構成

```text
backend-go/
├── Dockerfile        # Go バイナリをビルドし実行イメージへコピー
├── Readme.md         # このファイル
├── go.mod            # Go モジュール定義
├── main.go           # 起動、HTTP ルート、入力処理
├── config.go         # 環境変数と共通設定
├── model.go          # API・Elasticsearch 用の型とインターフェース
├── elasticsearch.go  # Elasticsearch 通信
└── query.go          # 検索 DSL、検索結果整形、時刻変換
```

## 主な責務

- `main.go`: `App`、ルートハンドラー、GET・POST の入力正規化
- `elasticsearch.go`: `ElasticClient` と検索リクエスト
- `query.go`: 全文・完全一致・正規表現・時刻範囲と結果整形
- `model.go`: 通信境界のインターフェースとデータ型

単一バイナリのまま、HTTP、設定、通信、検索処理、モデルを分離しています。

## 設定

- `ELASTICSEARCH_URL`
- `ELASTICSEARCH_INDEX`
- `ELASTICSEARCH_LIMIT`

コンテナは内部ポート `5000` で待ち受けます。
