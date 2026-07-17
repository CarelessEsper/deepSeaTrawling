package com.deepseatrawling;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Type;
import java.util.*;
import java.util.List;


@Slf4j
@PluginDescriptor(
	name = "Deep Sea Trawling",
	description = "Tracks Shoals - their movement, depth and relation to your net(s)",
	tags = {"trawl", "trawling", "sailing", "fishing", "shoal", "deep", "sea", "net"}
)
public class DeepSeaTrawling extends Plugin
{
	@Inject
	private ConfigManager configManager;

	@Inject
	private Gson gson;

	private static final String CONFIG_GROUP = "deepseatrawling";
	private static final String FISH_COUNTS_KEY = "fishCounts";
	private static final String FISH_HOLD_COUNTS_KEY = "fishHoldCounts";
	private static final String FISH_INVENTORY_COUNTS_KEY = "fishInventoryCounts";

	@Inject
	private Client client;

	@Inject
	private DeepSeaTrawlingConfig config;

	@Inject
	private DeepSeaTrawlingOverlay overlay;

	@Inject
	private DeepSeaTrawlingWidgetOverlay widgetOverlay;

	@Inject
	private TrawlingNetOverlay trawlingNetOverlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private net.runelite.client.game.ItemManager itemManager;

    @Inject
    ShoalRouteRegistry shoalRouteRegistry;

    @Inject
    private Notifier notifier;

    @Inject
    private net.runelite.client.eventbus.EventBus eventBus;

    @Inject
    private net.runelite.client.callback.ClientThread clientThread;
    private boolean notifiedFull = false;
    private boolean pendingInitialLoad = true;

    @Inject
    private NetActivityTracker netTracker;

    private int lastNotifiedDepth = -1;

    private Map<Integer, Integer> inventoryFishSnapshot = null;
    private boolean collectingFromNet = false;
    // Set when cargo hold fills during a partial deposit — net count is unknown until player next checks nets.
    private boolean netCountUnknown = false;
    private boolean netCountUnknownFromCargoHold = false;
    private int unknownHoldCount = 0;
    private int netCountBeforeCargoHold = 0; // fishQuantity at time of partial cargo hold

    private static final Set<Integer> TRACKED_FISH_ITEM_IDS = new HashSet<>(Arrays.asList(
            32309, // Giant Krill
            32317, // Haddock
            32325, // Yellowfin
            32333, // Halibut
            32341, // Bluefin
            32349 // Marlin
    ));

	private TrawlingNetInfoBox trawlingNetInfoBox;
    private boolean wasOnBoat = false;
    private final Map<String, FishCatchInfoBox> fishCatchInfoBoxes = new HashMap<>();


	public final Set<Integer> trackedShoals = new HashSet<>();

    public final int SKIFF_WORLD_ENTITY_TYPE = 2;
    public final int SLOOP_WORLD_ENTITY_TYPE = 3;
    public Map<Integer, Integer> boats = new HashMap<>();

    @Getter
    private ShoalData nearestShoal;

    private final Map<Integer, ShoalData> activeShoals = new HashMap<>();

    public ShoalData getActiveShoal(int worldViewId) {
        return activeShoals.get(worldViewId);
    }

    public Map<ShoalData.ShoalSpecies, Color> speciesColours = new EnumMap<>(ShoalData.ShoalSpecies.class);

