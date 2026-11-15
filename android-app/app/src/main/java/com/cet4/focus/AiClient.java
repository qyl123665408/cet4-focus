package com.cet4.focus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Small non-streaming Chat Completions client; no redirects or credential logging. */
final class AiClient {
    static final class UserException extends IOException {
        UserException(String message) { super(message); }
    }
    private volatile HttpURLConnection connection;
    private volatile boolean cancelled;

    static String endpoint(String address) throws IOException {
        try {
            URI uri = new URI(address.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new UserException("接口地址须为 HTTPS，不能带账号、查询参数或 # 片段。");
            String base = uri.toASCIIString().replaceAll("/+$", "");
            return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        } catch (java.net.URISyntaxException | NullPointerException e) {
            throw new UserException("接口地址格式不正确。");
        }
    }

    static void validate(String address, String model, String key) throws IOException {
        endpoint(address);
        if (model == null || model.trim().isEmpty() || model.length() > 200)
            throw new UserException("请填写服务商提供的模型 ID（不超过 200 字）。");
        if (key == null || key.trim().isEmpty() || key.length() > 4096)
            throw new UserException("请填写 API 密钥。");
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) < 33 || key.charAt(i) > 126)
                throw new UserException("API 密钥包含空白或非英文字符，请检查。");
        }
    }

    String complete(String address, String model, String key, JSONArray messages, boolean test) throws IOException {
        validate(address, model, key);
        HttpURLConnection http = null;
        try {
            JSONObject payload = new JSONObject().put("model", model.trim())
                    .put("messages", messages).put("stream", false);
            // Leave token limits to the provider for normal replies (including reasoning models).
            if (test) payload.put("max_tokens", 64);
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 180000) throw new UserException("本次内容过长，请开始新的讲解。");
            http = (HttpURLConnection) new URL(endpoint(address)).openConnection();
            connection = http;
            if (cancelled) throw new UserException("请求已取消。");
            http.setInstanceFollowRedirects(false);
            http.setConnectTimeout(15000);
            http.setReadTimeout(90000);
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setRequestProperty("Authorization", "Bearer " + key);
            http.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            http.setRequestProperty("Accept", "application/json");
            http.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = http.getOutputStream()) { out.write(bytes); }
            int status = http.getResponseCode();
            if (status < 200 || status >= 300) throw new UserException(statusMessage(status));
            String response;
            try (InputStream in = http.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = in.read(buffer)) != -1) {
                    if (cancelled) throw new UserException("请求已取消。");
                    if (out.size() + size > 2 * 1024 * 1024) throw new UserException("服务返回的内容过大。");
                    out.write(buffer, 0, size);
                }
                response = out.toString("UTF-8");
            }
            return parseResponse(response);
        } catch (JSONException e) {
            throw new UserException("服务返回的不是兼容的 Chat Completions JSON。");
        } finally {
            if (http != null) http.disconnect();
            connection = null;
        }
    }

    static String parseResponse(String response) throws IOException, JSONException {
        JSONObject object = new JSONObject(response);
        JSONArray choices = object.optJSONArray("choices");
        JSONObject choice = choices == null ? null : choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        if (message == null) throw new UserException("返回格式不兼容：没有 choices[0].message。");
        Object content = message.opt("content");
        String text = content instanceof String ? (String) content : "";
        if (content instanceof JSONArray) {
            StringBuilder result = new StringBuilder();
            JSONArray parts = (JSONArray) content;
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                if (part != null && "text".equals(part.optString("type"))) result.append(part.optString("text"));
            }
            text = result.toString();
        }
        if (text.trim().isEmpty()) throw new UserException("模型没有返回正文。请检查模型是否支持聊天，或关闭服务商的深度思考后重试。");
        if ("length".equals(choice.optString("finish_reason"))) text += "\n\n（服务商截断了回答，可追问：请继续。）";
        return text.trim();
    }

    private static String statusMessage(int status) {
        switch (status) {
            case 400: return "请求参数不兼容（HTTP 400），请核对模型 ID 及服务商的 Chat Completions 支持。";
            case 401: return "API 密钥无效或已过期（HTTP 401）。";
            case 402: return "API 账户余额不足（HTTP 402）。";
            case 403: return "当前密钥没有访问权限（HTTP 403）。";
            case 404: return "接口或模型不存在（HTTP 404），请核对地址和模型 ID。";
            case 429: return "请求过于频繁或额度不足（HTTP 429），请稍后重试或检查账户额度。";
            default: return status >= 300 && status < 400 ? "接口要求跳转。为保护密钥，请直接填写最终 HTTPS 接口地址。"
                    : "服务暂时不可用（HTTP " + status + "），请稍后重试。";
        }
    }

    void cancel() {
        cancelled = true;
        HttpURLConnection http = connection;
        if (http != null) http.disconnect();
    }
}
