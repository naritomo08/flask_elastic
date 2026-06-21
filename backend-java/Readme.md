# backend-java

Java 21 の組み込み HTTP サーバーと Jackson で共通ログ検索 API を提供するコンテナです。Maven で依存関係込み JAR を生成します。

## ファイル構成

```text
backend-java/
├── Dockerfile
├── Readme.md
├── pom.xml
└── src/
    └── main/
        └── java/
            └── com/example/flaskelastic/
                └── App.java
```

- `Dockerfile`: Maven ビルド後、JRE イメージで JAR を起動
- `pom.xml`: Java 21、Jackson、JUnit、Shade plugin の設定
- `App.java`: HTTP ハンドラー、設定、モデル、Elasticsearch クライアント、クエリ生成

`App.java` は複数責務を持つため、次に整理するなら `App`、`ElasticsearchClient`、`QueryBuilder`、`Config`、API 用レコードへ分割するのが自然です。現時点では単一ソースでビルドできる簡潔さを保ち、README で境界を明示しています。

## 設定

- `PORT`（既定値 `5000`）
- `ELASTICSEARCH_URL`
- `ELASTICSEARCH_INDEX`
- `ELASTICSEARCH_LIMIT`
