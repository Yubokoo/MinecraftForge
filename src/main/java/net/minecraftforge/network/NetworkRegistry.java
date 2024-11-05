/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */


package net.minecraftforge.network;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.network.Channel.VersionTest;
import net.minecraftforge.network.NetworkContext.NetworkMismatchData;
import net.minecraftforge.registries.DataPackRegistriesHooks;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import io.netty.util.Attribute;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tracks channels created by {@link ChannelBuilder}. This class is not intended for use by modders.
 */
@ApiStatus.Internal
public class NetworkRegistry {
    static final Logger LOGGER = LogManager.getLogger();
    static final Marker NETREGISTRY = MarkerManager.getMarker("NETREGISTRY");

    // Use ConcurrentHashMap for thread safety
    private static final Map<ResourceLocation, NetworkInstance> instances = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, NetworkInstance> byName = new ConcurrentHashMap<>();

    // Use volatile to ensure visibility across threads
    private static volatile boolean lock = false;

    public static boolean acceptsVanillaClientConnections() {
        return listRejectedVanillaMods(NetworkInstance::clientAcceptedVersions).isEmpty() &&
                DataPackRegistriesHooks.getSyncedCustomRegistries().isEmpty();    }

    public static boolean canConnectToVanillaServer() {
        return listRejectedVanillaMods(NetworkInstance::serverAcceptedVersions).isEmpty();
    }

    @Nullable
    public static NetworkInstance findTarget(ResourceLocation resourceLocation) {
        return byName.get(resourceLocation);
    }

    // Lists mods that reject vanilla connections
    static List<String> listRejectedVanillaMods(Function<NetworkInstance, VersionTest> testFunction) {
        return instances.values().stream()
                .filter(net -> !testFunction.apply(net).accepts(VersionTest.Status.VANILLA, -1))
                .peek(net -> LOGGER.debug("Channel '{}' : Vanilla acceptance test: REJECTED", net.getChannelName()))
                .map(net -> net.getChannelName().toString())
                .collect(Collectors.toList());
    }

