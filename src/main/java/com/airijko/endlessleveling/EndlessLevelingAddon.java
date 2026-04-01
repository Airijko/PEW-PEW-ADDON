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

import com.airijko.endlessleveling.commands.gate.GateCommand;
import com.airijko.endlessleveling.commands.AddonReloadCommand;
import com.airijko.endlessleveling.api.gates.InstanceDungeonDefinition;
import com.airijko.endlessleveling.compatibility.AddonDungeonGateContentProvider;
import com.airijko.endlessleveling.compatibility.AddonInstanceDungeonRegistry;
import com.airijko.endlessleveling.compatibility.AddonWaveGateContentProvider;
import com.airijko.endlessleveling.compatibility.EndlessLevelingCompatibility;
import com.airijko.endlessleveling.events.PortalDeathLoggingSystem;
import com.airijko.endlessleveling.events.PortalInstanceDiagnostics;
import com.airijko.endlessleveling.events.PortalLeveledInstanceRouter;
import com.airijko.endlessleveling.listeners.PortalGateJoinNotificationListener;
import com.airijko.endlessleveling.listeners.PortalReturnInteractionListener;
import com.airijko.endlessleveling.listeners.WavePortalBreakBlockSystem;
import com.airijko.endlessleveling.managers.AddonFilesManager;
import com.airijko.endlessleveling.managers.AddonGateInstanceRoutingManager;
import com.airijko.endlessleveling.managers.AddonGatesManager;
import com.airijko.endlessleveling.managers.AddonLoggingManager;
import com.airijko.endlessleveling.managers.ExampleFeatureManager;
import com.airijko.endlessleveling.managers.NaturalPortalGateManager;
import com.airijko.endlessleveling.managers.GateInstancePersistenceManager;
import com.airijko.endlessleveling.managers.PortalProximityManager;
import com.airijko.endlessleveling.managers.MobWaveManager;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.DrainPlayerFromWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.airijko.endlessleveling.registration.classes.ClassRegistration;
import com.airijko.endlessleveling.registration.augments.AugmentRegistration;
import com.airijko.endlessleveling.registration.passives.PassiveRegistration;
import com.airijko.endlessleveling.registration.races.RaceRegistration;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.logging.Level;

public class EndlessLevelingAddon extends JavaPlugin {

    private AddonFilesManager filesManager;
    private AddonDungeonGateContentProvider dungeonGateContentProvider;
    private AddonWaveGateContentProvider waveGateContentProvider;
    private final List<InstanceDungeonDefinition> instanceDungeonDefinitions = AddonInstanceDungeonRegistry.getDefinitions();
    private final Object reloadLock = new Object();

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

        this.getCommandRegistry().registerCommand(new GateCommand());
        this.getCommandRegistry().registerCommand(new AddonReloadCommand(this));

        dungeonGateContentProvider = new AddonDungeonGateContentProvider(this.filesManager);
        waveGateContentProvider = new AddonWaveGateContentProvider(this.filesManager);
        EndlessLevelingCompatibility.registerDungeonGateContentProvider(dungeonGateContentProvider);
        EndlessLevelingCompatibility.registerWaveGateContentProvider(waveGateContentProvider);
        registerInstanceDungeons();

        if (EndlessLevelingCompatibility.registerGatesManager(AddonGatesManager.INSTANCE)) {
                AddonLoggingManager.log(this, Level.INFO,
                    "[ELDungeonGateRegistry] Registered addon dungeon gate manager bridge.");
        }
        EndlessLevelingCompatibility.registerDungeonGateLifecycleBridge(AddonGatesManager.INSTANCE);
        EndlessLevelingCompatibility.registerWaveGateRuntimeBridge(AddonGatesManager.INSTANCE);
        EndlessLevelingCompatibility.registerWaveGateSessionExecutorBridge(AddonGatesManager.INSTANCE);
        EndlessLevelingCompatibility.registerGateInstanceRoutingBridge(AddonGateInstanceRoutingManager.INSTANCE);

