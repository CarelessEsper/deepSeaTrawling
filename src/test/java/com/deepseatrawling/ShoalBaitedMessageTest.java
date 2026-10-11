package com.deepseatrawling;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShoalBaitedMessageTest
{
	@Test
	public void crewmateBaitingIsRecognised()
	{
		assertTrue(DeepSeaTrawling.isShoalBaitedMessage("Jobless Jim has baited a nearby giant krill shoal with some fish offcuts."));
	}

	@Test
	public void otherTrawlingMessagesAreNot()
	{
		assertFalse(DeepSeaTrawling.isShoalBaitedMessage("Jobless Jim, take control of the advanced chum station!"));
		assertFalse(DeepSeaTrawling.isShoalBaitedMessage("Jolly Jim lowers the net to the correct depth for the nearby giant krill shoal."));
		assertFalse(DeepSeaTrawling.isShoalBaitedMessage("You catch four giant krill!"));
		assertFalse(DeepSeaTrawling.isShoalBaitedMessage(null));
	}
}
