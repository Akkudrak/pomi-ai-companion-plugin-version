package com.maine.pomi.network;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.google.gson.JsonObject;

/** Kokoro returns signed 16-bit little-endian mono PCM at 24 kHz. */
public final class PomiSpeech {
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	public short[] synthesize(String url, String voice, double speed, String text) throws IOException, InterruptedException {
		if (url.isBlank()) throw new IOException("Falta kokoro-url.");
		JsonObject body = new JsonObject();
		body.addProperty("model", "kokoro");
		body.addProperty("input", text.replaceAll("[♪*_~#]", "").replaceAll("\\s+", " ").strip());
		body.addProperty("voice", voice);
		body.addProperty("speed", speed);
		body.addProperty("response_format", "pcm");
		body.addProperty("stream", false);
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
		// A bounded subscriber avoids unbounded audio responses and keeps the request timeout active.
		HttpResponse<byte[]> response = http.send(request,
				HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 24_000 * 2 * 120));
		if (response.statusCode() != 200) throw new IOException("Kokoro respondió " + response.statusCode());
		return VoicePcm.upsample24k(response.body());
	}
}