        if (isAddonRuntimeEnabled()) {
            this.getEntityStoreRegistry().registerSystem(new PortalDeathLoggingSystem());
            this.getEntityStoreRegistry().registerSystem(new WavePortalBreakBlockSystem());
            NaturalPortalGateManager.initialize(this, this.filesManager);
            MobWaveManager.initialize();
            PortalProximityManager.initialize(this);
            PortalLeveledInstanceRouter.initialize(this);
            PortalLeveledInstanceRouter.setFilesManager(this.filesManager);
            GateInstancePersistenceManager.initialize();
            PortalLeveledInstanceRouter.restoreSavedGateInstances();
            PortalInstanceDiagnostics.initialize(this, this.filesManager);
            this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, PortalLeveledInstanceRouter::onAddPlayerToWorld);
            this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, PortalLeveledInstanceRouter::onPlayerReady);
            PortalGateJoinNotificationListener portalGateJoinNotificationListener = new PortalGateJoinNotificationListener();
            PortalReturnInteractionListener portalReturnInteractionListener = new PortalReturnInteractionListener();
            this.getEventRegistry().registerGlobal(PlayerReadyEvent.class, portalGateJoinNotificationListener::onPlayerReady);
            this.getEventRegistry().registerGlobal(PlayerInteractEvent.class, portalReturnInteractionListener::onPlayerInteract);
            this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, PortalInstanceDiagnostics::onAddPlayerToWorld);
            this.getEventRegistry().registerGlobal(DrainPlayerFromWorldEvent.class, PortalInstanceDiagnostics::onDrainPlayerFromWorld);
            this.getEventRegistry().registerGlobal(RemoveWorldEvent.class, PortalInstanceDiagnostics::onWorldRemoved);
        } else {
            AddonLoggingManager.log(this,
                    Level.INFO,
                    "[ELGateTypesRuntime] Addon runtime is disabled (core-owned mode). Dungeon gate and wave gate providers remain registered.");
        }
    }

    @Override
    protected void shutdown() {
        EndlessLevelingCompatibility.unregisterDungeonGateContentProvider(dungeonGateContentProvider);
        EndlessLevelingCompatibility.unregisterWaveGateContentProvider(waveGateContentProvider);
        unregisterInstanceDungeons();
        EndlessLevelingCompatibility.unregisterGateInstanceRoutingBridge(AddonGateInstanceRoutingManager.INSTANCE);
        EndlessLevelingCompatibility.unregisterWaveGateSessionExecutorBridge(AddonGatesManager.INSTANCE);
        EndlessLevelingCompatibility.unregisterWaveGateRuntimeBridge(AddonGatesManager.INSTANCE);
        EndlessLevelingCompatibility.unregisterDungeonGateLifecycleBridge(AddonGatesManager.INSTANCE);
        EndlessLevelingCompatibility.unregisterGatesManager(AddonGatesManager.INSTANCE);

        if (isAddonRuntimeEnabled()) {
            MobWaveManager.shutdown();
            PortalLeveledInstanceRouter.saveGateInstances();
            NaturalPortalGateManager.shutdown();
            PortalProximityManager.shutdown();
            PortalInstanceDiagnostics.purgeTrackedInstancesOnShutdown();
            PortalLeveledInstanceRouter.shutdown();
        }

        RaceRegistration.unregisterAll();
        ClassRegistration.unregisterAll();
        AugmentRegistration.unregisterAll();
        PassiveRegistration.unregisterAll();
    }

    @Nonnull
    public ReloadSummary reloadAddonRuntime() {
        synchronized (reloadLock) {
            if (this.filesManager == null) {
                this.filesManager = new AddonFilesManager(this);
            }

            this.filesManager.refreshContentOptions();
            this.filesManager.refreshDungeonGateOptions();
            PortalLeveledInstanceRouter.setFilesManager(this.filesManager);

            int unregisteredRaces = RaceRegistration.unregisterAll();
            int unregisteredClasses = ClassRegistration.unregisterAll();
            int unregisteredAugments = AugmentRegistration.unregisterAll();
            int unregisteredPassives = PassiveRegistration.unregisterAll();

            int registeredRaces = 0;
            int registeredClasses = 0;
            int registeredAugments = 0;
            int registeredPassives = 0;

            if (this.filesManager.shouldMergeRacesWithCore()) {
                registeredRaces = RaceRegistration.registerAll(this.filesManager.getRacesFolder());
            }
            if (this.filesManager.shouldMergeClassesWithCore()) {
                registeredClasses = ClassRegistration.registerAll(this.filesManager.getClassesFolder());
            }
            if (this.filesManager.shouldMergeAugmentsWithCore()) {
                registeredAugments = AugmentRegistration.registerAll(this.filesManager.getAugmentsFolder());
            }
            if (this.filesManager.shouldMergePassivesWithCore()) {
                registeredPassives = PassiveRegistration.registerAll(this.filesManager.getPassivesFolder());
            }

            ExampleFeatureManager.get().configure(
                    this.filesManager.shouldEnableExamples(),
                    this.filesManager.shouldEnableExampleCommand(),
                    this.filesManager.shouldEnableExampleEvents());

            if (isAddonRuntimeEnabled()) {
                NaturalPortalGateManager.shutdown();
                MobWaveManager.shutdown();
            }

            EndlessLevelingCompatibility.unregisterDungeonGateContentProvider(dungeonGateContentProvider);
            EndlessLevelingCompatibility.unregisterWaveGateContentProvider(waveGateContentProvider);
            unregisterInstanceDungeons();
            EndlessLevelingCompatibility.unregisterGateInstanceRoutingBridge(AddonGateInstanceRoutingManager.INSTANCE);
            EndlessLevelingCompatibility.unregisterWaveGateSessionExecutorBridge(AddonGatesManager.INSTANCE);
            EndlessLevelingCompatibility.unregisterWaveGateRuntimeBridge(AddonGatesManager.INSTANCE);
            EndlessLevelingCompatibility.unregisterDungeonGateLifecycleBridge(AddonGatesManager.INSTANCE);

            dungeonGateContentProvider = new AddonDungeonGateContentProvider(this.filesManager);
            waveGateContentProvider = new AddonWaveGateContentProvider(this.filesManager);
            EndlessLevelingCompatibility.registerDungeonGateContentProvider(dungeonGateContentProvider);
            EndlessLevelingCompatibility.registerWaveGateContentProvider(waveGateContentProvider);
            registerInstanceDungeons();

            EndlessLevelingCompatibility.unregisterGatesManager(AddonGatesManager.INSTANCE);
            EndlessLevelingCompatibility.registerGatesManager(AddonGatesManager.INSTANCE);
            EndlessLevelingCompatibility.registerDungeonGateLifecycleBridge(AddonGatesManager.INSTANCE);
            EndlessLevelingCompatibility.registerWaveGateRuntimeBridge(AddonGatesManager.INSTANCE);
            EndlessLevelingCompatibility.registerWaveGateSessionExecutorBridge(AddonGatesManager.INSTANCE);
            EndlessLevelingCompatibility.registerGateInstanceRoutingBridge(AddonGateInstanceRoutingManager.INSTANCE);

            if (isAddonRuntimeEnabled()) {
                NaturalPortalGateManager.initialize(this, this.filesManager);
                MobWaveManager.initialize();
                PortalInstanceDiagnostics.initialize(this, this.filesManager);
            }

                AddonLoggingManager.log(this,
                    Level.INFO,
                    "[ELReload] Runtime content reloaded. unregistered(r=%d,c=%d,a=%d,p=%d) registered(r=%d,c=%d,a=%d,p=%d)",
                    unregisteredRaces,
                    unregisteredClasses,
                    unregisteredAugments,
                    unregisteredPassives,
                    registeredRaces,
                    registeredClasses,
                    registeredAugments,
                    registeredPassives);

            return new ReloadSummary(
                    unregisteredRaces,
                    unregisteredClasses,
                    unregisteredAugments,
                    unregisteredPassives,
                    registeredRaces,
                    registeredClasses,
                    registeredAugments,
                    registeredPassives,
                    this.filesManager.isDungeonGateEnabled(),
                    this.filesManager.getDungeonSpawnIntervalMinutesMin(),
                    this.filesManager.getDungeonSpawnIntervalMinutesMax(),
                    this.filesManager.getDungeonDurationMinutes());
        }
    }

    private boolean isAddonRuntimeEnabled() {
        String prop = System.getProperty("el.addon.runtime.enabled");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        String env = System.getenv("EL_ADDON_RUNTIME_ENABLED");
        if (env != null) {
            return Boolean.parseBoolean(env);
        }
        return false;
    }

    private void registerInstanceDungeons() {
        for (InstanceDungeonDefinition definition : instanceDungeonDefinitions) {
            EndlessLevelingCompatibility.registerInstanceDungeon(definition);
        }
    }

    private void unregisterInstanceDungeons() {
        for (InstanceDungeonDefinition definition : instanceDungeonDefinitions) {
            EndlessLevelingCompatibility.unregisterInstanceDungeon(definition);
        }
    }

    public static final class ReloadSummary {
        public final int unregisteredRaces;
        public final int unregisteredClasses;
        public final int unregisteredAugments;
        public final int unregisteredPassives;
        public final int registeredRaces;
        public final int registeredClasses;
        public final int registeredAugments;
        public final int registeredPassives;
        public final boolean dungeonGateEnabled;
        public final int spawnIntervalMinutesMin;
        public final int spawnIntervalMinutesMax;
        public final int gateDurationMinutes;

        public ReloadSummary(int unregisteredRaces,
                int unregisteredClasses,
                int unregisteredAugments,
                int unregisteredPassives,
                int registeredRaces,
                int registeredClasses,
                int registeredAugments,
                int registeredPassives,
                boolean dungeonGateEnabled,
                int spawnIntervalMinutesMin,
                int spawnIntervalMinutesMax,
                int gateDurationMinutes) {
            this.unregisteredRaces = unregisteredRaces;
            this.unregisteredClasses = unregisteredClasses;
            this.unregisteredAugments = unregisteredAugments;
            this.unregisteredPassives = unregisteredPassives;
            this.registeredRaces = registeredRaces;
            this.registeredClasses = registeredClasses;
            this.registeredAugments = registeredAugments;
            this.registeredPassives = registeredPassives;
            this.dungeonGateEnabled = dungeonGateEnabled;
            this.spawnIntervalMinutesMin = spawnIntervalMinutesMin;
            this.spawnIntervalMinutesMax = spawnIntervalMinutesMax;
            this.gateDurationMinutes = gateDurationMinutes;
        }
    }
}
