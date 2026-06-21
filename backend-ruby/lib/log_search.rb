require "time"

module LogSearch
  JST_OFFSET = "+09:00"

  def normalize_filters(source)
    %w[time_from time_to log_type host program message].to_h do |key|
      [key, source.fetch(key, "").to_s.strip]
    end
  end

  def filters_from_hash(source)
    normalize_filters(source.transform_keys(&:to_s))
  end

  def build_query(filters)
    must = filters["message"].empty? ? [] : [text_search_clause("msg", filters["message"])]
    filter = []
    filter << exact_or_regex_clause("host", filters["host"]) unless filters["host"].empty?
    filter << exact_or_regex_clause("program", filters["program"]) unless filters["program"].empty?
    time_range = {}
    time_range["gte"] = datetime_local_to_iso(filters["time_from"]) unless filters["time_from"].empty?
    time_range["lte"] = datetime_local_to_iso(filters["time_to"]) unless filters["time_to"].empty?
    filter << { range: { "@timestamp" => time_range } } unless time_range.empty?
    return { match_all: {} } if must.empty? && filter.empty?

    { bool: {}.tap { |value| value[:must] = must unless must.empty?; value[:filter] = filter unless filter.empty? } }
  end

  def search_logs(elasticsearch_client, filters, page = 1, size = 20)
    search = elasticsearch_client.search(
      index: index_pattern_for_log_type(filters["log_type"]),
      query: build_query(filters), page: page, size: size
    )
    logs = search["hits"].filter_map do |hit|
      source = hit.fetch("_source", {})
      source.merge(
        "id" => hit["_id"], "index" => hit["_index"],
        "log_type" => detect_log_type(hit["_index"]),
        "display_time" => format_timestamp(source["@timestamp"]), "score" => hit["_score"]
      ).then { |log| log_matches_exact_filters?(log, filters) ? log : nil }
    end
    { "total" => search["total"], "results" => logs }
  end

  def regex_pattern(value)
    value.start_with?("/") && value.end_with?("/") && value.length >= 2 ? value[1...-1] : nil
  end

  def format_timestamp(value)
    return "" if value.nil?
    return Time.at(value / 1000.0).getlocal(JST_OFFSET).strftime("%Y/%m/%d %H:%M:%S JST") if value.is_a?(Numeric)

    parsed = parse_time(value.to_s.strip)
    parsed ? parsed.getlocal(JST_OFFSET).strftime("%Y/%m/%d %H:%M:%S JST") : value.to_s
  end

  private

  def text_search_clause(field, value)
    wildcard = "*#{value.to_s.gsub("\\", "\\\\\\").gsub("*", "\\*").gsub("?", "\\?")}*"
    { bool: { should: [
      { match_phrase: { field => { query: value } } },
      { match: { field => { query: value, operator: "and" } } },
      { wildcard: { field => { value: wildcard, case_insensitive: true } } },
      { wildcard: { "#{field}.keyword" => { value: wildcard, case_insensitive: true } } }
    ], minimum_should_match: 1 } }
  end

  def exact_or_regex_clause(field, value)
    pattern = regex_pattern(value)
    clauses =
      if pattern
        [{ regexp: { "#{field}.keyword" => { value: pattern, case_insensitive: true } } },
         { regexp: { field => { value: pattern, case_insensitive: true } } }]
      else
        [{ term: { "#{field}.keyword" => { value: value } } }, { term: { field => { value: value } } }]
      end
    { bool: { should: clauses, minimum_should_match: 1 } }
  end

  def datetime_local_to_iso(value)
    parsed = parse_time(value.to_s.strip)
    parsed ? parsed.utc.iso8601 : value.to_s
  end

  def index_pattern_for_log_type(log_type)
    self.class::LOG_TYPES.include?(log_type) ? "logs-#{log_type}-*" : self.class::ELASTICSEARCH_INDEX
  end

  def detect_log_type(index_name)
    return "authlog" if index_name.to_s.include?("authlog")
    return "syslog" if index_name.to_s.include?("syslog")
    "unknown"
  end

  def log_matches_exact_filters?(log, filters)
    %w[host program].all? do |field|
      filters[field].empty? || regex_pattern(filters[field]) || log[field] == filters[field]
    end
  end

  def parse_time(value)
    normalized = value.sub(/ UTC\z/, "Z").tr(" ", "T").sub(/Z\z/, "+00:00")
    return Time.iso8601(normalized) if normalized.match?(/(?:[+-]\d{2}:?\d{2})\z/)

    match = normalized.match(/\A(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?\z/)
    return nil unless match
    year, month, day, hour, minute, second = match.captures
    Time.new(year.to_i, month.to_i, day.to_i, hour.to_i, minute.to_i, second.to_i, JST_OFFSET)
  rescue ArgumentError
    nil
  end
end
