package com.airijko.endlessleveling.managers;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * Wave pool configuration loaded from waves/wave_&lt;rank&gt;.json.
 * <p>
 * Each rank file defines one or more mob pools. Each wave randomly picks one pool
 * for normal mob spawns. Boss spawns draw from the dedicated boss_pool section.
 */
public final class WavePoolConfig {

    @Nonnull
    public final List<Pool> pools;

    @Nonnull
    public final List<String> bossPool;

    public WavePoolConfig(@Nonnull List<Pool> pools, @Nonnull List<String> bossPool) {
        this.pools = Collections.unmodifiableList(pools);
        this.bossPool = Collections.unmodifiableList(bossPool);
    }

    /** Returns true when no pools and no boss mobs are configured. */
    public boolean isEmpty() {
        return pools.isEmpty() && bossPool.isEmpty();
    }

    /**
     * Picks a random pool from {@link #pools}, or {@code null} if none are defined.
     * A new pool is drawn on every call, so each wave gets independent selection.
     */
    @Nullable
    public Pool pickRandomPool() {
        if (pools.isEmpty()) {
            return null;
        }
        int index = (int) Math.floor(Math.random() * pools.size());
        return pools.get(Math.max(0, Math.min(index, pools.size() - 1)));
    }

    /**
     * Picks a random boss role from {@link #bossPool}, or {@code null} if none are defined.
     */
    @Nullable
    public String pickRandomBoss() {
        if (bossPool.isEmpty()) {
            return null;
        }
        int index = (int) Math.floor(Math.random() * bossPool.size());
        return bossPool.get(Math.max(0, Math.min(index, bossPool.size() - 1)));
    }

    /** A named group of NPC role IDs used for regular mob spawns within a wave. */
    public static final class Pool {

        @Nonnull
        public final String id;

        @Nonnull
        public final List<String> mobs;

        public Pool(@Nonnull String id, @Nonnull List<String> mobs) {
            this.id = id;
            this.mobs = Collections.unmodifiableList(mobs);
        }

        /** Picks a random mob role ID from this pool, or an empty string if empty. */
        @Nonnull
        public String pickRandomMob() {
            if (mobs.isEmpty()) {
                return "";
            }
            int index = (int) Math.floor(Math.random() * mobs.size());
            return mobs.get(Math.max(0, Math.min(index, mobs.size() - 1)));
        }
    }
}
