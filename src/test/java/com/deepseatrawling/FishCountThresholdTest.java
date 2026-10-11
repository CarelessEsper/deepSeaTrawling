package com.deepseatrawling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FishCountThresholdTest
{
	private static final int NETS = 250;

	@Test
	public void notifiesWhenACatchReachesTheThreshold()
	{
		assertTrue(DeepSeaTrawling.reachedFishCountThreshold(148, 150, 150, NETS));
		assertTrue(DeepSeaTrawling.reachedFishCountThreshold(146, 152, 150, NETS));
	}

	@Test
	public void notifiesOnlyOnTheCatchThatCrossesIt()
	{
		assertFalse(DeepSeaTrawling.reachedFishCountThreshold(150, 153, 150, NETS));
		assertFalse(DeepSeaTrawling.reachedFishCountThreshold(140, 145, 150, NETS));
	}

	@Test
	public void offAtZero()
	{
		assertFalse(DeepSeaTrawling.reachedFishCountThreshold(0, 3, 0, NETS));
	}

	@Test
	public void leavesAFullNetToTheFullNotification()
	{
		assertFalse(DeepSeaTrawling.reachedFishCountThreshold(245, 250, 248, NETS));
		assertFalse(DeepSeaTrawling.reachedFishCountThreshold(240, 250, 250, NETS));
	}

	@Test
	public void notifiesAgainAfterFishAreTakenOut()
	{
		// Reached at 151, some taken out down to 20, then the nets fill past 150 again
		assertTrue(DeepSeaTrawling.reachedFishCountThreshold(149, 151, 150, NETS));
		assertTrue(DeepSeaTrawling.reachedFishCountThreshold(148, 151, 150, NETS));
	}

	@Test
	public void messageNamesTheChosenCountAndTheTotal()
	{
		assertEquals("Trawling net(s) reached 150 fish (152 now)!", DeepSeaTrawling.fishCountMessage(150, 152));
	}

	@Test
	public void messageLeavesOutTheTotalWhenItMatches()
	{
		assertEquals("Trawling net(s) reached 150 fish!", DeepSeaTrawling.fishCountMessage(150, 150));
	}
}
