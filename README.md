# flask_elastic

既存の Elasticsearch に保存したログを、共通の静的 SPA と複数言語の API バックエンドで検索・閲覧するアプリです。
Elasticsearch は以下の記事の構成で作成済みのものを利用します。

https://qiita.com/naritomo08/items/8368c2f57803e471cc2f

記事の構成に合わせて、デフォルトでは `http://elastic1:9200` の `logs-*` を検索します。
キーワード検索の対象フィールドは `msg` です。
検索結果では `logs-syslog-*` / `logs-authlog-*` のどちらに由来するログかを表示します。
記事内の例に合わせて、Compose では `elastic1` を `192.168.11.20` に解決する設定を入れています。

## 起動

初回のみ、設定ファイルを作成します。

```bash
cp .env.example .env
```

```bash
docker compose up -d --build
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
フロントエンドは言語選択に応じて `/api/flask/...` や `/api/go/...` を呼び、nginx が各 backend コンテナへプロキシします。選択した backend は Local Storage に保存されます。

frontend の Docker ビルド時に CSS / JS の内容からハッシュ付きファイル名
（例: `styles.a1b2c3d4e5f6.css`）を生成し、HTML 内の参照も自動で置き換えます。
そのため、CSS / JS を変更した際に HTML 側のファイル名やクエリ文字列を手動更新する必要はありません。

ホストへ公開するポート:

- `frontend`: http://localhost:8080

各 backend のポートはホストへ公開しません。backend は Compose の内部ネットワークで
`backend-python:5000` などとして待ち受け、frontend の nginx または契約テストからのみ接続します。

### frontendのsyslog設定

`frontend`コンテナは、標準出力・標準エラーをDockerの`syslog` logging driverで
Docker稼働ホストの`127.0.0.1:514/TCP`へ送信します。
この設定を使用する場合は、コンテナを起動する前にホスト上でrsyslogやsyslog-ngなどを起動し、
TCP 514番ポートで受信できるようにしてください。

rsyslogを使用する場合の設定例:

```conf
# /etc/rsyslog.d/10-docker-frontend.conf
module(load="imtcp")
input(type="imtcp" port="514" address="127.0.0.1")

if $programname == "elastic-search-frontend" then {
    action(type="omfile" file="/var/log/elastic-search-frontend.log")
    stop
}
```

設定を反映し、待ち受け状態を確認します。

```bash
sudo systemctl restart rsyslog
sudo ss -lntp | grep ':514'
```

`syslog-address`は`127.0.0.1`を指定しているため、通常はTCP 514番ポートを外部へ公開する必要はありません。
ログファイルの権限やローテーションは、利用するsyslogサーバー側で設定してください。

syslog転送を使用しない場合は、`docker-compose.yml`の`frontend`から次の部分を削除してから起動してください。
この場合はDocker標準のlogging driverが使用されます。

```yaml
logging:
  driver: syslog
  options:
    syslog-address: "tcp://127.0.0.1:514"
    tag: "{{.Name}}"
```

設定を変更した場合は、`frontend`コンテナを再作成します。

```bash
docker compose up -d --force-recreate frontend
```

このsyslog設定が対象にするのはコンテナの標準出力・標準エラーです。
後述する`frontend_access_logs`ボリュームへ保存されるアクセスログとは別のログです。

## API

ログ検索:

```bash
curl -X POST http://localhost:8080/api/flask/logs \
  -H "Content-Type: application/json" \
  -d '{
    "message":"timeout",
    "log_type":"syslog",
    "page":1,
    "size":20
  }'
