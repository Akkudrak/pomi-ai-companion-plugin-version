package com.maine.pomi.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.maine.pomi.PomiPlugin;

/**
 * Pomi's ears for the Pomi Voz app, ported from the mod: Whisper (speaches, OpenAI API) turns what
 * the app heard into text, and the wake word ("Pomi", "oye Pomi", "hey Pomi") says whether it was
 * for her. Whisper rarely spells the name the same way twice, so besides the exact forms it accepts
 * a first word that sounds alike and is one letter off.
 */
public final class PomiEars {
	/** The app sends 16 kHz, 16-bit, mono PCM. */
	public static final int SAMPLE_RATE = 16000;
	/** Biases Whisper towards spelling the name right and towards how orders are said. */
	private static final String PROMPT = "Pomi, la ranita. Pomi, ven. Pomi, baila. Pomi, ¿qué es eso?";
	/** Orders Whisper mishears as other words ("Pomi, ven" → "Pomi Ben"), fixed at the start of the order. */
	private static final Map<String, String> MISHEARD = Map.of("ben", "ven", "bien", "ven", "be", "ve", "bé", "ve",
			"vaila", "baila", "baile", "baila", "kanta", "canta");
	private static final String FILLERS = "(?:(?:oye|oiga|hey|ey|hei|hola|eh|ah|ay)\\W+){0,2}";
	private static final Pattern WAKE = Pattern.compile("^\\W*" + FILLERS + "(?:[pb]om+[iíy]e?)\\b\\W*",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
	/** Fillers, then one or two words that might be the name ("Pommy", "Po mi"). */
	private static final Pattern LEADING_WORDS = Pattern.compile("^\\W*" + FILLERS + "(\\p{L}+)(?:(\\s+)(\\p{L}+))?",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
	private static final Pattern LEADING_PUNCTUATION = Pattern.compile("^\\W*", Pattern.UNICODE_CHARACTER_CLASS);
	/** What Whisper "hears" in silence or noise; never a real request. */
	private static final Set<String> HALLUCINATIONS = Set.of(
			"gracias", "gracias por ver", "gracias por ver el video", "gracias por ver el vídeo",
			"subtítulos realizados por la comunidad de amara.org", "subtitulos realizados por la comunidad de amara.org",
			"suscríbete", "música", "musica", "aplausos");

	private final PomiPlugin plugin;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private volatile boolean modelRequested;

	public PomiEars(PomiPlugin plugin) {
		this.plugin = plugin;
	}

	/** The transcript of {@code pcm}, or "" if nothing meaningful was said. */
	public String transcribe(byte[] pcm) throws IOException, InterruptedException {
		HttpResponse<String> response = post(pcm);
		if (response.statusCode() == 404 && !modelRequested) {
			// speaches downloads models on request; do it once and retry.
			modelRequested = true;
			downloadModel();
			response = post(pcm);
		}
		if (response.statusCode() != 200) {
			throw new IOException("Whisper respondió " + response.statusCode() + ": " + response.body());
		}
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		String text = json.has("text") ? json.get("text").getAsString().strip() : "";
		String plain = text.toLowerCase(Locale.ROOT).replaceAll("[¡!¿?.…\\s]+$", "").replaceAll("^[¡¿\\s]+", "");
		// Whisper sometimes "hears" its own prompt in silence. A bare "Pomi" looks like that too, but
		// that one is the player calling: keep it.
		int wakeEnd = wakeEnd(text);
		boolean justTheName = wakeEnd >= 0 && text.substring(wakeEnd).replaceAll("\\W", "").isEmpty();
		boolean echo = !plain.isEmpty() && PROMPT.toLowerCase(Locale.ROOT).startsWith(plain) && !justTheName;
		return plain.isEmpty() || echo || HALLUCINATIONS.contains(plain) ? "" : text;
	}

	/** Where the request starts if {@code text} begins with Pomi's name, or -1. */
	public static int wakeEnd(String text) {
		Matcher exact = WAKE.matcher(text);
		if (exact.find()) {
			return exact.end();
		}
		Matcher words = LEADING_WORDS.matcher(text);
		if (!words.find()) {
			return -1;
		}
		int end = -1;
		// Two words only when together they are exactly the name ("Po mi"), so "pon mi casa" isn't a call.
		if (words.group(3) != null && phonetic(words.group(1) + words.group(3)).equals("pomi")) {
			end = words.end(3);
		} else if (soundsLikePomi(words.group(1))) {
			end = words.end(1);
		}
		if (end < 0) {
			return -1;
		}
		Matcher punctuation = LEADING_PUNCTUATION.matcher(text).region(end, text.length());
		return punctuation.lookingAt() ? punctuation.end() : end;
	}

	/** "consigue madera?" → "consigue madera": the punctuation Whisper adds would break the orders. */
	public static String asCommand(String request) {
		String command = request.replaceAll("[¡!¿?.,;:…\"«»]", " ").replaceAll("\\s+", " ").strip();
		String[] words = command.split(" ", 2);
		String fixed = MISHEARD.get(words[0].toLowerCase(Locale.ROOT));
		return fixed == null ? command : fixed + (words.length > 1 ? " " + words[1] : "");
	}

	/** One letter off ("Pumi", "Poemi", "Bomi"), but starting with p or b: with the mic always open, "Tomi" mustn't count. */
	private static boolean soundsLikePomi(String word) {
		String heard = phonetic(word);
		return (heard.startsWith("p") || heard.startsWith("b")) && distance(heard, "pomi") <= 1;
	}

	/** Spelling that sounds the same in Spanish collapses to one form: "Pommy", "Pomí" → "pomi". */
	private static String phonetic(String word) {
		String s = Normalizer.normalize(word, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
		return s.replaceAll("[^a-zñ]", "").replace('y', 'i').replace('v', 'b').replaceAll("^h", "").replaceAll("(.)\\1+", "$1");
	}

	/** Levenshtein distance. */
	private static int distance(String a, String b) {
		int[] previous = new int[b.length() + 1];
		int[] current = new int[b.length() + 1];
		for (int j = 0; j <= b.length(); j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length(); i++) {
			current[0] = i;
			for (int j = 1; j <= b.length(); j++) {
				int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
				current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return previous[b.length()];
	}

	private HttpResponse<String> post(byte[] pcm) throws IOException, InterruptedException {
		String boundary = "pomi" + UUID.randomUUID().toString().replace("-", "");
		ByteArrayOutputStream body = new ByteArrayOutputStream(pcm.length + 1024);
		field(body, boundary, "model", plugin.settings().whisperModel());
		field(body, boundary, "language", "es");
		field(body, boundary, "prompt", PROMPT);
		field(body, boundary, "temperature", "0");
		field(body, boundary, "response_format", "json");
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"voz.wav\"\r\n"
				+ "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(wav(pcm));
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		HttpRequest request = HttpRequest.newBuilder(URI.create(plugin.settings().whisperUrl()))
				.timeout(Duration.ofSeconds(60))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	/** POST {base}/v1/models/{model}: asks speaches to download the model (slow the first time). */
	private void downloadModel() throws IOException, InterruptedException {
		String base = plugin.settings().whisperUrl().replaceFirst("/v1/audio/transcriptions/?$", "");
		String model = plugin.settings().whisperModel();
		plugin.getLogger().info("Descargando el modelo de voz " + model + " en " + base + " (solo la primera vez).");
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/v1/models/"
				+ URLEncoder.encode(model, StandardCharsets.UTF_8).replace("%2F", "/")))
				.timeout(Duration.ofMinutes(10))
				.POST(HttpRequest.BodyPublishers.noBody())
				.build();
		http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
				.getBytes(StandardCharsets.UTF_8));
	}

	private static byte[] wav(byte[] pcm) {
		ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
				.put("WAVE".getBytes(StandardCharsets.US_ASCII))
				.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
				.putShort((short) 1).putShort((short) 1)
				.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2)
				.putShort((short) 2).putShort((short) 16)
				.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length);
		byte[] out = new byte[44 + pcm.length];
		System.arraycopy(header.array(), 0, out, 0, 44);
		System.arraycopy(pcm, 0, out, 44, pcm.length);
		return out;
	}
}
