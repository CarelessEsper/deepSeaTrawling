package com.deepseatrawling;

import net.runelite.client.ui.overlay.infobox.InfoBox;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class TrawlingNetInfoBox extends InfoBox {
    private final DeepSeaTrawling plugin;
    private final DeepSeaTrawlingConfig config;

    public TrawlingNetInfoBox(BufferedImage image, DeepSeaTrawling plugin, DeepSeaTrawlingConfig config)
    {
        super(image, plugin);
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public boolean render()
    {
        return (plugin.fishQuantity > 0 && config.infoboxIsEnabled());
    }

    @Override
    public String getText()
    {
        return String.valueOf(plugin.fishQuantity);
    }

    @Override
    public String getTooltip()
    {
        // Aggregate queue by fish type for display
        LinkedHashMap<String, Integer> byType = new LinkedHashMap<>();
        for (AbstractMap.SimpleEntry<String, Integer> entry : plugin.netQueue)
        {
            byType.merge(entry.getKey(), entry.getValue(), Integer::sum);
        }

        if (byType.isEmpty())
        {
            return "Fish in nets: " + plugin.fishQuantity;
        }

        StringBuilder sb = new StringBuilder("Fish in nets: ").append(plugin.fishQuantity);
        for (Map.Entry<String, Integer> entry : byType.entrySet())
        {
            sb.append("</br>").append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return sb.toString();
    }

    @Override
    public Color getTextColor()
    {
        return config != null
                ? config.fishCounterTextColour()
                : Color.WHITE;
    }

}
