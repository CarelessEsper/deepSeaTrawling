package com.deepseatrawling;

import net.runelite.client.ui.overlay.infobox.InfoBox;

import java.awt.*;
import java.awt.image.BufferedImage;

public class FishCatchInfoBox extends InfoBox {
    private final DeepSeaTrawling plugin;
    private final DeepSeaTrawlingConfig config;
    private final String fishName;
    private int count;

    public FishCatchInfoBox(BufferedImage image, DeepSeaTrawling plugin, DeepSeaTrawlingConfig config, String fishName)
    {
        super(image, plugin);
        this.plugin = plugin;
        this.config = config;
        this.fishName = fishName;
        this.count = 0;
    }

    public void incrementCount(int amount)
    {
        this.count += amount;
    }

    public void resetCount()
    {
        this.count = 0;
    }

    public int getCount()
    {
        return count;
    }

    @Override
    public String getText()
    {
        return String.valueOf(count);
    }

    @Override
    public boolean render()
    {
        return (count > 0 && config.infoboxFishTypeEnabled());
    }

    @Override
    public String getTooltip()
    {
        return fishName + ": " + count;
    }

    @Override
    public Color getTextColor()
    {
        return config != null
                ? config.fishCounterTextColour()
                : Color.WHITE;
    }
}
