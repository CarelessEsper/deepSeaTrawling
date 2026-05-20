package com.deepseatrawling;

import java.util.HashMap;
import java.util.Map;

public enum ShoalTypes {
    GIANT_KRILL(27, 28, 29, 30),
    HADDOCK(24, 25, 26),
    YELLOWFIN(21, 22, 23),
    HALIBUT(19, 20),
    BLUEFIN(17, 18),
    MARLIN(15, 16);

    private static final Map<Integer, ShoalTypes> SHOAL_ID = new HashMap<>();

    private static final Map<ShoalTypes, ShoalData.ShoalSpecies> SHOAL_TYPES_SHOAL_SPECIES_MAP = Map.of(
            GIANT_KRILL, ShoalData.ShoalSpecies.GIANT_KRILL,
            HADDOCK, ShoalData.ShoalSpecies.HADDOCK,
            YELLOWFIN, ShoalData.ShoalSpecies.YELLOWFIN,
            HALIBUT, ShoalData.ShoalSpecies.HALIBUT,
            BLUEFIN, ShoalData.ShoalSpecies.BLUEFIN,
            MARLIN, ShoalData.ShoalSpecies.MARLIN
    );

    static {
        for (ShoalTypes type : values()) {
            for (int id : type.ids) {
                SHOAL_ID.put(id, type);
            }
        }
    }

    private final int[] ids;

    ShoalTypes(int... ids) {
        this.ids = ids;
    }

    public int[] getIds() {
        return ids;
    }

    public static ShoalTypes fromId(int id) {
        return SHOAL_ID.get(id);
    }

    public static ShoalData.ShoalSpecies fromType(ShoalTypes shoal) { return SHOAL_TYPES_SHOAL_SPECIES_MAP.get(shoal); }

    public static ShoalData.ShoalSpecies fromIdToSpecies(int id) { return SHOAL_TYPES_SHOAL_SPECIES_MAP.get(SHOAL_ID.get(id)); }
}