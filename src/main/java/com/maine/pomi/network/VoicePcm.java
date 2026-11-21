package com.maine.pomi.network;

/** Conversions between Simple Voice Chat (48 kHz), Whisper (16 kHz) and Kokoro (24 kHz). */
final class VoicePcm {
	private VoicePcm() { }

	static byte[] downsample48k(short[] samples) {
		byte[] pcm = new byte[samples.length / 3 * 2];
		for (int i = 0, j = 0; i + 2 < samples.length; i += 3, j += 2) {
			int value = (samples[i] + samples[i + 1] + samples[i + 2]) / 3;
			pcm[j] = (byte) value;
			pcm[j + 1] = (byte) (value >> 8);
		}
		return pcm;
	}

	static short[] upsample24k(byte[] pcm) {
		if ((pcm.length & 1) != 0) throw new IllegalArgumentException("PCM de Kokoro incompleto.");
		short[] out = new short[pcm.length];
		for (int i = 0; i < pcm.length / 2; i++) {
			short value = sample(pcm, i);
			short next = i + 1 < pcm.length / 2 ? sample(pcm, i + 1) : value;
			out[i * 2] = value;
			out[i * 2 + 1] = (short) ((value + next) / 2);
		}
		return out;
	}

	private static short sample(byte[] pcm, int index) {
		return (short) ((pcm[index * 2] & 255) | (pcm[index * 2 + 1] << 8));
	}
}
