package com.deepseatrawling;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.EnumMap;

@Slf4j
@Singleton
public class NetActivityTracker
{
    @Inject
    private Client client;

    public final EnumMap<TrawlingNetSide, GameObject> netObjects = new EnumMap<>(TrawlingNetSide.class);

    // GameObject of most recently-used net 
    @Getter
    private GameObject kickedNetObject = null;

    @Getter
    private boolean kickHighlightActive = false;

    
    public void onKicked()
    {
        kickHighlightActive = true;
        log.debug("Kick highlight activated: kickedNetObject={}",
                kickedNetObject != null ? kickedNetObject.getId() : "null");
    }

    public void reset()
    {
        netObjects.clear();
        kickedNetObject = null;
        kickHighlightActive = false;
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event)
    {
        GameObject obj = event.getGameObject();
        if (obj == null || obj.getWorldView() == null) return;

        TrawlingNetSide side = TrawlingNetSide.fromGameObjectId(obj.getId());
        if (side == null) return;

        if (client.getLocalPlayer() == null
                || client.getLocalPlayer().getWorldView() == null
                || client.getLocalPlayer().getWorldView() != obj.getWorldView()) return;

        netObjects.put(side, obj);
        backfillLastUsedNet();
    }

    @Subscribe
    public void onGameObjectDespawned(GameObjectDespawned event)
    {
        GameObject obj = event.getGameObject();
        if (obj == null) return;
        netObjects.values().remove(obj);
    }

    @Subscribe
    public void onVarbitChanged(VarbitChanged e)
    {
        // clear highlight when the player starts using any facility
        if (e.getVarbitId() == VarbitID.SAILING_BOAT_FACILITY_LOCKEDIN && e.getValue() != 0)
        {
            kickHighlightActive = false;
        }

        if (e.getVarbitId() != VarbitID.SAILING_FACILITY_HOTSPOT_NUMBER) return;
        if (e.getValue() == 0) return;

        int hotspotId = e.getValue()-1;
        int net0Hotspot = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_0_HOTSPOT_ID);
        int net1Hotspot = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_1_HOTSPOT_ID);

        if (hotspotId == net0Hotspot) {
            GameObject net = netObjects.get(TrawlingNetSide.STARBOARD);
            if (net != null) kickedNetObject = net;
        } else if (hotspotId == net1Hotspot) {
            GameObject net = netObjects.get(TrawlingNetSide.PORT);
            if (net != null) kickedNetObject = net;
        }
    }

    // call if a player was already using a net when the plugin is loaded, like a fresh install
    private void backfillLastUsedNet()
    {
        if (kickedNetObject != null) return;
        int currentHotspot = client.getVarbitValue(VarbitID.SAILING_FACILITY_HOTSPOT_NUMBER);
        if (currentHotspot == 0) return;

        // subtract 1 to match the hotspot ID varbits which are 1-indexed 
        currentHotspot = currentHotspot - 1;
        int net0Hotspot = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_0_HOTSPOT_ID);
        int net1Hotspot = client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_1_HOTSPOT_ID);
        if (currentHotspot == net0Hotspot) {
            GameObject net = netObjects.get(TrawlingNetSide.STARBOARD);
            if (net != null) kickedNetObject = net;
        } else if (currentHotspot == net1Hotspot) {
            GameObject net = netObjects.get(TrawlingNetSide.PORT);
            if (net != null) kickedNetObject = net;
        }
    }
}
