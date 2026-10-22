package com.maine.pomi.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The xAI chat completions API with function calling. Not streamed: the plugin shows Pomi's line
 * when it's complete, so one JSON answer is simpler and just as fast. Runs off the server thread.
 */
public final class GrokClient {
	private static final URI ENDPOINT = URI.create("https://api.x.ai/v1/chat/completions");

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	public record ToolCall(String id, String name, String arguments) {
	}

	/**
	 * Grok's answer: what Pomi says and the tools she wants to use; {@code message} is the raw
	 * assistant message to put back in the conversation. {@code error} is set when the call failed.
	 */
	public record Reply(String text, List<ToolCall> calls, JsonObject message, String error) {
		static Reply failed(String error) {
			return new Reply("", List.of(), null, error);
		}
	}

	public CompletableFuture<Reply> chat(String apiKey, String model, JsonArray messages, JsonArray tools) {
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.add("messages", messages);
		if (tools != null && !tools.isEmpty()) {
			body.add("tools", tools);
			body.addProperty("tool_choice", "auto");
		}
		HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
				.timeout(Duration.ofSeconds(60))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + apiKey)
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
				.thenApply(GrokClient::parse)
				.exceptionally(error -> Reply.failed("sin conexión con xAI (" + error.getClass().getSimpleName() + ")"));
	}

	private static Reply parse(HttpResponse<String> response) {
		if (response.statusCode() == 401 || response.statusCode() == 403) {
			return Reply.failed("la clave de xAI no es válida (" + response.statusCode() + ")");
		}
		if (response.statusCode() >= 400) {
			return Reply.failed("xAI respondió " + response.statusCode() + ": " + abbreviate(response.body()));
		}
		try {
			JsonObject message = JsonParser.parseString(response.body()).getAsJsonObject()
					.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
			JsonElement content = message.get("content");
			String text = content == null || content.isJsonNull() ? "" : content.getAsString();
			List<ToolCall> calls = new ArrayList<>();
			JsonElement toolCalls = message.get("tool_calls");
			if (toolCalls != null && toolCalls.isJsonArray()) {
				for (JsonElement element : toolCalls.getAsJsonArray()) {
					JsonObject call = element.getAsJsonObject();
					JsonObject function = call.getAsJsonObject("function");
					calls.add(new ToolCall(call.get("id").getAsString(), function.get("name").getAsString(),
							function.has("arguments") ? function.get("arguments").getAsString() : "{}"));
				}
			}
			return new Reply(text.strip(), calls, message, null);
		} catch (RuntimeException e) {
			return Reply.failed("respuesta inesperada de xAI");
		}
	}

	private static String abbreviate(String text) {
		return text == null ? "" : text.length() > 200 ? text.substring(0, 200) + "..." : text;
	}
}
