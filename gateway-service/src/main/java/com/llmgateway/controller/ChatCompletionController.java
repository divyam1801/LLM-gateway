package com.llmgateway.controller;

import com.llmgateway.model.ChatRequest;
import com.llmgateway.provider.ProviderRouter;
import com.llmgateway.provider.ProviderRouter.RoutingResult;
import com.llmgateway.provider.ProviderRouter.StreamRoutingResult;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;

@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionController.class);

    private final ProviderRouter providerRouter;

    public ChatCompletionController(ProviderRouter providerRouter) {
        this.providerRouter = providerRouter;
    }

    @PostMapping("/chat/completions")
    public ResponseEntity<?> chatCompletions(@RequestBody ChatRequest request,
                                             HttpServletRequest servletRequest) {
        log.info("Chat completion request: model={}, stream={}", request.model(), request.isStream());

        try {
            if (request.isStream()) {
                StreamRoutingResult result = providerRouter.stream(request);
                Flux<String> sseStream = result.flux()
                        .map(chunk -> "data: " + chunk + "\n\n")
                        .concatWithValues("data: [DONE]\n\n");

                HttpHeaders headers = new HttpHeaders();
                addFailoverHeaders(headers, result.fallbackUsed(), result.provider(),
                        result.originalProvider());

                return ResponseEntity.ok()
                        .headers(headers)
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(sseStream);
            } else {
                RoutingResult result = providerRouter.complete(request);
                HttpHeaders headers = new HttpHeaders();
                addFailoverHeaders(headers, result.fallbackUsed(), result.provider(),
                        result.originalProvider());

                return ResponseEntity.ok()
                        .headers(headers)
                        .body(result.response());
            }
        } catch (ProviderRouter.UnsupportedModelException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", Map.of(
                            "message", e.getMessage(),
                            "type", "invalid_request_error",
                            "code", "model_not_found"
                    )
            ));
        } catch (ProviderRouter.AllProvidersDownException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", Map.of(
                            "message", "All LLM providers are currently unavailable",
                            "type", "server_error",
                            "code", 503
                    )
            ));
        } catch (Exception e) {
            log.error("Chat completion failed", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", Map.of(
                            "message", "Internal server error",
                            "type", "server_error",
                            "code", 500
                    )
            ));
        }
    }

    private void addFailoverHeaders(HttpHeaders headers, boolean fallbackUsed,
                                    String provider, String originalProvider) {
        if (fallbackUsed) {
            headers.add("X-LLM-Gateway-Fallback", "true");
            headers.add("X-LLM-Gateway-Original-Provider", originalProvider);
            headers.add("X-LLM-Gateway-Fallback-Provider", provider);
        }
        headers.add("X-LLM-Gateway-Provider", provider);
    }
}