    private static void logDebug(String message, Object... params) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(NETREGISTRY, message, params);
        }
    }

    private static void logError(String message, Object... params) {
        LOGGER.error(NETREGISTRY, message, params);
    }

    static Map<ResourceLocation, ServerStatusPing.ChannelData> buildChannelVersionsForListPing() {
        return instances.entrySet().stream()
                .collect(Collectors.toConcurrentMap(Map.Entry::getKey, e -> e.getValue().pingData));
    }

    static List<String> listRejectedVanillaMods(Function<NetworkInstance, VersionTest> testFunction) {
        List<String> rejectedChannels = (List<String>) instances.values().stream();
        for (var net : instances.values()) {
            boolean test = testFunction.apply(net).accepts(VersionTest.Status.VANILLA, -1);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(NETREGISTRY, "Channel '{}' : Vanilla acceptance test: {}", net.getChannelName(), test ? "ACCEPTED" : "REJECTED");
            }
            if (!test)
                results.add(net.getChannelName().toString());
        }

        if (!results.isEmpty()) {
            LOGGER.error(NETREGISTRY, "Channels [{}] rejected vanilla connections", String.join(", ", results));
            return results;
        }

        LOGGER.debug(NETREGISTRY, "Accepting channel list from vanilla");
        return Collections.emptyList();
    }

    @Nullable
    public static NetworkMismatchData validateChannels(Map<ResourceLocation, Integer> incoming, boolean fromClient) {
        var originName = fromClient ? "client" : "server";

        Set<ResourceLocation> missing = new HashSet<>();
        Map<ResourceLocation, NetworkMismatchData.Version> results = new ConcurrentHashMap<>();
        for (var net : instances.values()) {
            var name = net.getChannelName();
            VersionTest test = fromClient ? net.clientAcceptedVersions : net.serverAcceptedVersions;

            var status = VersionTest.Status.MISSING;
            var version = 0;
            if (incoming.containsKey(net.getChannelName())) {
                status = VersionTest.Status.PRESENT;
                version = incoming.get(net.getChannelName());
            }

            boolean accepted = test.accepts(status, version);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(NETREGISTRY, "Channel '{}' : Version test of '{} {}' from {} : {}", name, status, version, originName, accepted ? "ACCEPTED" : "REJECTED");
            }

            if (!accepted) {
                if (status == VersionTest.Status.MISSING)
                    missing.add(name);
                else
                    results.put(name, new NetworkMismatchData.Version(Integer.toString(version), Integer.toString(net.getNetworkProtocolVersion())));
            }
        }

        if (!results.isEmpty() || !missing.isEmpty()) {
            LOGGER.error(NETREGISTRY, "Channels [{}] rejected their {} side version number",
                    Stream.concat(missing.stream(), results.keySet().stream()).map(Object::toString).collect(Collectors.joining(",")), originName);
            return new NetworkMismatchData(results, missing, !fromClient, null);
        }

        LOGGER.debug(NETREGISTRY, "Accepting channel list from {}", originName);
        return null;
    }

    public static boolean checkListPingCompatibilityForClient(Map<ResourceLocation, ServerStatusPing.ChannelData> incoming) {
        Set<ResourceLocation> handled = new HashSet<>();
        var rejected = new ArrayList<String>();

        for (var net : instances.values()) {
            var status = VersionTest.Status.MISSING;
            var version = 0;
            if (incoming.containsKey(net.getChannelName())) {
                status = VersionTest.Status.PRESENT;
                version = incoming.get(net.getChannelName()).version();
            }

            boolean accepted = net.serverAcceptedVersions.accepts(status, version);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(NETREGISTRY, "Channel '{}' : Version test of '{} {}' during listping : {}", net.getChannelName(), status, version, accepted ? "ACCEPTED" : "REJECTED");
            }

            if (!accepted)
                rejected.add(net.getChannelName().toString());
            handled.add(net.getChannelName());
        }

        var missingButRequired = new ArrayList<String>();
        incoming.forEach((name, data) -> {
            if (data.required() && !handled.contains(name))
                missingButRequired.add(name.toString());
        });

        if (!rejected.isEmpty()) {
            LOGGER.error(NETREGISTRY, "Channels [{}] rejected their server side version number during listping", String.join(", ", rejected));
            return false;
        }

        if (!missingButRequired.isEmpty()) {
            LOGGER.error(NETREGISTRY, "The server is likely to require channel [{}] to be present, yet we don't have it", String.join(", ", missingButRequired));
            return false;
        }

        LOGGER.debug(NETREGISTRY, "Accepting channel list during listping");
        return true;
    }

    public static void lock() {
        lock = true;
    }

    @SuppressWarnings("unchecked")
    public static void onConnectionStart(Connection connection) {
        ForgeEventFactory.onConnectionStart(connection);
        var channel = connection.channel();
        for (var inst : instances.values()) {
            if (inst.attributes != null)
                inst.attributes.forEach((k, v) -> ((Attribute<Object>)channel.attr(k)).compareAndSet(null, v.apply(connection)));
            if (inst.channelHandler != null)
                inst.channelHandler.accept(connection);
        }
    }

    public static Map<ResourceLocation, Integer> buildChannelVersions() {
        var ret = new Object2IntOpenHashMap<ResourceLocation>(instances.size());
        for (var net : instances.values()) {
            ret.put(net.getChannelName(), net.getNetworkProtocolVersion());
        }
        return ret;
    }

    static List<ResourceLocation> buildRegisterList() {
        var ret = new ArrayList<ResourceLocation>(byName.keySet().size());
        for (var name : byName.keySet())
            if (!"minecraft".equals(name.getNamespace()))
                ret.add(name);
        return ret;
    }

    static void register(NetworkInstance instance, ResourceLocation name) {
        checkLock(instance);
        if (NetworkRegistry.byName.putIfAbsent(name, instance) != null) {
            error("Payload name " + name + " already registered.");
        }
    }

    static void register(NetworkInstance instance) {
        checkLock(instance);
        var name = instance.getChannelName();
        if (NetworkRegistry.instances.putIfAbsent(name, instance) != null) {
            error("Channel " + name + " already registered.");
        }
    }

    private static void checkLock(NetworkInstance instance) {
        if (NetworkRegistry.lock)
            error("Attempted to register channel " + instance.getChannelName() + " even though registry phase is over");
    }

    private static void error(String message) {
        NetworkRegistry.LOGGER.error(NetworkRegistry.NETREGISTRY, message);
        throw new IllegalArgumentException(message);
    }

    // Creates and registers a NetworkInstance if not already present, throws if duplicate
    public static NetworkInstance createInstance(ResourceLocation name, int networkProtocolVersion,
                                                 VersionTest clientAcceptedVersions, VersionTest serverAcceptedVersions) {
        if (lock) {
            throw new IllegalStateException("Attempted to create instance after registry lock.");
        }

        NetworkInstance networkInstance = new NetworkInstance(name, networkProtocolVersion, clientAcceptedVersions, serverAcceptedVersions);
        NetworkInstance oldInstance = instances.putIfAbsent(name, networkInstance);

        if (oldInstance != null) {
            throw new IllegalArgumentException("NetworkDirection Channel {" + name + "} already registered");
        }

        return networkInstance;
    }

    // Registers a new instance in byName map if not already registered, respecting lock
    static void register(NetworkInstance instance, ResourceLocation name) {
        if (lock) {
            throw new IllegalStateException("Cannot register the NetworkInstance with name " + name + " after the registry lock is enabled. Complete all registrations beforehand.");
        }
        if (byName.putIfAbsent(name, instance) != null) {
            throw new IllegalArgumentException("Payload name " + name + " is already registered. Ensure each name is unique.");
        }
    }


}