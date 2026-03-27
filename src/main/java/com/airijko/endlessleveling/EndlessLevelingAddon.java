/*
 * Copyright (c) 2026 Airijko
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.airijko.endlessleveling;

import com.airijko.endlessleveling.commands.EndgameQoLPortalCommand;
import com.airijko.endlessleveling.commands.MajorDungeonsPortalCommand;
import com.airijko.endlessleveling.commands.PortalGateTestCommand;
import com.airijko.endlessleveling.events.ExampleEvent;
import com.airijko.endlessleveling.events.PortalDeathLoggingSystem;
import com.airijko.endlessleveling.events.PortalGateJoinNotificationListener;
import com.airijko.endlessleveling.events.PortalInstanceDiagnostics;
import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;
import com.airijko.endlessleveling.managers.AddonFilesManager;
import com.airijko.endlessleveling.managers.ExampleFeatureManager;
import com.airijko.endlessleveling.managers.NaturalPortalGateManager;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.airijko.endlessleveling.registration.classes.ClassRegistration;
import com.airijko.endlessleveling.registration.augments.AugmentRegistration;
import com.airijko.endlessleveling.registration.passives.PassiveRegistration;
import com.airijko.endlessleveling.registration.races.RaceRegistration;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

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

        ExampleFeatureManager.get().configure(
            this.filesManager.shouldEnableExamples(),
            this.filesManager.shouldEnableExampleCommand(),
            this.filesManager.shouldEnableExampleEvents());
        ExampleFeatureManager.get().registerExamples(this);

        this.getCommandRegistry().registerCommand(new MajorDungeonsPortalCommand());
        this.getCommandRegistry().registerCommand(new EndgameQoLPortalCommand());
        this.getCommandRegistry().registerCommand(new PortalGateTestCommand());
        this.getEntityStoreRegistry().registerSystem(new PortalDeathLoggingSystem());
        NaturalPortalGateManager.initialize(this);
        PortalLeveledInstanceRouter.initialize(this);
        PortalInstanceDiagnostics.initialize(this, this.filesManager);
        this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, PortalLeveledInstanceRouter::onAddPlayerToWorld);
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, PortalLeveledInstanceRouter::onPlayerReady);
        PortalGateJoinNotificationListener portalGateJoinNotificationListener = new PortalGateJoinNotificationListener();
        this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, portalGateJoinNotificationListener::onPlayerReady);
        this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, PortalInstanceDiagnostics::onAddPlayerToWorld);
        this.getEventRegistry().registerGlobal(DrainPlayerFromWorldEvent.class, PortalInstanceDiagnostics::onDrainPlayerFromWorld);
        this.getEventRegistry().registerGlobal(RemoveWorldEvent.class, PortalInstanceDiagnostics::onWorldRemoved);
    }

    @Override
    protected void shutdown() {
        NaturalPortalGateManager.shutdown();
        RaceRegistration.unregisterAll();
        ClassRegistration.unregisterAll();
        AugmentRegistration.unregisterAll();
        PassiveRegistration.unregisterAll();
    }
}
