package org.javaup.agent.llm;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.javaup.agent.config.LocalMindAiProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class AliyunOpenAiLlmClient implements RecommendationLlmClient {

    private final LocalMindAiProperties properties;

    private final HttpClient httpClient;

    public AliyunOpenAiLlmClient(LocalMindAiProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeoutMs()))
                .build();
    }

    @Override
    public boolean isConfigured() {
        return Boolean.TRUE.equals(properties.getEnabled()) && StrUtil.isNotBlank(properties.getApiKey());
    }

    @Override
    public LlmChatResult chat(List<LlmChatMessage> messages, LlmChatOptions options) {
        ensureConfigured();
        JSONObject body = new JSONObject(true);
        body.put("model", StrUtil.blankToDefault(options.getModel(), properties.getChat().getModel()));
        body.put("messages", toMessageArray(messages));
        body.put("temperature", options.getTemperature() == null ? properties.getChat().getTemperature() : options.getTemperature());
        body.put("max_tokens", options.getMaxTokens() == null ? properties.getChat().getMaxTokens() : options.getMaxTokens());
        if (options.getResponseFormat() != null) {
            body.put("response_format", options.getResponseFormat());
        }

        JSONObject response = postJson("/chat/completions", body);
        JSONArray choices = response.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmClientException("LLM returned empty choices", 200, true);
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message == null ? null : message.getString("content");
        if (StrUtil.isBlank(content)) {
            throw new LlmClientException("LLM returned empty content", 200, true);
        }
        JSONObject usage = response.getJSONObject("usage");
        LlmChatResult result = new LlmChatResult()
                .setContent(content)
                .setModel(response.getString("model"));
        if (usage != null) {
            result.setPromptTokens(usage.getInteger("prompt_tokens"))
                    .setCompletionTokens(usage.getInteger("completion_tokens"))
                    .setTotalTokens(usage.getInteger("total_tokens"));
        }
        return result;
    }

    @Override
    public List<Double> embedding(String input) {
        ensureConfigured();
        JSONObject body = new JSONObject(true);
        body.put("model", properties.getEmbedding().getModel());
        body.put("input", input);
        body.put("dimensions", properties.getEmbedding().getDimensions());

        JSONObject response = postJson("/embeddings", body);
        JSONArray data = response.getJSONArray("data");
        if (data == null || data.isEmpty()) {
            throw new LlmClientException("Embedding returned empty data", 200, true);
        }
        JSONArray embedding = data.getJSONObject(0).getJSONArray("embedding");
        List<Double> vector = new ArrayList<>();
        if (embedding != null) {
            for (Object item : embedding) {
                if (item instanceof Number number) {
                    vector.add(number.doubleValue());
                }
            }
        }
        return vector;
    }

    private void ensureConfigured() {
        if (!isConfigured()) {
            throw new LlmClientException("LLM api key is not configured", 0, false);
        }
    }

    private JSONArray toMessageArray(List<LlmChatMessage> messages) {
        JSONArray array = new JSONArray();
        for (LlmChatMessage message : messages) {
            JSONObject item = new JSONObject(true);
            item.put("role", message.getRole());
            item.put("content", message.getContent());
            array.add(item);
        }
        return array;
    }

    private JSONObject postJson(String path, JSONObject body) {
        String url = trimTrailingSlash(properties.getBaseUrl()) + path;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new LlmClientException("LLM request failed, status=" + status + ", body=" + safeBody(response.body()),
                        status, isRetryableStatus(status));
            }
            return JSON.parseObject(response.body());
        } catch (IOException e) {
            throw new LlmClientException("LLM network error", -1, true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("LLM request interrupted", -1, true, e);
        } catch (LlmClientException e) {
            throw e;
        } catch (Exception e) {
            log.warn("LLM response parse failed, url={}", url, e);
            throw new LlmClientException("LLM response parse failed", 200, true, e);
        }
    }

    private String trimTrailingSlash(String value) {
        if (StrUtil.isBlank(value)) {
            return "https://dashscope.aliyuncs.com/compatible-mode/v1";
        }
        String normalized = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        if (normalized.endsWith("/compatible-mode")) {
            return normalized + "/v1";
        }
        return normalized;
    }

    private boolean isRetryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private String safeBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300);
    }
}
