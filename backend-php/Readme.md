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
    ├── elasticsearch.php  # Elasticsearch 通信、クエリ生成、結果整形
    └── http.php           # Slim ルート、入力処理、JSON レスポンス
```

HTTP、設定、Elasticsearch の責務は分離済みです。さらに分ける場合は `elasticsearch.php` 内の HTTP 通信とクエリ生成を別ファイルにすると、通信なしで検索 DSL を検証しやすくなります。

## 設定

- `ELASTICSEARCH_URL`
- `ELASTICSEARCH_INDEX`
- `ELASTICSEARCH_LIMIT`
