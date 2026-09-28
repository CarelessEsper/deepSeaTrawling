package com.deepseatrawling;

import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.client.config.Config;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.*;

public class TrawlingNetOverlay extends Overlay {

    private final Client client;
    private final DeepSeaTrawling plugin;
    private final NetActivityTracker netTracker;

    @Inject
    DeepSeaTrawlingConfig config;

    @Inject
    private TrawlingNetOverlay(Client client, DeepSeaTrawling plugin, NetActivityTracker netTracker) {
        this.client = client;
        this.plugin = plugin;
        this.netTracker = netTracker;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (netTracker.isKickHighlightActive() && netTracker.getKickedNetObject() != null) {
            renderKickedNetHighlight(graphics, netTracker.getKickedNetObject());
        }

        if (!config.highlightFullNets() && !config.highlightWrongDepthNets()) {
            return null;
        }
        if (plugin.netList[0] == null && plugin.netList[1] == null)
        {
            return null;
        }

        int totalNetSize = 0;
        if (plugin.netList[0] != null)
        {
            totalNetSize += plugin.netList[0].getNetSize();
        }
        if (plugin.netList[1] != null)
        {
            totalNetSize += plugin.netList[1].getNetSize();
        }

        int desiredDepth = -1;
        if (config.highlightWrongDepthNets())
        {
            ShoalData shoal = plugin.getNearestShoal();
            if (shoal != null && shoal.getDepth() != ShoalData.ShoalDepth.UNKNOWN)
            {
                desiredDepth = ShoalData.ShoalDepth.asInt(shoal.getDepth());
            }
        }

        for (TrawlingNetSide side : TrawlingNetSide.values())
        {
            GameObject netObj = netTracker.netObjects.get(side);
            if (netObj == null) continue;

            Net net = plugin.netList[side.ordinal()];
            if (net == null) continue;

            int currentDepth = net.getNetDepthAsInt();

            boolean full = config.highlightFullNets() && plugin.fishQuantity >= totalNetSize;
            boolean wrongDepth = config.highlightWrongDepthNets() && desiredDepth >= 1 && currentDepth >= 1 && currentDepth != desiredDepth;

            if ( full || wrongDepth )
            {
                trawlingNetOutline(graphics, full, netObj);
            }
        }

        return null;

    }

    private void trawlingNetOutline(Graphics2D graphic, boolean full, GameObject netObject) {
        if (netObject == null) {
            return;
        }

        Shape netShape = null;
        switch (config.netHighlightStyle()) {
            case CLICKBOX:
                netShape = netObject.getClickbox();
                break;
            case HULL_FILL:
            case OUTLINE:
                netShape = netObject.getConvexHull();
                break;
        }
        if (netShape == null) {
            return;
        }

        Color colour = full ? config.netFullHighlightColour() : config.netDepthHighlightColour();

        if (config.netHighlightStyle() == DeepSeaTrawlingConfig.NetHighlightStyle.HULL_FILL) {
            graphic.setColor(new Color(colour.getRed(), colour.getGreen(),colour.getBlue(), 60));
            graphic.fill(netShape);
        }

        OverlayUtil.renderPolygon(graphic, netShape, colour);
    }

    private void renderKickedNetHighlight(Graphics2D graphics, GameObject netObject) {
        Shape netShape = null;
        switch (config.netHighlightStyle()) {
            case CLICKBOX:
                netShape = netObject.getClickbox();
                break;
            case HULL_FILL:
            case OUTLINE:
                netShape = netObject.getConvexHull();
                break;
        }
        if (netShape == null) {
            return;
        }

        Color kickColour = config.netKickedHighlightColour();
        if (config.netHighlightStyle() == DeepSeaTrawlingConfig.NetHighlightStyle.HULL_FILL) {
            graphics.setColor(new Color(kickColour.getRed(), kickColour.getGreen(), kickColour.getBlue(), 60));
            graphics.fill(netShape);
        }
        OverlayUtil.renderPolygon(graphics, netShape, kickColour);
    }
}
