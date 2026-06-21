FROM alpine:3.22 AS builder

WORKDIR /src
COPY . .
RUN sh build.sh /src /dist

FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY proxy_params.conf /etc/nginx/proxy_params.conf
COPY --from=builder /dist/ /usr/share/nginx/html/
