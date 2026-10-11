package com.deepseatrawling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BaitStationTrackerTest
{
	@Test
	public void crewPositionsFromSixAreHotspots()
	{
		assertEquals(0, BaitStationTracker.hotspotForCrewPosition(6));
		assertEquals(4, BaitStationTracker.hotspotForCrewPosition(10));
		assertEquals(12, BaitStationTracker.hotspotForCrewPosition(18));
	}

	@Test
	public void otherJobsAreNotHotspots()
	{
		assertEquals(-1, BaitStationTracker.hotspotForCrewPosition(0));
		assertEquals(-1, BaitStationTracker.hotspotForCrewPosition(2));
		assertEquals(-1, BaitStationTracker.hotspotForCrewPosition(5));
		assertEquals(-1, BaitStationTracker.hotspotForCrewPosition(19));
	}

	// The options of a sloop's sixth hotspot as the game lists them: chum station, advanced chum
	// station, chum spreader, then range and keg
	private static final Object[] SLOOP_HOTSPOT_6 = {8500, 8504, 8508, 8512, 8513};

	@Test
	public void hotspotValueNamesTheFacilityBuilt()
	{
		assertEquals(8504, BaitStationTracker.facilityOption(SLOOP_HOTSPOT_6, 2));
		assertTrue(BaitStationTracker.isBaitStationFacility(BaitStationTracker.facilityOption(SLOOP_HOTSPOT_6, 1)));
		assertTrue(BaitStationTracker.isBaitStationFacility(BaitStationTracker.facilityOption(SLOOP_HOTSPOT_6, 2)));
		assertTrue(BaitStationTracker.isBaitStationFacility(BaitStationTracker.facilityOption(SLOOP_HOTSPOT_6, 3)));
		assertFalse(BaitStationTracker.isBaitStationFacility(BaitStationTracker.facilityOption(SLOOP_HOTSPOT_6, 4)));
	}

	@Test
	public void nothingBuiltOrOutOfRangeIsNoFacility()
	{
		assertEquals(-1, BaitStationTracker.facilityOption(SLOOP_HOTSPOT_6, 0));
		assertEquals(-1, BaitStationTracker.facilityOption(SLOOP_HOTSPOT_6, 6));
		assertEquals(-1, BaitStationTracker.facilityOption(null, 1));
	}

	@Test
	public void recognisesAllThreeBaitStations()
	{
		assertTrue(BaitStationTracker.isBaitStationName("Chum station"));
		assertTrue(BaitStationTracker.isBaitStationName("Advanced chum station"));
		assertTrue(BaitStationTracker.isBaitStationName("Chum spreader"));
	}

	@Test
	public void ignoresOtherFacilities()
	{
		assertFalse(BaitStationTracker.isBaitStationName("Rope trawling net"));
		assertFalse(BaitStationTracker.isBaitStationName("Salvaging hook"));
		assertFalse(BaitStationTracker.isBaitStationName(null));
	}
}
