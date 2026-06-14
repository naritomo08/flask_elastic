# flask_elastic

既存の Elasticsearch に保存したログを、共通の静的フロントエンドと複数言語の API バックエンドで検索するアプリです。
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

ブラウザで http://localhost:8080 を開きます。

Compose では以下のコンテナを起動します。

- `frontend`: nginx で `frontend/` の HTML / CSS / JS を配信します
- `backend-python`: Python / Flask / gunicorn で JSON API を提供します
- `backend-go`: Go で JSON API を提供します
- `backend-java`: Java で JSON API を提供します
- `backend-php`: PHP / Slim で JSON API を提供します
- `backend-ruby`: Ruby / Sinatra で JSON API を提供します
- `backend-elixir`: Elixir / Plug.Cowboy で JSON API を提供します

Elasticsearch / Kibana はこの Compose には含めません。
フロントエンドは言語選択に応じて `/api/flask/...` や `/api/go/...` を呼び、nginx が各 backend コンテナへプロキシします。

公開ポート:

- `frontend`: http://localhost:8080
- `backend-python`: http://localhost:5005
- `backend-go`: http://localhost:5006
- `backend-java`: http://localhost:5007
- `backend-php`: http://localhost:5008
- `backend-ruby`: http://localhost:5009
- `backend-elixir`: http://localhost:5010

## API

ログ検索:

```bash
curl -X POST http://localhost:8080/api/flask/logs \
  -H "Content-Type: application/json" \
  -d '{
    "message":"timeout",
    "log_type":"syslog"
  }'
```

Go backend を frontend 経由で呼ぶ例:

```bash
curl -X POST http://localhost:8080/api/go/logs \
  -H "Content-Type: application/json" \
  -d '{"message":"timeout","log_type":"syslog"}'
```

ヘルスチェック:

```bash
curl http://localhost:5005/health
```

追加 backend を直接確認する場合:

```bash
curl http://localhost:5006/api/options
```

## 共通 backend テスト

pytest で全 backend の HTTP API 契約を確認できます。
テストは各言語の実装内部を import せず、起動中の backend に同じリクエストを送ります。

実行方法:

```bash
docker compose build
docker compose --profile test run --rm backend-contract-tests
```

確認している内容:

- `GET /` による backend メタ情報
- `GET /health` によるヘルスチェック形式
- `GET /api/options` による検索条件取得

Elasticsearch に接続できる環境で検索 API まで確認する場合:

```bash
RUN_SEARCH_CONTRACT_TESTS=1 docker compose --profile test run --rm backend-contract-tests
```

その場合は `POST /api/logs` のレスポンス形式も確認します。

## 設定

`docker-compose.yml` の環境変数で接続先とインデックス名を変更できます。

- `ELASTICSEARCH_URL`: Elasticsearch の URL
- `ELASTICSEARCH_INDEX`: 検索対象のインデックスパターン

例:

```yaml
environment:
  ELASTICSEARCH_URL: http://elastic1:9200
  ELASTICSEARCH_INDEX: logs-syslog-*
extra_hosts:
  - "elastic1:192.168.11.20"
```

## 他言語版

フロントエンドの `Backend` セレクトから Flask / Go / Java / PHP / Ruby / Elixir を切り替えられます。
各 backend は同じ API 契約を実装しているため、言語比較やパフォーマンス比較にも利用できます。
