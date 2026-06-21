# backend-python

Flask と gunicorn で共通ログ検索 API を提供する Python コンテナです。Elasticsearch 公式クライアントを使用します。

## ファイル構成

```text
backend-python/
├── Dockerfile               # Python 依存関係を導入し gunicorn を起動
├── Readme.md                # このファイル
├── app.py                   # Flask のルート、入力処理、JSON レスポンス
├── elasticsearch_logs.py    # Elasticsearch 接続、検索実行
├── search_query.py          # 検索 DSL と時刻条件の生成
├── log_format.py            # 検索結果の時刻・ログ種別・完全一致判定
└── requirements.txt         # 実行・契約テストに必要な Python パッケージ
```

## 責務

- `app.py`: `GET /`、`GET /health`、`GET /api/options`、`GET|POST /api/logs`
- `elasticsearch_logs.py`: クライアント生成、ヘルスチェック、検索リクエスト
- `search_query.py`: Elasticsearch に依存しない検索 DSL 生成
- `log_format.py`: Elasticsearch に依存しない検索結果整形

HTTP、Elasticsearch 通信、クエリ生成、結果整形を分離しています。

## 設定

- `ELASTICSEARCH_URL`
- `ELASTICSEARCH_INDEX`

コンテナは内部ポート `5000` で待ち受けます。
