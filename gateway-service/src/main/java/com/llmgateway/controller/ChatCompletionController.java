package com.llmgateway.controller;

import com.llmgateway.model.ChatRequest;
import com.llmgateway.model.ChatResponse;
import com.llmgateway.provider.ProviderRouter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                Flux<String> sseStream = providerRouter.stream(request)
                        .map(chunk -> "data: " + chunk + "\n\n")
                        .concatWithValues("data: [DONE]\n\n");

                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(sseStream);
            } else {
                ChatResponse response = providerRouter.complete(request);
                return ResponseEntity.ok(response);
            }
        } catch (ProviderRouter.UnsupportedModelException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", Map.of(
                            "message", e.getMessage(),
                            "type", "invalid_request_error",
                            "code", "model_not_found"
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
}
