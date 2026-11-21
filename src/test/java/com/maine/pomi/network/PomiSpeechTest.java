package com.maine.pomi.network;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class PomiSpeechTest {
	private String simulation() {
		String url = System.getenv("POMI_VOICE_SIM_URL");
		assumeTrue(url != null && !url.isBlank(), "Requiere la simulación Postman local; ver README.");
		return url;
	}

	@Test void sendsKokoroContractAndConvertsRealHttpAudio() throws Exception {
		short[] pcm = new PomiSpeech().synthesize(simulation() + "/kokoro/v1/audio/speech",
				"ef_dora", 0.95, "**Hola, soy Pomi.** ♪");
		assertEquals(48_000, pcm.length);
		assertTrue(pcm[20] > 0);
		assertTrue(pcm[80] < 0);
	}

	@Test void surfacesKokoroFailureWithoutReturningInvalidAudio() {
		String url = simulation() + "/kokoro-error/v1/audio/speech";
		IOException error = assertThrows(IOException.class,
				() -> new PomiSpeech().synthesize(url, "ef_dora", 0.95, "Hola, soy Pomi."));
		assertTrue(error.getMessage().contains("503"));
	}

	@Test void missingEndpointHasAnActionableError() {
		IOException error = assertThrows(IOException.class,
				() -> new PomiSpeech().synthesize("", "ef_dora", 0.95, "Hola"));
		assertTrue(error.getMessage().contains("kokoro-url"));
	}
}
