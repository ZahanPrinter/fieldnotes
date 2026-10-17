package com.flytbase.copilot.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** Talks to a local Ollama server's /api/chat with native tool calling. */
public final class OllamaClient implements Agent.Llm {
  private final String baseUrl, model;
  private final int numCtx;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public OllamaClient(String baseUrl, String model, int numCtx) { this.baseUrl = baseUrl.replaceAll("/$", ""); this.model = model; this.numCtx = numCtx; }

  @Override public JsonNode chat(ArrayNode messages, ArrayNode tools) throws Exception {
    ObjectNode body = Dataset.M.createObjectNode();
    body.put("model", model).put("stream", false);
    body.set("messages", messages);
    if (tools != null && tools.size() > 0) body.set("tools", tools);
    body.putObject("options").put("temperature", 0).put("num_ctx", numCtx);
    HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat")).timeout(Duration.ofMinutes(5))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
    HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
    if (r.statusCode() / 100 != 2) throw new IllegalStateException("Ollama returned " + r.statusCode() + ": " + r.body());
    return Dataset.M.readTree(r.body()).path("message");
  }
}
