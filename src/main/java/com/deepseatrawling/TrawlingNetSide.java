package com.deepseatrawling;

import lombok.Getter;
import net.runelite.api.gameval.ObjectID;

@Getter
public enum TrawlingNetSide
{
    STARBOARD(new int[]{
        ObjectID.SAILING_ROPE_TRAWLING_NET_3X8_STARBOARD,
        ObjectID.SAILING_LINEN_TRAWLING_NET_3X8_STARBOARD,
        ObjectID.SAILING_HEMP_TRAWLING_NET_3X8_STARBOARD,
        ObjectID.SAILING_COTTON_TRAWLING_NET_3X8_STARBOARD,
        ObjectID.SAILING_ROPE_TRAWLING_NET,
        ObjectID.SAILING_LINEN_TRAWLING_NET,
        ObjectID.SAILING_HEMP_TRAWLING_NET,
        ObjectID.SAILING_COTTON_TRAWLING_NET,
    }),
    PORT(new int[]{
        ObjectID.SAILING_ROPE_TRAWLING_NET_3X8_PORT,
        ObjectID.SAILING_LINEN_TRAWLING_NET_3X8_PORT,
        ObjectID.SAILING_HEMP_TRAWLING_NET_3X8_PORT,
        ObjectID.SAILING_COTTON_TRAWLING_NET_3X8_PORT,
    });

    private final int[] gameObjectIds;

    TrawlingNetSide(int[] gameObjectIds)
    {
        this.gameObjectIds = gameObjectIds;
    }

    public static TrawlingNetSide fromGameObjectId(int id)
    {
        for (TrawlingNetSide side : values())
        {
            for (int objectId : side.gameObjectIds)
            {
                if (objectId == id)
                {
                    return side;
                }
            }
        }
        return null;
    }
}
