# flask_elastic

既存の Elasticsearch に保存したログを Ruby / Sinatra のWebアプリからキーワード検索するアプリです。
Elasticsearch は以下の記事の構成で作成済みのものを利用します。

https://qiita.com/naritomo08/items/8368c2f57803e471cc2f

記事の構成に合わせて、デフォルトでは `http://elastic1:9200` の `logs-*` を検索します。
キーワード検索の対象フィールドは `msg` です。
検索結果では `logs-syslog-*` / `logs-authlog-*` のどちらに由来するログかを表示します。
記事内の例に合わせて、Compose では `elastic1` を `192.168.11.20` に解決する設定を入れています。

## 起動

```bash
docker compose up --build
```

ブラウザで http://localhost:5001 を開きます。

Ruby / Sinatra アプリだけを Docker で起動します。Elasticsearch / Kibana はこの Compose には含めません。
画面検索は `/api/logs` を呼び出すため、リロードしてもフォーム再送信は発生しません。

## API

ログ検索:

```bash
curl -X POST http://localhost:5001/api/logs \
  -H "Content-Type: application/json" \
  -d '{
    "message":"timeout",
    "log_type":"syslog"
  }'
```

ヘルスチェック:

```bash
curl http://localhost:5001/health
```

## テスト

Minitest でアプリの主要処理を確認できます。
テストでは Elasticsearch に実接続せず、Fake クライアントを使います。

実行方法:

```bash
bundle exec ruby test_app.rb
```

確認している内容:

- JST の時刻表示変換
- 検索条件から Elasticsearch クエリを組み立てる処理
- `syslog` / `authlog` のログ種別判定
- `POST /` による画面検索
- `POST /api/logs` による JSON API 検索
- 検索結果の表示用フィールド作成

## 設定

`docker-compose.yml` の環境変数で接続先とインデックス名を変更できます。

- `ELASTICSEARCH_URL`: Elasticsearch の URL
- `ELASTICSEARCH_INDEX`: 検索対象のインデックスパターン
- `ELASTICSEARCH_LIMIT`: 検索結果の最大表示件数

例:

```yaml
environment:
  ELASTICSEARCH_URL: http://elastic1:9200
  ELASTICSEARCH_INDEX: logs-syslog-*
  ELASTICSEARCH_LIMIT: "50"
extra_hosts:
  - "elastic1:192.168.11.20"
```

## 他言語版

本サイトは Ruby / Sinatra 版です。
ブランチを切り替えれば Go / Java / PHP / Python 版にもなります。

ブランチ名がそのままその言語版になります。

言語比較やパフォーマンス比較にもご利用ください。
