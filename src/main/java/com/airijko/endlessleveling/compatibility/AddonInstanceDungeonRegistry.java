package com.airijko.endlessleveling.compatibility;

import com.airijko.endlessleveling.api.gates.InstanceDungeonDefinition;

import javax.annotation.Nonnull;
import java.util.List;

public final class AddonInstanceDungeonRegistry {

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

    @Nonnull
    public static List<InstanceDungeonDefinition> getDefinitions() {
        return DEFINITIONS;
    }
}