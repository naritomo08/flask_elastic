FROM alpine:3.22 AS builder

WORKDIR /src
COPY . .
RUN sh build.sh /src /dist

FROM nginx:1.27-alpine

RUN apk add --no-cache tzdata
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY proxy_params.conf /etc/nginx/proxy_params.conf
COPY 10-access-log-dir.sh /docker-entrypoint.d/10-access-log-dir.sh
RUN chmod +x /docker-entrypoint.d/10-access-log-dir.sh
COPY --from=builder /dist/ /usr/share/nginx/html/
