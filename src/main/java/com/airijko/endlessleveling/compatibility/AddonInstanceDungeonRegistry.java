package com.airijko.endlessleveling.compatibility;

import com.airijko.endlessleveling.api.gates.InstanceDungeonDefinition;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public final class AddonInstanceDungeonRegistry {

        private static volatile boolean majorDungeonContentAvailable = true;
        private static volatile boolean endgameContentAvailable = true;

    private static final List<InstanceDungeonDefinition> DEFINITIONS = List.of(
            new InstanceDungeonDefinition(
                    "major-dungeon-i",
                    "EL_MJ_Instance_D01",
                    "EL_MajorDungeonPortal_D01",
                    "major_dungeon_i",
                    "Major Dungeon I",
                    "MJ_Instance_D01",
                    "MJ_D01"),
            new InstanceDungeonDefinition(
                    "major-dungeon-ii",
                    "EL_MJ_Instance_D02",
                    "EL_MajorDungeonPortal_D02",
                    "major_dungeon_ii",
                    "Major Dungeon II",
                    "MJ_Instance_D02",
                    "MJ_D02"),
            new InstanceDungeonDefinition(
                    "major-dungeon-iii",
                    "EL_MJ_Instance_D03",
                    "EL_MajorDungeonPortal_D03",
                    "major_dungeon_iii",
                    "Major Dungeon III",
                    "MJ_Instance_D03",
                    "MJ_D03"),
            new InstanceDungeonDefinition(
                    "endgame-frozen-dungeon",
                    "EL_Endgame_Frozen_Dungeon",
                    "EL_EndgamePortal_Frozen_Dungeon",
                    "frozen_dungeon",
                    "Endgame Frozen Dungeon",
                    "Endgame_Frozen_Dungeon",
                    "EG_Frozen"),
            new InstanceDungeonDefinition(
                    "endgame-golem-void",
                    "EL_Endgame_Golem_Void",
                    "EL_EndgamePortal_Golem_Void",
                    "golem_void",
                    "Endgame Golem Void",
                    "Endgame_Golem_Void",
                    "EG_Golem"),
            new InstanceDungeonDefinition(
                    "endgame-swamp-dungeon",
                    "EL_Endgame_Swamp_Dungeon",
                    "EL_EndgamePortal_Swamp_Dungeon",
                    "swamp_dungeon",
                    "Endgame Swamp Dungeon",
                    "Endgame_Swamp_Dungeon",
                    "EG_Swamp"));

    private AddonInstanceDungeonRegistry() {
    }

        public static void configureDependencyAvailability(boolean majorAvailable, boolean endgameAvailable) {
                majorDungeonContentAvailable = majorAvailable;
                endgameContentAvailable = endgameAvailable;
        }

    @Nonnull
    public static List<InstanceDungeonDefinition> getDefinitions() {
                List<InstanceDungeonDefinition> filtered = new ArrayList<>();
                for (InstanceDungeonDefinition definition : DEFINITIONS) {
                            String portalBlockId = definition.basePortalBlockId();
                        if (portalBlockId != null && portalBlockId.startsWith("EL_MajorDungeonPortal_") && !majorDungeonContentAvailable) {
                                continue;
                        }
                        if (portalBlockId != null && portalBlockId.startsWith("EL_EndgamePortal_") && !endgameContentAvailable) {
                                continue;
                        }
                        filtered.add(definition);
                }
                return List.copyOf(filtered);
    }
}