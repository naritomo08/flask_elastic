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
                ├── App.java
                ├── Config.java
                ├── Models.java
                ├── Values.java
                ├── QueryBuilder.java
                ├── LogFormatter.java
                ├── LogService.java
                └── ElasticsearchClient.java
```

- `Dockerfile`: Maven ビルド後、JRE イメージで JAR を起動
- `pom.xml`: Java 21、Jackson、JUnit、Shade plugin の設定
- `App.java`: 起動、HTTP ハンドラー、入力・レスポンス処理
- `Config.java` / `Values.java`: 環境設定と共通値変換
- `Models.java`: API・Elasticsearch 用レコードと通信インターフェース
- `QueryBuilder.java`: 検索 DSL と時刻条件の生成
- `LogFormatter.java` / `LogService.java`: 結果整形と検索ユースケース
- `ElasticsearchClient.java`: Elasticsearch 通信

依存関係込みの単一JARを維持したまま、HTTP、設定、モデル、クエリ、通信、整形を分離しています。

## 設定

- `PORT`（既定値 `5000`）
- `ELASTICSEARCH_URL`
- `ELASTICSEARCH_INDEX`
- `ELASTICSEARCH_LIMIT`