```

GET でも同じ条件を指定できます。画面ではGET形式を使用するため、検索条件をURLのまま共有できます。

```bash
curl 'http://localhost:8080/api/flask/logs?message=timeout&log_type=syslog&page=1&size=20'
```

レスポンス:

```json
{
  "filters": {},
  "total": 1234,
  "page": 1,
  "size": 20,
  "results": [],
  "count": 20,
  "logs": []
}
```

`count` と `logs` は旧クライアントとの互換性のため残しています。

## 画面機能

- 現在のログ総量と最近のログを5秒ごとに自動更新するトップ画面
- メッセージ、時刻範囲、ログ種別、Host、Programによる検索
- Host、Programは通常入力で完全一致、`/web.*/` のように囲むと正規表現検索
- 最近のログと検索結果のHost、Program、ログ種別クリックによる絞り込み
- 検索条件のURL保存
- 総件数表示とページング
- 検索キーワードのハイライト
- 全フィールドとRaw JSONを確認できるログ詳細ダイアログ
- Raw JSONコピー
- 検索結果のCSVダウンロード
- Python／Elixir／PHP／Java／Go／Rubyのbackend切り替え
- backend選択のLocal Storage保存
- 6 backendの稼働状況を5秒ごとに自動更新
- Elasticsearchの応答時間、バージョン、対象index表示
- frontendへのアクセスログ閲覧とCSVダウンロード
- スマートフォン向けレスポンシブ表示

画面URL:

- トップ: <http://localhost:8080/>
- ログ検索: <http://localhost:8080/search>
- 条件付き検索: <http://localhost:8080/search?message=error&page=1&size=20>
- 稼働状況: <http://localhost:8080/health>

Go backend を frontend 経由で呼ぶ例:

```bash
curl -X POST http://localhost:8080/api/go/logs \
  -H "Content-Type: application/json" \
  -d '{"message":"timeout","log_type":"syslog"}'
```

ヘルスチェック:

```bash
curl http://localhost:8080/health
```

`/health` は全 backend の `/health` を5秒ごとに確認する疎通確認ページです。
各カードには backend、Elasticsearch、応答時間、バージョン、対象indexを表示します。

Python backend を確認する場合:

```bash
curl http://localhost:8080/health/flask
```

Go backend の API を確認する場合:

```bash
curl http://localhost:8080/api/go/options
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
- `GET /health` の共通ステータス、backend名、応答時間

Elasticsearch に接続できる環境で検索 API まで確認する場合:

```bash
RUN_SEARCH_CONTRACT_TESTS=1 docker compose --profile test run --rm backend-contract-tests
```

その場合は `POST /api/logs` のページングを含むレスポンス形式も6言語すべてで確認します。

## 設定

プロジェクト直下の `.env` で、全 backend 共通の設定を変更できます。
`.env` は Git の管理対象外です。初期値は `.env.example` を参照してください。

- `ELASTICSEARCH_URL`: Elasticsearch の URL
- `ELASTICSEARCH_INDEX`: 検索対象のインデックスパターン
- `ELASTICSEARCH_HOST_IP`: `elastic1` に割り当てる IP アドレス
- `ACCESS_LOG_RETENTION_DAYS`: frontendアクセスログの保持日数（デフォルト14日）

例:

```dotenv
ELASTICSEARCH_URL=http://elastic1:9200
ELASTICSEARCH_INDEX=logs-syslog-*
ELASTICSEARCH_HOST_IP=192.168.11.20
ACCESS_LOG_RETENTION_DAYS=14
```

## アクセスログ

frontendのNginxは、利用者操作に対応するリクエストをJSONL形式で日次保存します。
稼働状況画面の「アクセスログ」で対象日を指定して直近100件を確認でき、同じ対象日の日次ログ全件をCSVでダウンロードできます。
ヘルスチェック、アクセスログAPI自身、CSS・JavaScript・画像などの静的ファイルは記録対象外です。

```bash
curl 'http://localhost:8080/api/access-logs?tail=100'
curl 'http://localhost:8080/api/access-logs?full=1'
curl 'http://localhost:8080/api/access-logs?date=2026-06-23&full=1'
```

ログは名前付きボリューム`frontend_access_logs`へ
`access-YYYY-MM-DD.jsonl`として保存され、コンテナ再作成後も残ります。

変更後はコンテナを再作成してください。

```bash
docker compose up -d --force-recreate
```

## おまけ情報

`omake/readme1.md` には、このサイトのNginxアクセスログをアクセスログAPIから取得し、
Elasticsearchのデータストリーム `logs-access-elastic` へ日次で取り込む手順をまとめています。

主な内容:

- アクセスログAPIの疎通確認
- ILMポリシーとインデックステンプレートの作成
- 取り込み用シェルの設置
- 手動実行と取り込み結果の確認
- cronによる前日分の定期取り込み

本体の検索対象ログとは別に、このサイト自体の利用状況をElasticsearchへ蓄積したい場合の補足手順です。
