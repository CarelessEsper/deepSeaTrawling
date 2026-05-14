package com.deepseatrawling;

import net.runelite.client.ui.overlay.infobox.InfoBox;

import java.awt.*;
import java.awt.image.BufferedImage;

public class FishCatchInfoBox extends InfoBox {
    private final DeepSeaTrawling plugin;
    private final DeepSeaTrawlingConfig config;
    private final String fishName;
    private int count;
    private int holdCount;
    private int inventoryCount;

    public FishCatchInfoBox(BufferedImage image, DeepSeaTrawling plugin, DeepSeaTrawlingConfig config, String fishName)
    {
        super(image, plugin);
        this.plugin = plugin;
        this.config = config;
        this.fishName = fishName;
        this.count = 0;
        this.holdCount = 0;
    }

    public void incrementCount(int amount)
    {
        this.count += amount;
    }

    public void decrementCount(int amount)
    {
        this.count = Math.max(0, this.count - amount);
    }

    public void incrementHoldCount(int amount)
    {
        this.holdCount += amount;
    }

    public void incrementInventoryCount(int amount)
    {
        this.inventoryCount += amount;
    }

    public void resetCount()
    {
        this.count = 0;
        this.holdCount = 0;
        this.inventoryCount = 0;
    }

    public int getCount()
    {
        return count;
    }

    public int getHoldCount()
    {
        return holdCount;
    }

    public int getInventoryCount()
    {
        return inventoryCount;
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
        return "Total " + fishName + ": " + count + "</br>Sent to cargo: " + holdCount;
    }

    @Override
    public Color getTextColor()
    {
        return config != null
                ? config.fishCounterTextColour()
                : Color.WHITE;
    }
}