    @Provides
	DeepSeaTrawlingConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(DeepSeaTrawlingConfig.class);
	}

	private static final int SHOAL_WORLD_ENTITY_TYPE = 4;

	public Net[] netList = {
			new Net(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_0_DEPTH),
			new Net(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_1_DEPTH)
	};

	@Override
	protected void startUp() throws Exception
	{
		overlayManager.add(overlay);
		overlayManager.add(widgetOverlay);
		overlayManager.add(trawlingNetOverlay);
		eventBus.register(netTracker);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/netInfobox_icon.png");
		trawlingNetInfoBox = new TrawlingNetInfoBox(icon, this, config);
		infoBoxManager.addInfoBox(trawlingNetInfoBox);

		nearestShoal = null;
		rebuildTrackedShoals();
        rebuildShoalColours();

        shoalRouteRegistry.load();

        if (client.getGameState() == GameState.LOGGED_IN) {
            loadFishCounts();
        }

		log.info("Deep Sea Trawling Plugin Started");

	}

	@Override
	protected void shutDown() {
		overlayManager.remove(overlay);
		overlayManager.remove(widgetOverlay);
		overlayManager.remove(trawlingNetOverlay);
		eventBus.unregister(netTracker);
		netTracker.reset();

		if (trawlingNetInfoBox != null) {
			infoBoxManager.removeInfoBox(trawlingNetInfoBox);
			trawlingNetInfoBox = null;
		}
        for (FishCatchInfoBox infoBox : fishCatchInfoBoxes.values()) {
			infoBoxManager.removeInfoBox(infoBox);
		}
		fishCatchInfoBoxes.clear();
		trackedShoals.clear();
        activeShoals.clear();
        nearestShoal = null;
        collectingFromNet = false;
        inventoryFishSnapshot = null;
        netQueue.clear();
        netCountUnknown = false;
        netCountUnknownFromCargoHold = false;
        unknownHoldCount = 0;
        netCountBeforeCargoHold = 0;
		log.info("Deep Sea Trawling Plugin Stopped");
	}

    private static final Set<Integer> COLLECTIBLE_NET_OBJECT_IDS = new HashSet<>(Arrays.asList(
            59755, 59756, 59757, 59758, 59759, 59760, 59761, 59762, 59763, 59764, 59765, 59766
    ));


	public int fishQuantity = 0;

    // track which fish types are removed during partial inventory pulls
    public final LinkedList<AbstractMap.SimpleEntry<String, Integer>> netQueue = new LinkedList<>();

    private int netQueueTotal() {
        int total = 0;
        for (AbstractMap.SimpleEntry<String, Integer> e : netQueue) total += e.getValue();
        return total;
    }

	@Subscribe
	public void onWorldEntitySpawned(WorldEntitySpawned event) {
		WorldEntity entity = event.getWorldEntity();
		WorldEntityConfig cfg = entity.getConfig();

		if (cfg == null) {
			return;
		}

        WorldView view = entity.getWorldView();
        if (view == null) return;

        if (cfg.getId() == SHOAL_WORLD_ENTITY_TYPE) {
            int worldViewId = view.getId();
            ShoalData newShoal = new ShoalData(worldViewId, entity, shoalRouteRegistry.get(worldViewId));
            activeShoals.computeIfAbsent(worldViewId, id -> newShoal);
            if (nearestShoal == null) {
                nearestShoal = newShoal;
            }

        } else if (cfg.getId() == SKIFF_WORLD_ENTITY_TYPE || cfg.getId() == SLOOP_WORLD_ENTITY_TYPE) {
            boats.put(view.getId(), cfg.getId());
        }
	}

	@Subscribe
	public void onWorldEntityDespawned(WorldEntityDespawned event)
	{
        WorldEntity entity = event.getWorldEntity();
        WorldEntityConfig cfg = entity.getConfig();

        if (cfg == null) {
            return;
        }

        int worldViewId = entity.getWorldView().getId();

        boats.remove(worldViewId);
        activeShoals.remove(worldViewId);

	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{

		GameObject obj = event.getGameObject();
		if (obj == null || obj.getWorldView() == null) return;

		int id = obj.getId();

		ShoalData.ShoalSpecies species = ShoalData.ShoalSpecies.fromGameObjectId(id);
		if (species == null) {
			return;
		}

		int worldViewId = obj.getWorldView().getId();
		ShoalData shoal = activeShoals.get(worldViewId);
		if (shoal == null) {
			return;
		}

        if (shoal.getWorldViewId() == worldViewId)
        {
            ShoalData.ShoalSpecies previousSpecies = shoal.getSpecies();
            shoal.setSpecies(species);
            shoal.setShoalObject(obj);

            // Notify if the nearest shoal just became a special shoal
            if (shoal == nearestShoal
                    && (species == ShoalData.ShoalSpecies.SHIMMERING || species == ShoalData.ShoalSpecies.GLISTENING || species == ShoalData.ShoalSpecies.VIBRANT)
                    && (previousSpecies == null || (previousSpecies != ShoalData.ShoalSpecies.SHIMMERING && previousSpecies != ShoalData.ShoalSpecies.GLISTENING && previousSpecies != ShoalData.ShoalSpecies.VIBRANT)))
            {
                if (isNotifyGuardPassed()) {
                    notifier.notify(config.notifySpecialShoal(), "Shoal has become a " + species.name().charAt(0) + species.name().substring(1).toLowerCase() + " shoal!");
                }
            }

            WorldEntity shoalWorldEntity= shoal.getWorldEntity();
            if (shoalWorldEntity == null) return;

            LocalPoint lp = shoalWorldEntity.getLocalLocation();
            if (lp != null)
            {
                WorldPoint wp = WorldPoint.fromLocal(client, lp);
                shoal.setCurrentWorldPoint(wp);
                shoal.setLast(null);
            }

            shoal.setMovingStreak(0);
            shoal.setStoppedStreak(0);

            shoal.setDepthFromAnimation(client.getTickCount());
            log.debug("Shoal worldViewId={} species={} objectId={}", worldViewId, species, id);
        }
	}

    @Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event) {
		GameObject obj = event.getGameObject();
		if (obj == null) return;

        int worldViewId = obj.getWorldView() != null ? obj.getWorldView().getId() : -1;
        if (worldViewId != -1)
        {
            ShoalData shoal = activeShoals.get(worldViewId);
            if (shoal != null && shoal.getShoalObject() == obj)
            {
                shoal.setShoalObject(null);
            }
        }
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event)
    {
        if (event.getGroupId() != 219 || !netCountUnknown) return;

        // only process if the player is on a boat 
        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getWorldView() == null) return;
        if (!boats.containsKey(localPlayer.getWorldView().getId())) return;

        clientThread.invokeLater(() -> readNetCountFromWidget());
    }

    private void readNetCountFromWidget()
    {
        if (!netCountUnknown) return;
        Widget parent = client.getWidget(219, 1);
        if (parent == null || parent.isHidden()) return;
        Widget[] dynamic = parent.getDynamicChildren();
        if (dynamic == null || dynamic.length == 0) return;

        String text = dynamic[0].getText();
        if (text == null || !text.startsWith("There ")) return;

        String[] parts = text.split(" ");
        if (parts.length < 3) return;

        try {
            String countWord = parts[2];
            int trueNetCount;
            if (countWord.equals("no")) {
                trueNetCount = 0;
            } else {
                try {
                    trueNetCount = Integer.parseInt(countWord);
                } catch (NumberFormatException e) {
                    trueNetCount = convertToNumber(countWord);
                }
            }
            log.debug("Resolved net count from widget: {} (text='{}')", trueNetCount, text);

            // Trim the queue to trueNetCount from the tail (everything before trueNetCount went to the hold)
            if (netCountUnknownFromCargoHold)
            {
                int depositedToHold = netCountBeforeCargoHold - trueNetCount;
                if (depositedToHold > 0) unknownHoldCount += depositedToHold;
                // Replace queue with unknown entry — types of remaining fish are no longer known
                netQueue.clear();
                if (trueNetCount > 0)
                {
                    netQueue.addLast(new AbstractMap.SimpleEntry<>("Unknown", trueNetCount));
                }
                netCountUnknownFromCargoHold = false;
                netCountBeforeCargoHold = 0;
                log.debug("Partial cargo hold resolved: {} to hold (unknown), {} unknown remain in nets", depositedToHold, trueNetCount);
            }
            else
            {
                // Partial inventory — trim front of queue, crediting those to hold
                int queueTotal = netQueueTotal();
                int toRemoveFromFront = queueTotal - trueNetCount;
                log.debug("Trimming {} from front of queue (queue={} trueNet={})", toRemoveFromFront, queueTotal, trueNetCount);
                while (toRemoveFromFront > 0 && !netQueue.isEmpty())
                {
                    AbstractMap.SimpleEntry<String, Integer> head = netQueue.peekFirst();
                    int remove = Math.min(head.getValue(), toRemoveFromFront);
                    toRemoveFromFront -= remove;
                    FishCatchInfoBox infoBox = fishCatchInfoBoxes.get(head.getKey());
                    if (infoBox != null) infoBox.incrementHoldCount(remove);
                    if (remove == head.getValue()) netQueue.pollFirst();
                    else head.setValue(head.getValue() - remove);
                }
            }

            fishQuantity = netQueueTotal();
            netCountUnknown = false;
            saveFishCounts();
        } catch (IllegalArgumentException e) {
            log.debug("Could not parse net count from widget text: '{}'", text);
        }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!event.getMenuOption().equals("Collect-from")) return;
        if (!COLLECTIBLE_NET_OBJECT_IDS.contains(event.getId())) return;

        ItemContainer inventory = client.getItemContainer(93);
        if (inventory == null) return;

        inventoryFishSnapshot = snapshotFishInInventory(inventory);
        collectingFromNet = true;
        log.debug("Net collect-from detected, fish snapshot: {}", inventoryFishSnapshot);
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        if (!collectingFromNet) return;
        if (event.getGroupId() != 219) return;

        ItemContainer inventory = client.getItemContainer(93);
        if (inventory == null || inventoryFishSnapshot == null)
        {
            collectingFromNet = false;
            inventoryFishSnapshot = null;
            return;
        }

        Map<Integer, Integer> afterSnapshot = snapshotFishInInventory(inventory);
        int totalAdded = 0;
        for (int itemId : TRACKED_FISH_ITEM_IDS)
        {
            int before = inventoryFishSnapshot.getOrDefault(itemId, 0);
            int after = afterSnapshot.getOrDefault(itemId, 0);
            int delta = after - before;
            if (delta > 0) totalAdded += delta;
        }

        if (totalAdded > 0)
        {
            int remaining = totalAdded;
            while (remaining > 0 && !netQueue.isEmpty())
            {
                AbstractMap.SimpleEntry<String, Integer> head = netQueue.peekFirst();
                int take = Math.min(head.getValue(), remaining);
                remaining -= take;
                FishCatchInfoBox infoBox = fishCatchInfoBoxes.get(head.getKey());
                if (infoBox != null) infoBox.incrementInventoryCount(take);
                if (take == head.getValue()) netQueue.pollFirst();
                else head.setValue(head.getValue() - take);
            }
            log.debug("Partial inventory collection: {} fish moved to inventory", totalAdded);
            fishQuantity = netQueueTotal();
            saveFishCounts();
        }

        collectingFromNet = false;
        inventoryFishSnapshot = null;
    }

    private Map<Integer, Integer> snapshotFishInInventory(ItemContainer inventory)
    {
        Map<Integer, Integer> snapshot = new HashMap<>();
        for (Item item : inventory.getItems())
        {
            if (item == null) continue;
            int id = item.getId();
            if (TRACKED_FISH_ITEM_IDS.contains(id))
            {
                snapshot.merge(id, item.getQuantity(), Integer::sum);
            }
        }
        return snapshot;
    }

    @Subscribe
    public void onNpcSpawned(NpcSpawned e)
    {
        NPC eventNpc = e.getNpc();
        int npcWorldViewId = eventNpc.getWorldView().getId();
        if (eventNpc.getId() == NpcID.SAILING_SHOAL_RIPPLES) {
            activeShoals.get(npcWorldViewId).setShoalNpc(eventNpc);
            activeShoals.get(npcWorldViewId).setDepthFromAnimation(client.getTickCount());
        }
    }

    @Subscribe
    public void onNpcDespawned(NpcDespawned e)
    {
        NPC eventNpc = e.getNpc();
        int npcWorldViewId = eventNpc.getWorldView().getId();
        if (eventNpc.getId() == NpcID.SAILING_SHOAL_RIPPLES) {
            activeShoals.get(npcWorldViewId).setShoalNpc(null);
            activeShoals.get(npcWorldViewId).setDepth(ShoalData.ShoalDepth.UNKNOWN);
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged e) {
        GameState state = e.getGameState();
        if (state == GameState.LOGGED_IN && pendingInitialLoad) {
            pendingInitialLoad = false;
            loadFishCounts();
        }
        if (state == GameState.HOPPING || state == GameState.LOGGING_IN) {
            // Fish remaining in nets are lost on hop — remove them from totals
            for (AbstractMap.SimpleEntry<String, Integer> entry : netQueue) {
                FishCatchInfoBox infoBox = fishCatchInfoBoxes.get(entry.getKey());
                if (infoBox != null) infoBox.decrementCount(entry.getValue());
            }
            fishQuantity = 0;
            wasOnBoat = false;
            collectingFromNet = false;
            inventoryFishSnapshot = null;
            netQueue.clear();
            netCountUnknown = false;
            netCountUnknownFromCargoHold = false;
            unknownHoldCount = 0;
            netCountBeforeCargoHold = 0;
            netTracker.reset();
            if (nearestShoal != null) {
                nearestShoal.setDepth(ShoalData.ShoalDepth.UNKNOWN);
                nearestShoal.clearStopTimer();
                nearestShoal.setLast(null);
            }
            if (state == GameState.HOPPING) {
                saveFishCounts();
            }
        }
    }

    @Subscribe
    public void onAccountHashChanged(AccountHashChanged e) {
        loadFishCounts();
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        if (netCountUnknown)
        {
            Widget parent = client.getWidget(219, 1);
            if (parent != null && !parent.isHidden())
            {
                readNetCountFromWidget();
            }
        }

        for (ShoalData shoal : activeShoals.values()) {
            WorldEntity shoalWorldEntity = shoal.getWorldEntity();
            if (shoalWorldEntity == null) continue;

            LocalPoint currentLP = shoalWorldEntity.getLocalLocation();
            if (currentLP == null) continue;

            shoal.setCurrentWorldPoint(WorldPoint.fromLocal(client, currentLP));
        }

        updateNearestShoalSticky();

        ShoalData shoal = nearestShoal;
        if (shoal == null) {
            return;
        }
        shoal.setDepthFromAnimation(client.getTickCount());
        checkWrongDepthNotification();

        WorldPoint last = shoal.getLast();
        if (last == null)
        {
            shoal.setLast(shoal.getCurrentWorldPoint());
            shoal.setMovingStreak(0);
            shoal.setStoppedStreak(0);
            return;
        }

        boolean isMoving = !shoal.getCurrentWorldPoint().equals(last);

        if (isMoving)
        {
            shoal.setMovingStreak(shoal.getMovingStreak() + 1);
            shoal.setStoppedStreak(0);
            shoal.setDepth(shoal.getSpecies().defaultDepth());
        }
        else
        {
            shoal.setStoppedStreak(shoal.getStoppedStreak() + 1);
            shoal.setMovingStreak(0);
        }

        boolean isMovingConfirmed = shoal.getMovingStreak() >= 2;
        boolean isStoppedConfirmed = shoal.getStoppedStreak() >= 2;

        boolean wasMovingPrev = shoal.isWasMoving();

        boolean movingToStopped = wasMovingPrev && isStoppedConfirmed;
        boolean stoppedToMoving = !wasMovingPrev && isMovingConfirmed;

        if (movingToStopped)
        {
            if (!shoal.hasActiveStopTimer())
            {
                int stopDurationTicks = ShoalData.shoalTimers
                        .getOrDefault(ShoalTypes.fromIdToSpecies(shoal.getWorldViewId()), 0);

                if (stopDurationTicks > 0)
                {
                    shoal.beginStopTimer(client.getTickCount(), stopDurationTicks - 2);
                }
            }
            shoal.setWasMoving(false);
        }
        else if (stoppedToMoving)
        {
            shoal.clearStopTimer();
            shoal.setWasMoving(true);
        }

        if (shoal.hasActiveStopTimer() && shoal.getTicksUntilMove(client.getTickCount()) <= 0)
        {
            shoal.clearStopTimer();
        }

        if (isMovingConfirmed) {
            shoal.setWasMoving(true);
        } else if (isStoppedConfirmed) {
            shoal.setWasMoving(false);
        }

        shoal.setLast(shoal.getCurrentWorldPoint());
    }

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		ChatMessageType type = event.getType();

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || localPlayer.getWorldView() == null) return;
        if (!boats.containsKey(localPlayer.getWorldView().getId())) return;

		if (type == ChatMessageType.GAMEMESSAGE || type == ChatMessageType.SPAM)
		{
            String msg = event.getMessage().replaceAll("<[^>]*>","");
			if (msg.startsWith("You empty the net") ||
                    msg.startsWith("You take all of the fish from the net")) {
                if (msg.contains("cargo hold") && msg.contains("not enough space")) {
                    // Partial cargo hold — keep queue intact (tail = what remains, front = what went to hold)
                    // Types that went to hold are unknown; resolved via widget next time it's opened
                    netCountBeforeCargoHold = fishQuantity;
                    netCountUnknown = true;
                    netCountUnknownFromCargoHold = true;
                    collectingFromNet = false;
                    inventoryFishSnapshot = null;
                    log.debug("Partial cargo hold deposit - net count now unknown, queue preserved");
                    saveFishCounts();
                } else if (msg.contains("cargo hold")) {
                    // Full cargo hold — credit hold counts and clear
                    for (AbstractMap.SimpleEntry<String, Integer> entry : netQueue) {
                        FishCatchInfoBox infoBox = fishCatchInfoBoxes.get(entry.getKey());
                        if (infoBox != null) infoBox.incrementHoldCount(entry.getValue());
                    }
                    netQueue.clear();
                    fishQuantity = 0;
                    collectingFromNet = false;
                    inventoryFishSnapshot = null;
                    notifiedFull = false;
                    log.debug("Emptied nets to cargo hold");
                    saveFishCounts();
                } else {
                    // Full inventory collection — credit inventory counts then clear
                    for (AbstractMap.SimpleEntry<String, Integer> entry : netQueue) {
                        FishCatchInfoBox infoBox = fishCatchInfoBoxes.get(entry.getKey());
                        if (infoBox != null) infoBox.incrementInventoryCount(entry.getValue());
                    }
                    netQueue.clear();
                    fishQuantity = 0;
                    notifiedFull = false;
                    log.debug("Emptied nets to inventory");
                    saveFishCounts();
                }
            } else if (msg.equals("You take some fish from the net")) {
                // Partial inventory collection — onWidgetClosed handles the diff
            }

            if (msg.startsWith("Your hands grow numb from holding the net in place for a long time without moving")) {
                notifier.notify(config.notifyKickedFromNet(), "You were removed from the trawling net due to inactivity!");
                netTracker.onKicked();
                return;
            }

            if (msg.contains("Trawler's trust")) {
				// Another message includes the additional fish caught
				return;
			}

			String quantityStr = "";
			String fishName = "";
			int startIndex = -1;
			if (msg.contains("You catch "))
			{
				startIndex = "You catch ".length();
			} else if (msg.contains(" catches ")) {
				startIndex = msg.indexOf(" catches ") + " catches ".length();
			}
			
			// Parse quantity and fish name if valid pattern is found
			if (startIndex != -1) {
				int spaceIndex = msg.indexOf(" ", startIndex + 1);
				if (spaceIndex == -1) {
					log.debug("Could not find space after quantity in message: '{}'", msg);
				} else {
					quantityStr = msg.substring(startIndex, spaceIndex);
					
					// Extract fish name (everything after the quantity until the exclamation point)
					int exclamIndex = msg.indexOf("!", spaceIndex);
					if (exclamIndex > 0) {
						fishName = msg.substring(spaceIndex + 1, exclamIndex).trim();
					} else {
						fishName = msg.substring(spaceIndex + 1).trim();
					}
				}
			}

			if (!quantityStr.isEmpty())
			{
                int totalNetSize = 0;
                if (netList[0] != null)
                {
                    totalNetSize += netList[0].getNetSize();
                }
                if (netList[1] != null)
                {
                    totalNetSize += netList[1].getNetSize();
                }
                if (totalNetSize > 0) {
                    int amount = convertToNumber(quantityStr);
                    if (!fishName.isEmpty()) {
                        String titleFishName = toTitleCase(fishName);
                        netQueue.addLast(new AbstractMap.SimpleEntry<>(titleFishName, amount));
                        trackFishCatch(titleFishName, amount);
                    } else {
                        netQueue.addLast(new AbstractMap.SimpleEntry<>("Unknown", amount));
                    }
                    fishQuantity = netQueueTotal();
                }
                if (fishQuantity >= totalNetSize && !notifiedFull) {
                    if (isNotifyGuardPassed()) {
                        notifier.notify(config.notifyNetFull(), "Trawling net(s) full! Empty now!");
                    }
                    notifiedFull = true;
                }
			}
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		int changed = e.getVarbitId();

		switch (changed)
		{
			case VarbitID.SAILING_PLAYER_IS_ON_PLAYER_BOAT:
				if (e.getValue() == 1) {
					wasOnBoat = true; // Track this to avoid wipes during login state change
				} else if (e.getValue() == 0 && wasOnBoat) {
					wasOnBoat = false;
					fishQuantity = 0;
					notifiedFull = false;
					netQueue.clear();
					netCountUnknown = false;
					netCountUnknownFromCargoHold = false;
					unknownHoldCount = 0;
					netCountBeforeCargoHold = 0;
					netTracker.reset();
					log.debug("Disembarked from boat - clearing fish counts");

					// Report trip summary before clearing
					if (config.reportTripTotals() && !fishCatchInfoBoxes.isEmpty()) {
						List<String> caughtParts = new ArrayList<>();
						List<String> holdParts = new ArrayList<>();

						for (Map.Entry<String, FishCatchInfoBox> entry : fishCatchInfoBoxes.entrySet()) {
							FishCatchInfoBox box = entry.getValue();
							if (box.getCount() > 0) caughtParts.add(box.getCount() + " " + entry.getKey());
							if (box.getInventoryCount() > 0) holdParts.add(box.getInventoryCount() + " " + entry.getKey());
						}

						if (!caughtParts.isEmpty()) {
							String msg = "<col=005f9e>[Deep Sea Trawling] Trip summary — Caught " + String.join(", ", caughtParts);
							if (!holdParts.isEmpty()) msg += " (" + String.join(", ", holdParts) + " taken to inventory)";
							msg += "</col>";
							client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", msg, null);
						}
					}

					for (FishCatchInfoBox infoBox : fishCatchInfoBoxes.values()) {
						infoBox.resetCount();
						infoBoxManager.removeInfoBox(infoBox);
					}
					fishCatchInfoBoxes.clear();
					configManager.unsetConfiguration(CONFIG_GROUP, FISH_COUNTS_KEY);
					configManager.unsetConfiguration(CONFIG_GROUP, FISH_HOLD_COUNTS_KEY);
					configManager.unsetConfiguration(CONFIG_GROUP, FISH_INVENTORY_COUNTS_KEY);
				}
				break;
			case VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_0_DEPTH:
				netList[0].setNetDepth(e.getValue());
				break;
			case VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_1_DEPTH:
				netList[1].setNetDepth(e.getValue());
				break;
		}

	}

