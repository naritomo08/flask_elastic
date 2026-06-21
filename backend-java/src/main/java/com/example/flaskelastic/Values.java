package com.example.flaskelastic;

final class Values {
    private Values() {}

    static int positiveInt(String value, int fallback, Integer maximum) {
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (Exception ignored) {
            parsed = fallback;
        }
        if (parsed < 1) parsed = fallback;
        return maximum == null ? parsed : Math.min(parsed, maximum);
    }

    static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static String getenv(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    static int getenvInt(String key, int fallback) {
        try {
            return Integer.parseInt(System.getenv().getOrDefault(key, ""));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
