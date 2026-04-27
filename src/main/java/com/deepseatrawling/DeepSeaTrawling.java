package com.deepseatrawling;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatCommandManager;
import net.runelite.client.chat.ChatMessageBuilder;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.*;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
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

	@Inject
	private Client client;

	@Inject
	private ChatCommandManager chatCommandManager;

	private static final String CAUGHT_COMMAND = "!caught";

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
    private boolean notifiedFull = false;

    private int lastNotifiedDepth = -1;

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

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/deepseatrawling_icon.png");
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
		chatCommandManager.registerCommandAsync(CAUGHT_COMMAND, this::onCaughtCommand);

	}

	@Override
	protected void shutDown() {
		overlayManager.remove(overlay);
		overlayManager.remove(widgetOverlay);
		overlayManager.remove(trawlingNetOverlay);

		if (trawlingNetInfoBox != null) {
			infoBoxManager.removeInfoBox(trawlingNetInfoBox);
			trawlingNetInfoBox = null;
		}
        for (FishCatchInfoBox infoBox : fishCatchInfoBoxes.values()) {
			infoBoxManager.removeInfoBox(infoBox);
		}
		fishCatchInfoBoxes.clear();
		trackedShoals.clear();
		netObjectByIndex[0] = null;
		netObjectByIndex[1] = null;
        activeShoals.clear();
        nearestShoal = null;
		chatCommandManager.unregisterCommand(CAUGHT_COMMAND);
		log.info("Deep Sea Trawling Plugin Stopped");
	}

    public final GameObject[] netObjectByIndex = new GameObject[2];

	public int fishQuantity = 0;

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

		if (client.getLocalPlayer() != null && client.getLocalPlayer().getWorldView() != null && obj.getWorldView() != null && client.getLocalPlayer().getWorldView() == obj.getWorldView())
		{
			if (isStarboardNetObject(id)) {
				netObjectByIndex[0] = obj;
				return;
			}

			if (isPortNetObject(id)) {
				netObjectByIndex[1] = obj;
				return;
			}
		}
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
            shoal.setSpecies(species);
            shoal.setShoalObject(obj);

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

		if (netObjectByIndex[0] == obj) netObjectByIndex[0] = null;
		if (netObjectByIndex[1] == obj) netObjectByIndex[1] = null;

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
        if (state == GameState.HOPPING || state == GameState.LOGGING_IN) {
            fishQuantity = 0;
            wasOnBoat = false;
            for (FishCatchInfoBox infoBox : fishCatchInfoBoxes.values()) {
                infoBoxManager.removeInfoBox(infoBox);
            }
            fishCatchInfoBoxes.clear();
            if (nearestShoal != null) {
                nearestShoal.setDepth(ShoalData.ShoalDepth.UNKNOWN);
                nearestShoal.clearStopTimer();
                nearestShoal.setLast(null);
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
                fishQuantity = 0;
                log.debug("Emptied nets");
                notifiedFull = false;
                saveFishCounts();
            } else if (msg.equals("You take some fish from the net")) {
                log.debug("Unknown amount withdrawn from net, resetting to 0");
                fishQuantity = 0;
                notifiedFull = false;
                saveFishCounts();
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
					fishQuantity += amount;

					// Track individual fish types
					if (!fishName.isEmpty()) {
						trackFishCatch(toTitleCase(fishName), amount);
					}
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
					log.debug("Disembarked from boat - clearing fish counts");
					for (FishCatchInfoBox infoBox : fishCatchInfoBoxes.values()) {
						infoBox.resetCount();
						infoBoxManager.removeInfoBox(infoBox);
					}
					fishCatchInfoBoxes.clear();
					configManager.unsetRSProfileConfiguration(CONFIG_GROUP, FISH_COUNTS_KEY);
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

	private static final Map<String, Integer> WORD_NUMBERS = Map.of(
			"a", 1,
			"two", 2,
			"three", 3,
			"four", 4,
			"five", 5,
			"six", 6,
			"seven", 7,
			"eight", 8,
			"nine", 9,
			"ten", 10
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
	public boolean isPortNetObject(int objectId)
	{
		return objectId == ObjectID.SAILING_ROPE_TRAWLING_NET_3X8_PORT
				|| objectId == ObjectID.SAILING_LINEN_TRAWLING_NET_3X8_PORT
				|| objectId == ObjectID.SAILING_HEMP_TRAWLING_NET_3X8_PORT
				|| objectId == ObjectID.SAILING_COTTON_TRAWLING_NET_3X8_PORT;
	}

	public boolean isStarboardNetObject(int objectId)
	{
		return objectId == ObjectID.SAILING_ROPE_TRAWLING_NET_3X8_STARBOARD
				|| objectId == ObjectID.SAILING_LINEN_TRAWLING_NET_3X8_STARBOARD
				|| objectId == ObjectID.SAILING_HEMP_TRAWLING_NET_3X8_STARBOARD
				|| objectId == ObjectID.SAILING_COTTON_TRAWLING_NET_3X8_STARBOARD
				|| objectId == ObjectID.SAILING_ROPE_TRAWLING_NET
				|| objectId == ObjectID.SAILING_LINEN_TRAWLING_NET
				|| objectId == ObjectID.SAILING_HEMP_TRAWLING_NET
				|| objectId == ObjectID.SAILING_COTTON_TRAWLING_NET;
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

    private void onCaughtCommand(ChatMessage chatMessage, String message)
    {
        if (fishCatchInfoBoxes.isEmpty()) return;

        ChatMessageBuilder builder = new ChatMessageBuilder();
        boolean first = true;
        for (Map.Entry<String, FishCatchInfoBox> entry : fishCatchInfoBoxes.entrySet()) {
            int count = entry.getValue().getCount();
            if (count <= 0) continue;
            if (!first) builder.append(ChatColorType.NORMAL).append(", ");
            builder.append(ChatColorType.HIGHLIGHT).append(entry.getKey())
                    .append(ChatColorType.NORMAL).append(" caught: ")
                    .append(ChatColorType.HIGHLIGHT).append(String.format("%,d", count));
            first = false;
        }
        if (first) return;

        String response = builder.build();
        chatMessage.setMessage(response);
        chatMessage.getMessageNode().setRuneLiteFormatMessage(response);
        client.refreshChat();
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
        for (Map.Entry<String, FishCatchInfoBox> entry : fishCatchInfoBoxes.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().getCount());
        }
        configManager.setRSProfileConfiguration(CONFIG_GROUP, FISH_COUNTS_KEY, gson.toJson(counts));
        log.debug("Saved fish counts: {}", counts);
    }

    private void loadFishCounts() {
        String json = configManager.getRSProfileConfiguration(CONFIG_GROUP, FISH_COUNTS_KEY);
        if (json == null) return;

        Type type = new TypeToken<Map<String, Integer>>(){}.getType();
        Map<String, Integer> counts = gson.fromJson(json, type);
        if (counts == null) return;

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
            fishCatchInfoBoxes.put(fishName, infoBox);
            infoBoxManager.addInfoBox(infoBox);
        }
        log.debug("Loaded fish counts: {}", counts);
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
