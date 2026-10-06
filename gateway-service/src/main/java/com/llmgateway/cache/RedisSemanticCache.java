package com.llmgateway.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmgateway.model.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.Query;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.TextField;
import redis.clients.jedis.search.schemafields.VectorField;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class RedisSemanticCache implements SemanticCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSemanticCache.class);
    private static final String INDEX_NAME = "idx:semantic_cache";
    private static final String KEY_PREFIX = "cache:";

    private final EmbeddingService embeddingService;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final JedisPooled jedis;
    private final double similarityThreshold;
    private final int vectorDimensions;
    private final long ttlSeconds;

    public RedisSemanticCache(EmbeddingService embeddingService,
                              ObjectMapper objectMapper,
                              StringRedisTemplate redisTemplate,
                              @Value("${spring.data.redis.host}") String redisHost,
                              @Value("${spring.data.redis.port}") int redisPort,
                              @Value("${gateway.cache.similarity-threshold}") double similarityThreshold,
                              @Value("${gateway.cache.vector-dimensions}") int vectorDimensions,
                              @Value("${gateway.cache.ttl-seconds}") long ttlSeconds) {
        this.embeddingService = embeddingService;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.jedis = new JedisPooled(redisHost, redisPort);
        this.similarityThreshold = similarityThreshold;
        this.vectorDimensions = vectorDimensions;
        this.ttlSeconds = ttlSeconds;
    }

    @PostConstruct
    public void createIndex() {
        try {
            jedis.ftInfo(INDEX_NAME);
            log.info("RediSearch index {} already exists", INDEX_NAME);
        } catch (Exception e) {
            log.info("Creating RediSearch index {}", INDEX_NAME);
            Map<String, Object> vectorAttrs = new HashMap<>();
            vectorAttrs.put("TYPE", "FLOAT32");
            vectorAttrs.put("DIM", vectorDimensions);
            vectorAttrs.put("DISTANCE_METRIC", "COSINE");

            jedis.ftCreate(INDEX_NAME,
                    FTCreateParams.createParams()
                            .on(IndexDataType.HASH)
                            .addPrefix(KEY_PREFIX),
                    new SchemaField[]{
                            VectorField.builder().fieldName("embedding")
                                    .algorithm(VectorField.VectorAlgorithm.HNSW)
                                    .attributes(vectorAttrs).build(),
                            TagField.of("model"),
                            TextField.of("prompt_hash"),
                            TextField.of("response")
                    }
            );
            log.info("RediSearch index {} created", INDEX_NAME);
        }
    }

    @Override
    public Optional<ChatResponse> lookup(String promptText, String model) {
        String promptHash = sha256(promptText);
        String exactKey = KEY_PREFIX + promptHash;
        Map<String, String> exactMatch = jedis.hgetAll(exactKey);
        if (!exactMatch.isEmpty() && model.equals(exactMatch.get("model"))) {
            log.info("Exact cache hit for prompt hash {}", promptHash);
            return parseResponse(exactMatch.get("response"));
        }

        try {
            float[] embedding = embeddingService.embed(promptText);
            byte[] blob = floatsToBytes(embedding);

            Query query = new Query("(@model:{" + escapeTag(model) + "})=>[KNN 1 @embedding $vec AS score]")
                    .addParam("vec", blob)
                    .returnFields("response", "score", "model")
                    .limit(0, 1)
                    .dialect(2);

            SearchResult result = jedis.ftSearch(INDEX_NAME, query);

            if (result.getTotalResults() > 0) {
                var doc = result.getDocuments().get(0);
                double score = Double.parseDouble(doc.getString("score"));
                double similarity = 1.0 - score;

                if (similarity >= similarityThreshold) {
                    log.info("Semantic cache hit (similarity={})", similarity);
                    return parseResponse(doc.getString("response"));
                }
            }
        } catch (Exception e) {
            log.warn("Semantic cache lookup failed, proceeding without cache", e);
        }

        return Optional.empty();
    }

    @Override
    public void store(String promptText, String model, ChatResponse response) {
        try {
            String promptHash = sha256(promptText);
            float[] embedding = embeddingService.embed(promptText);
            String responseJson = objectMapper.writeValueAsString(response);

            String key = KEY_PREFIX + promptHash;
            Map<String, String> fields = new HashMap<>();
            fields.put("prompt_hash", promptHash);
            fields.put("model", model);
            fields.put("response", responseJson);

            jedis.hset(key, fields);
            jedis.hset(key.getBytes(), "embedding".getBytes(), floatsToBytes(embedding));
            jedis.expire(key, ttlSeconds);

            log.info("Cached response for model={}, hash={}", model, promptHash);
        } catch (Exception e) {
            log.warn("Failed to cache response", e);
        }
    }

    @Override
    public void flush() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.info("Flushed {} cache entries", keys.size());
        }
    }

    @Override
    public long size() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        return keys != null ? keys.size() : 0;
    }

    private Optional<ChatResponse> parseResponse(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, ChatResponse.class));
        } catch (JsonProcessingException e) {
            log.error("Failed to parse cached response", e);
            return Optional.empty();
        }
    }

    private static byte[] floatsToBytes(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String escapeTag(String tag) {
        return tag.replace("-", "\\-").replace(".", "\\.");
    }
}
