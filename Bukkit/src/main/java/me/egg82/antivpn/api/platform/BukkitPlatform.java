package me.egg82.antivpn.api.platform;

import com.google.common.collect.ImmutableSet;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.checkerframework.checker.nullness.qual.NonNull;

public class BukkitPlatform implements Platform {
    private static final Set<UUID> uniquePlayers = new HashSet<>();
    private static final Set<String> uniqueIps = new HashSet<>();

    public static void addUniquePlayer(@NonNull UUID uuid) { uniquePlayers.add(uuid); }

    public static void addUniqueIp(@NonNull String ip) { uniqueIps.add(ip); }

    private final long startTime;

    public BukkitPlatform(long startTime) {
        this.startTime = startTime;
    }

    public @NonNull Type getType() { return Type.BUKKIT; }

    public @NonNull Set<UUID> getUniquePlayers() { return ImmutableSet.copyOf(uniquePlayers); }

    public @NonNull Set<String> getUniqueIps() { return ImmutableSet.copyOf(uniqueIps); }

    public @NonNull Instant getStartTime() { return Instant.ofEpochMilli(startTime); }
}