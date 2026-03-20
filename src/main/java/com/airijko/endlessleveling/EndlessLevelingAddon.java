package com.airijko.endlessleveling;

import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.airijko.endlessleveling.registration.augments.AugmentRegistration;
import com.airijko.endlessleveling.registration.classes.ClassRegistration;
import com.airijko.endlessleveling.registration.passives.PassiveRegistration;
import com.airijko.endlessleveling.registration.races.RaceRegistration;
import com.airijko.endlessleveling.commands.ExampleCommand;
import com.airijko.endlessleveling.events.ExampleEvent;
import com.airijko.endlessleveling.managers.AddonFilesManager;

import javax.annotation.Nonnull;

public class EndlessLevelingAddon extends JavaPlugin {

    private AddonFilesManager filesManager;

    public EndlessLevelingAddon(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // Seed config and data folders from bundled resources on first startup.
        this.filesManager = new AddonFilesManager(this);
        
        // Register all content from respective folders
        if (this.filesManager.shouldMergeRacesWithCore()) {
            RaceRegistration.registerAll(this.filesManager.getRacesFolder());
        }
        if (this.filesManager.shouldMergeClassesWithCore()) {
            ClassRegistration.registerAll(this.filesManager.getClassesFolder());
        }
        if (this.filesManager.shouldMergeAugmentsWithCore()) {
            AugmentRegistration.registerAll(this.filesManager.getAugmentsFolder());
        }
        if (this.filesManager.shouldMergePassivesWithCore()) {
            PassiveRegistration.registerAll(this.filesManager.getPassivesFolder());
        }
        
        this.getCommandRegistry().registerCommand(new ExampleCommand("example", "An example command"));
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, ExampleEvent::onPlayerReady);
    }

    @Override
    protected void shutdown() {
        RaceRegistration.unregisterAll();
        ClassRegistration.unregisterAll();
        AugmentRegistration.unregisterAll();
        PassiveRegistration.unregisterAll();
    }
}