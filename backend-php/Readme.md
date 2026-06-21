# backend-php

PHP 8.3、Apache、Slim 4 で共通ログ検索 API を提供するコンテナです。DocumentRoot は `public/`、待受ポートは `5000` に変更されています。

## ファイル構成

```text
backend-php/
├── Dockerfile             # Composer と Apache/PHP のマルチステージビルド
├── Readme.md              # このファイル
├── composer.json          # 依存関係と src ファイルの自動読み込み
├── composer.lock          # 依存バージョン固定
├── router.php             # PHP 組み込みサーバー用のルーター
├── public/
│   ├── .htaccess          # Apache のフロントコントローラー設定
│   └── index.php          # Slim アプリの起動点
└── src/
    ├── config.php         # 環境変数と共通定数
    ├── search_query.php   # 検索 DSL と時刻条件の生成
    ├── log_formatter.php  # 時刻・ログ種別・完全一致判定
    ├── elasticsearch.php  # Elasticsearch 通信と検索実行
    └── http.php           # Slim ルート、入力処理、JSON レスポンス
```

HTTP、設定、Elasticsearch 通信、クエリ生成、結果整形を分離しています。

## 設定

- `ELASTICSEARCH_URL`
- `ELASTICSEARCH_INDEX`
- `ELASTICSEARCH_LIMIT`
