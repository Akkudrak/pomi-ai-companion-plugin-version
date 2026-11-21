package com.maine.pomi.network;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PomiWakeWordTest {
	@Test void acceptsSpanishWakeWordAndNormalizesCommands() {
		String text = "¡Oye Pomi, ven!";
		int end = PomiEars.wakeEnd(text);
		assertTrue(end >= 0);
		assertEquals("ven", PomiEars.asCommand(text.substring(end)));
	}

	@Test void doesNotTreatOtherConversationAsACommand() {
		assertEquals(-1, PomiEars.wakeEnd("Vamos a buscar madera"));
		assertEquals(-1, PomiEars.wakeEnd("Tomi, ven"));
		assertEquals(-1, PomiEars.wakeEnd("pon mi casa aquí"));
	}

	@Test void recognizesNameAloneForFollowUp() {
		String text = "Pomi.";
		assertEquals("", PomiEars.asCommand(text.substring(PomiEars.wakeEnd(text))));
	}
}
