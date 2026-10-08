package com.llmgateway.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.model.GeminiEmbedRequest;
import com.llmgateway.model.GeminiGenerateRequest;
import com.llmgateway.model.GeminiGenerateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GeminiRequestParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiRequestParser.class);
    private static final Pattern MODEL_ACTION_PATTERN = Pattern.compile("/models/([^:]+):(.+)");

    private final ObjectMapper objectMapper;

    public GeminiRequestParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String extractModel(String path) {
        Matcher matcher = MODEL_ACTION_PATTERN.matcher(path);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown";
    }

    public RequestType extractRequestType(String path) {
        Matcher matcher = MODEL_ACTION_PATTERN.matcher(path);
        if (matcher.find()) {
            return switch (matcher.group(2)) {
                case "generateContent" -> RequestType.CHAT;
                case "streamGenerateContent" -> RequestType.CHAT_STREAM;
                case "batchEmbedContents" -> RequestType.EMBEDDING;
                default -> RequestType.UNKNOWN;
            };
        }
        return RequestType.UNKNOWN;
    }

    public String extractPromptText(byte[] requestBody, RequestType requestType) {
        try {
            return switch (requestType) {
                case CHAT, CHAT_STREAM -> {
                    GeminiGenerateRequest req = objectMapper.readValue(requestBody, GeminiGenerateRequest.class);
                    yield req.extractPromptText();
                }
                case EMBEDDING -> {
                    GeminiEmbedRequest req = objectMapper.readValue(requestBody, GeminiEmbedRequest.class);
                    List<String> texts = req.extractInputTexts();
                    yield String.join("\n", texts);
                }
                default -> "";
            };
        } catch (Exception e) {
            log.warn("Failed to extract prompt text", e);
            return "";
        }
    }

    public TokenCounts extractTokenCounts(byte[] responseBody) {
        try {
            GeminiGenerateResponse response = objectMapper.readValue(responseBody, GeminiGenerateResponse.class);
            if (response.usageMetadata() != null) {
                return new TokenCounts(
                        response.usageMetadata().promptTokenCount(),
                        response.usageMetadata().candidatesTokenCount(),
                        response.usageMetadata().totalTokenCount()
                );
            }
        } catch (Exception e) {
            log.debug("No usageMetadata in response (may be embedding or streaming)");
        }
        return TokenCounts.EMPTY;
    }

    public TokenCounts extractStreamTokenCounts(String lastChunk) {
        try {
            JsonNode node = objectMapper.readTree(lastChunk);
            JsonNode usage = node.path("usageMetadata");
            if (!usage.isMissingNode()) {
                return new TokenCounts(
                        usage.path("promptTokenCount").asInt(0),
                        usage.path("candidatesTokenCount").asInt(0),
                        usage.path("totalTokenCount").asInt(0)
                );
            }
        } catch (Exception e) {
            log.debug("Failed to parse stream chunk for token counts");
        }
        return TokenCounts.EMPTY;
    }

    public enum RequestType {
        CHAT, CHAT_STREAM, EMBEDDING, UNKNOWN
    }

    public record TokenCounts(int inputTokens, int outputTokens, int totalTokens) {
        public static final TokenCounts EMPTY = new TokenCounts(0, 0, 0);
    }
}