/*
	public int worldDistanceSq(WorldPoint a, WorldPoint b)
	{
		int dx = a.getX() - b.getX();
		int dy = a.getY() - b.getY();
		return dx * dx + dy * dy;
	}
*/
	private void rebuildTrackedShoals() {
		trackedShoals.clear();

		if(config.showGiantKrill()) {
			for (int id : ShoalTypes.GIANT_KRILL.getIds()) {
				trackedShoals.add(id);
			}
		}
		if(config.showHaddock()) {
			for (int id : ShoalTypes.HADDOCK.getIds()) {
				trackedShoals.add(id);
			}
		}
		if(config.showHalibut()) {
			for (int id : ShoalTypes.HALIBUT.getIds()) {
				trackedShoals.add(id);
			}
		}
		if(config.showYellowfin()) {
			for (int id : ShoalTypes.YELLOWFIN.getIds()) {
				trackedShoals.add(id);
			}
		}
		if(config.showBluefin()) {
			for (int id : ShoalTypes.BLUEFIN.getIds()) {
				trackedShoals.add(id);
			}
		}
		if(config.showMarlin()) {
			for (int id : ShoalTypes.MARLIN.getIds()) {
				trackedShoals.add(id);
			}
		}
	}

	@Subscribe
	private void onConfigChanged(ConfigChanged event) {
		if (!event.getGroup().equals("deepseatrawling")) {
			return;
		}
		rebuildTrackedShoals();
        rebuildShoalColours();

		/*
		StringBuilder builder = new StringBuilder();
		builder.append("Shoal wv=").append(nearestShoal.getWorldViewId()).append(" species=").append(nearestShoal.getSpecies()).append(" path=[");

		for (WorldPoint worldPoint : nearestShoal.getPathPoints()) {
			builder.append(worldPoint.getX()).append(", ").append(worldPoint.getY()).append(", 0|");
		}
		builder.append("] stops=[");
		for (WorldPoint worldPoint : nearestShoal.getStopPoints()) {
			builder.append(worldPoint.getX()).append(", ").append(worldPoint.getY()).append(", 0|");
		}

		log.info(builder.toString());*/
	}

	private static final Map<String, Integer> WORD_NUMBERS = Map.ofEntries(
			Map.entry("a", 1),
			Map.entry("one", 1),
			Map.entry("two", 2),
			Map.entry("three", 3),
			Map.entry("four", 4),
			Map.entry("five", 5),
			Map.entry("six", 6),
			Map.entry("seven", 7),
			Map.entry("eight", 8),
			Map.entry("nine", 9),
			Map.entry("ten", 10)
	);

	private int convertToNumber(String s)
	{
		s = s.toLowerCase();

		Integer v = WORD_NUMBERS.get(s);
		if (v != null)
		{
			return v;
		}

		throw new IllegalArgumentException("Unknown quantity: " + s);
	}
    private void rebuildShoalColours() {
        speciesColours.clear();
        speciesColours.put(ShoalData.ShoalSpecies.GIANT_KRILL, config.giantKrillColour());
        speciesColours.put(ShoalData.ShoalSpecies.YELLOWFIN, config.yellowfinColour());
        speciesColours.put(ShoalData.ShoalSpecies.HADDOCK, config.haddockColour());
        speciesColours.put(ShoalData.ShoalSpecies.HALIBUT, config.halibutColour());
        speciesColours.put(ShoalData.ShoalSpecies.BLUEFIN, config.bluefinColour());
        speciesColours.put(ShoalData.ShoalSpecies.MARLIN, config.marlinColour());
        speciesColours.put(ShoalData.ShoalSpecies.SHIMMERING, config.shimmeringColour());
        speciesColours.put(ShoalData.ShoalSpecies.GLISTENING, config.glisteningColour());
        speciesColours.put(ShoalData.ShoalSpecies.VIBRANT, config.vibrantColour());

    }

    private void checkWrongDepthNotification() {
        ShoalData shoal = getNearestShoal();
        int desiredDepth = -1;

        if (shoal != null && shoal.getDepth() != ShoalData.ShoalDepth.UNKNOWN) {
            desiredDepth = ShoalData.ShoalDepth.asInt(shoal.getDepth());
        }
        if (desiredDepth < 1) {
            return;
        }

        if (desiredDepth == lastNotifiedDepth) {
            return;
        }

        if (checkNetDepths(desiredDepth) && lastNotifiedDepth != desiredDepth) {
            lastNotifiedDepth = desiredDepth;
            if (isNotifyGuardPassed()) {
                notifier.notify(config.notifyDepthChange(), "Shoal depth changed! Change net depth!");
            }
        }

    }

    private boolean checkNetDepths(int desiredDepth) {
        for (int i = 0; i < 2; i++) {
            Net net = netList[i];
            if (net == null) continue;

            int currentDepth = Net.NetDepth.asInt(net.getNetDepth());
            if (currentDepth > 0 && currentDepth != desiredDepth) {
                return true;
            }
        }
        return false;
    }

    public boolean isNotifyGuardPassed() {
        switch (config.notifyGuard()) {
            case ALWAYS:
                return true;
            case ON_BOAT:
                return client.getVarbitValue(VarbitID.SAILING_PLAYER_IS_ON_PLAYER_BOAT) == 1;
            case NET_PRESENT:
                return client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_0_HOTSPOT_ID) > 0
                    || client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_1_HOTSPOT_ID) > 0;
            case NET_DEPLOYED:
                return client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_0_DEPTH) > 0
                    || client.getVarbitValue(VarbitID.SAILING_SIDEPANEL_BOAT_TRAWLING_NET_1_DEPTH) > 0;
            default:
                return true;
        }
    }


    private void trackFishCatch(String fishName, int amount) {
		FishCatchInfoBox infoBox = fishCatchInfoBoxes.get(fishName);
		if (infoBox == null) {
			BufferedImage icon = getFishIcon(fishName);
			if (icon == null) {
				log.debug("No icon available for '{}', skipping infobox creation", fishName);
				return;
			}
			infoBox = new FishCatchInfoBox(icon, this, config, fishName);
			fishCatchInfoBoxes.put(fishName, infoBox);
			infoBoxManager.addInfoBox(infoBox);
		}
		
		infoBox.incrementCount(amount);
	}

    private BufferedImage getFishIcon(String fishName) {
		ShoalData.ShoalSpecies species = ShoalData.ShoalSpecies.fromFishName(fishName);
		log.debug("getFishIcon for '{}': species={}", fishName, species);
		if (species != null && species.getItemID() > 0) {
			BufferedImage img = itemManager.getImage(species.getItemID());
			return img;
		}
		return null;
	}

	public Map<String, FishCatchInfoBox> getFishCatchInfoBoxes() {
		return fishCatchInfoBoxes;
	}

    private static String toTitleCase(String input) {
        if (input == null || input.isEmpty()) return input;
        String[] words = input.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    private void saveFishCounts() {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> holdCounts = new HashMap<>();
        Map<String, Integer> inventoryCounts = new HashMap<>();
        for (Map.Entry<String, FishCatchInfoBox> entry : fishCatchInfoBoxes.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().getCount());
            holdCounts.put(entry.getKey(), entry.getValue().getHoldCount());
            inventoryCounts.put(entry.getKey(), entry.getValue().getInventoryCount());
        }
        configManager.setConfiguration(CONFIG_GROUP, FISH_COUNTS_KEY, gson.toJson(counts));
        configManager.setConfiguration(CONFIG_GROUP, FISH_HOLD_COUNTS_KEY, gson.toJson(holdCounts));
        configManager.setConfiguration(CONFIG_GROUP, FISH_INVENTORY_COUNTS_KEY, gson.toJson(inventoryCounts));
        log.debug("Saved fish counts: {} hold: {} inventory: {}", counts, holdCounts, inventoryCounts);
    }

    private void loadFishCounts() {
        String json = configManager.getConfiguration(CONFIG_GROUP, FISH_COUNTS_KEY);
        if (json == null) return;

        Type type = new TypeToken<Map<String, Integer>>(){}.getType();
        Map<String, Integer> counts = gson.fromJson(json, type);
        if (counts == null) return;

        String holdJson = configManager.getConfiguration(CONFIG_GROUP, FISH_HOLD_COUNTS_KEY);
        Map<String, Integer> holdCounts = holdJson != null ? gson.fromJson(holdJson, type) : new HashMap<>();
        if (holdCounts == null) holdCounts = new HashMap<>();

        String inventoryJson = configManager.getConfiguration(CONFIG_GROUP, FISH_INVENTORY_COUNTS_KEY);
        Map<String, Integer> inventoryCounts = inventoryJson != null ? gson.fromJson(inventoryJson, type) : new HashMap<>();
        if (inventoryCounts == null) inventoryCounts = new HashMap<>();

        // Clear any existing boxes before loading to avoid duplicates getting created
        for (FishCatchInfoBox infoBox : fishCatchInfoBoxes.values()) {
            infoBoxManager.removeInfoBox(infoBox);
        }
        fishCatchInfoBoxes.clear();

        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            String fishName = entry.getKey();
            int count = entry.getValue();
            if (count <= 0) continue;

            BufferedImage icon = getFishIcon(fishName);
            if (icon == null) continue;

            FishCatchInfoBox infoBox = new FishCatchInfoBox(icon, this, config, fishName);
            infoBox.incrementCount(count);
            int holdCount = holdCounts.getOrDefault(fishName, 0);
            if (holdCount > 0) infoBox.incrementHoldCount(holdCount);
            int inventoryCount = inventoryCounts.getOrDefault(fishName, 0);
            if (inventoryCount > 0) infoBox.incrementInventoryCount(inventoryCount);
            fishCatchInfoBoxes.put(fishName, infoBox);
            infoBoxManager.addInfoBox(infoBox);
        }
        log.debug("Loaded fish counts: {} hold: {} inventory: {}", counts, holdCounts, inventoryCounts);
    }

    private void updateNearestShoalSticky() {
        if (activeShoals.size() == 1) {
            nearestShoal = activeShoals.values().iterator().next();
        } else if (activeShoals.isEmpty()) {
            return;
        } else {
            WorldPoint playerLocation = client.getLocalPlayer() != null
                    ? client.getLocalPlayer().getWorldLocation()
                    : null;
            if (playerLocation == null) return;
            ShoalData bestShoal = pickBestShoal(playerLocation);
            nearestShoal = bestShoal;
        }
    }

    private ShoalData pickBestShoal(WorldPoint playerLocation) {
        ShoalData bestShoal = nearestShoal;
        int bestDist = playerLocation.distanceTo(nearestShoal.getCurrentWorldPoint());
        for (ShoalData shoal : activeShoals.values()) {
            WorldPoint worldPoint = shoal.getCurrentWorldPoint();
            if (worldPoint == null) continue;

            int shoalDistance = playerLocation.distanceTo(worldPoint);
            if (shoalDistance < bestDist) {
                bestDist = shoalDistance;
                bestShoal = shoal;
            }
        }
        return bestShoal;
    }
}
