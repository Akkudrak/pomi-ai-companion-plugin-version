package com.maine.pomi.network;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class VoicePcmTest {
	@Test void whisperGets16kLittleEndianWithSignedSamples() {
		short[] audio = {32767, 32767, 32767, -32768, -32768, -32768, 300, -300, 0};
		assertArrayEquals(new byte[] {-1, 127, 0, -128, 0, 0}, VoicePcm.downsample48k(audio));
	}

	@Test void durationIsPreservedAtWhisperRate() {
		assertEquals(16_000 * 2, VoicePcm.downsample48k(new short[48_000]).length);
	}

	@Test void kokoroBecomes48kWithInterpolationAndNoSignLoss() {
		assertArrayEquals(new short[] {1000, 0, -1000, -1000},
				VoicePcm.upsample24k(new byte[] {(byte) 232, 3, 24, (byte) 252}));
	}

	@Test void durationIsPreservedAtVoiceChatRate() {
		assertEquals(48_000, VoicePcm.upsample24k(new byte[24_000 * 2]).length);
	}

	@Test void rejectsTruncatedPcmAndAcceptsEmptyAudio() {
		assertThrows(IllegalArgumentException.class, () -> VoicePcm.upsample24k(new byte[3]));
		assertEquals(0, VoicePcm.upsample24k(new byte[0]).length);
	}
}
