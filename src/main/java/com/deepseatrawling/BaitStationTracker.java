package com.deepseatrawling;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.Notifier;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Finds the bait station (chum station, advanced chum station or chum spreader) on the player's
 * boat and works out whether a crewmate is assigned to it, the same way the Sailing side panel does:
 * a crewmate's position is 6 + the hotspot it works at, and the boat's hotspot table names the
 * facility built there.
 */
@Slf4j
@Singleton
public class BaitStationTracker
{
    /** The first crew position that is a hotspot; positions below it are the sails, helm and hull. */
    static final int FIRST_HOTSPOT_POSITION = 6;

    /** Ticks the station must stay unassigned before the notification fires, once. */
    static final int NOTIFY_AFTER_TICKS = 2;

    /** In the boat table's hotspot column, (coord, int, int, hotspot row): the hotspot row's field. */
    private static final int HOTSPOT_ROW_FIELD = 3;

    private static final int[] HOTSPOT_VARBITS = {
            VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT0, VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT1,
            VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT2, VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT3,
            VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT4, VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT5,
            VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT6, VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT7,
            VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT8, VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT9,
            VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT10, VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT11,
            VarbitID.SAILING_SIDEPANEL_FACILITY_HOTSPOT12
    };

    private static final int[] CREW_POSITION_VARBITS = {
            VarbitID.SAILING_SIDEPANEL_CREW_SLOT_1_POSITION, VarbitID.SAILING_SIDEPANEL_CREW_SLOT_2_POSITION,
            VarbitID.SAILING_SIDEPANEL_CREW_SLOT_3_POSITION, VarbitID.SAILING_SIDEPANEL_CREW_SLOT_4_POSITION,
            VarbitID.SAILING_SIDEPANEL_CREW_SLOT_5_POSITION
    };

    /** Every bait station the game has: three kinds, two boat sizes, two variants each. */
    private static final Set<Integer> BAIT_STATION_FACILITIES = Set.of(
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_STATION_2X5A,
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_STATION_2X5B,
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_STATION_3X8A,
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_STATION_3X8B,
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_STATION_ADVANCED_2X5A,
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_STATION_ADVANCED_2X5B,
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_STATION_ADVANCED_3X8A,
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_STATION_ADVANCED_3X8B,
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_SPREADER_2X5A,
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_SPREADER_2X5B,
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_SPREADER_3X8A,
            DBTableID.SailingBoatFacility.Row.SAILING_BOAT_FACILITY_CHUM_SPREADER_3X8B
    );

    @Inject
    private Client client;

    @Inject
    private DeepSeaTrawlingConfig config;

    @Inject
    private DeepSeaTrawling plugin;

    @Inject
    private Notifier notifier;

    /** How many ticks in a row the station has had nobody on it while the nets are out. */
    private int unassignedTicks;

    /** Bait station objects on the player's own boat. */
    private final List<GameObject> stations = new ArrayList<>();

    /** The stations to highlight this tick: built, nets out, and nobody assigned. */
    @Getter
    private final List<GameObject> unassignedStations = new ArrayList<>();

    public void reset()
    {
        stations.clear();
        unassignedStations.clear();
        unassignedTicks = 0;
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        GameObject obj = event.getGameObject();
        if (obj == null || obj.getWorldView() == null) return;
        if (client.getLocalPlayer() == null
                || client.getLocalPlayer().getWorldView() != obj.getWorldView()) return;

        if (isBaitStationName(objectName(obj.getId())))
        {
            stations.add(obj);
        }
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event)
    {
        stations.remove(event.getGameObject());
        unassignedStations.remove(event.getGameObject());
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        unassignedStations.clear();
        boolean watching = config.highlightUnassignedBaitStation() || config.notifyUnassignedBaitStation().isEnabled();
        boolean unassigned = watching && !stations.isEmpty() && netsDeployed() && !isBaitStationCrewed();
        unassignedTicks = unassigned ? unassignedTicks + 1 : 0;

        if (unassigned && config.highlightUnassignedBaitStation())
        {
            unassignedStations.addAll(stations);
        }

        // Moving a crewmate between jobs can leave the station empty for a tick, so wait before notifying
        if (unassignedTicks == NOTIFY_AFTER_TICKS && plugin.isNotifyGuardPassed())
        {
            notifier.notify(config.notifyUnassignedBaitStation(), "Your bait station has no crewmate assigned!");
        }
    }

    private boolean netsDeployed()
    {
        return client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_0_DEPTH) > 0
                || client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_1_DEPTH) > 0;
    }

    private boolean isBaitStationCrewed()
    {
        for (int varbit : CREW_POSITION_VARBITS)
        {
            int facility = facilityAtCrewPosition(client.getVarbitValue(varbit));
            if (isBaitStationFacility(facility))
            {
                return true;
            }
        }
        return false;
    }

    /** The boat facility row a crewmate at this position works at, or -1 if it is not a hotspot. */
    private int facilityAtCrewPosition(int position)
    {
        int hotspot = hotspotForCrewPosition(position);
        if (hotspot < 0) return -1;

        int built = client.getVarbitValue(HOTSPOT_VARBITS[hotspot]);
        int boat = client.getVarpValue(VarPlayerID.SAILING_SIDEPANEL_BOAT_TYPE);
        if (built <= 0 || boat <= 0) return -1;

        try
        {
            // getDBTableField's last argument picks a field of the column's tuple and returns
            // that field for every entry. The boat's hotspot list holds (coord, int, int, hotspot
            // row), so field 3 gives each hotspot's row; that row lists the facilities that can be
            // built there, and the hotspot varbit is 1 + the one that was.
            Object[] hotspotRows = client.getDBTableField(boat, DBTableID.SailingBoat.COL_HOTSPOT, HOTSPOT_ROW_FIELD);
            return pick(hotspotRows, hotspot) instanceof Integer
                    ? facilityOption(client.getDBTableField((Integer) hotspotRows[hotspot], DBTableID.SailingBoatHotspot.COL_OPTION, 0), built)
                    : -1;
        }
        catch (RuntimeException e)
        {
            log.debug("Could not read the facility at crew position {}", position, e);
            return -1;
        }
    }

    static boolean isBaitStationFacility(int facility)
    {
        return BAIT_STATION_FACILITIES.contains(facility);
    }

    /** The facility a hotspot varbit value names from that hotspot's options, or -1. */
    static int facilityOption(Object[] options, int built)
    {
        Object facility = pick(options, built - 1);
        return facility instanceof Integer ? (Integer) facility : -1;
    }

    private static Object pick(Object[] values, int index)
    {
        return values != null && index >= 0 && index < values.length ? values[index] : null;
    }

    /** The hotspot a crew position refers to, or -1 for the sails, helm, hull or no job. */
    static int hotspotForCrewPosition(int position)
    {
        int hotspot = position - FIRST_HOTSPOT_POSITION;
        return hotspot >= 0 && hotspot < HOTSPOT_VARBITS.length ? hotspot : -1;
    }

    /** The chum station, advanced chum station and chum spreader all have "chum" in their name. */
    static boolean isBaitStationName(String name)
    {
        return name != null && name.toLowerCase().contains("chum");
    }

    private String objectName(int objectId)
    {
        ObjectComposition composition = client.getObjectDefinition(objectId);
        if (composition == null) return null;
        if (composition.getImpostorIds() != null)
        {
            ObjectComposition impostor = composition.getImpostor();
            if (impostor != null) composition = impostor;
        }
        return composition.getName();
    }
}
