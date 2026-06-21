# backend-python

Flask と gunicorn で共通ログ検索 API を提供する Python コンテナです。Elasticsearch 公式クライアントを使用します。

## ファイル構成

```text
backend-python/
├── Dockerfile               # Python 依存関係を導入し gunicorn を起動
├── Readme.md                # このファイル
├── app.py                   # Flask のルート、入力処理、JSON レスポンス
├── elasticsearch_logs.py    # Elasticsearch 接続、クエリ生成、結果整形
└── requirements.txt         # 実行・契約テストに必要な Python パッケージ
```

## 責務

- `app.py`: `GET /`、`GET /health`、`GET /api/options`、`GET|POST /api/logs`
- `elasticsearch_logs.py`: フィルター正規化、検索 DSL、JST 表示、ログ種別判定

HTTP 層と Elasticsearch 層はすでに分離されています。さらに整理する場合は、`elasticsearch_logs.py` の純粋なクエリ生成処理と通信処理を別モジュールにすると単体テストしやすくなります。

## 設定

- `ELASTICSEARCH_URL`
- `ELASTICSEARCH_INDEX`

コンテナは内部ポート `5000` で待ち受けます。
