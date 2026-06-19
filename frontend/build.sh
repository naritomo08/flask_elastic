#!/bin/sh
set -eu

source_dir=${1:?usage: build.sh SOURCE_DIR OUTPUT_DIR}
output_dir=${2:?usage: build.sh SOURCE_DIR OUTPUT_DIR}

mkdir -p "$output_dir"

for asset in styles.css search.js health.js; do
    source_file="$source_dir/$asset"
    hash=$(sha256sum "$source_file" | cut -c 1-12)
    stem=${asset%.*}
    extension=${asset##*.}
    output_name="$stem.$hash.$extension"

    cp "$source_file" "$output_dir/$output_name"

    case "$asset" in
        styles.css) styles_output=$output_name ;;
        search.js) search_output=$output_name ;;
        health.js) health_output=$output_name ;;
    esac
done

for page in index.html health.html; do
    sed \
        -e "s|/styles.css|/$styles_output|g" \
        -e "s|/search.js|/$search_output|g" \
        -e "s|/health.js|/$health_output|g" \
        "$source_dir/$page" > "$output_dir/$page"
done
