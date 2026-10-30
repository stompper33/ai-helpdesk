package com.helpdesk.ai;

public final class AiServiceConstants {
    private AiServiceConstants(){}
    public static final String DEFAULT_BASE_URL = "http://localhost:8000";
    public static final String DEFAULT_API_KEY = "";
    public static final String DEFAULT_CONNECT_TIMEOUT ="2s";
    public static final String DEFAULT_READ_TIMEOUT = "30s";
    public static final String DEFAULT_MIN_CONFIDENCE = "0.6";

    public static final class AiErrorCodes {
        private AiErrorCodes(){}
        public static final String AI_BAD_RESPONSE = "AI_BAD_RESPONSE";
        public static final String AI_SERVICE_UNAVAILABLE = "AI_SERVICE_UNAVAILABLE";
        public static final String AI_SERVICE_ERROR = "AI_SERVICE_ERROR";
    }

    public static final class Headers {
        private Headers(){}
        public static final String API_KEY_HEADER = "X-Api-Key";
    }
}
