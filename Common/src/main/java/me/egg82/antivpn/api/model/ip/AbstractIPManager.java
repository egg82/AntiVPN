package me.egg82.antivpn.api.model.ip;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import me.egg82.antivpn.api.APIException;
import me.egg82.antivpn.api.model.source.Source;
import me.egg82.antivpn.api.model.source.SourceManager;
import me.egg82.antivpn.api.model.source.models.SourceModel;
import me.egg82.antivpn.config.CachedConfig;
import me.egg82.antivpn.config.ConfigUtil;
import me.egg82.antivpn.core.Pair;
import me.egg82.antivpn.logging.GELFLogger;
import me.egg82.antivpn.messaging.packets.vpn.DeleteIPPacket;
import me.egg82.antivpn.messaging.packets.vpn.IPPacket;
import me.egg82.antivpn.storage.StorageService;
import me.egg82.antivpn.storage.models.IPModel;
import me.egg82.antivpn.utils.PacketUtil;
import me.egg82.antivpn.utils.TimeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractIPManager implements IPManager {
    protected final @NotNull Logger logger = new GELFLogger(LoggerFactory.getLogger(getClass()));

    private final @NotNull LoadingCache<Pair<String, AlgorithmMethod>, IPModel> ipCache;
    private final @NotNull LoadingCache<String, Boolean> sourceInvalidationCache;

    private final @NotNull SourceManager sourceManager;

    protected AbstractIPManager(@NotNull SourceManager sourceManager, @NotNull TimeUtil.Time cacheTime) {
        this.sourceManager = sourceManager;

        ipCache = Caffeine.newBuilder()
                .expireAfterAccess(cacheTime.getTime(), cacheTime.getUnit())
                .expireAfterWrite(cacheTime.getTime(), cacheTime.getUnit())
                .build(k -> calculateIpResult(k.getT1(), k.getT2(), true));
        sourceInvalidationCache = Caffeine.newBuilder().expireAfterWrite(1L, TimeUnit.MINUTES).build(k -> Boolean.FALSE);
    }

    @NotNull
    public LoadingCache<Pair<String, AlgorithmMethod>, IPModel> getIpCache() { return ipCache; }

    @Override
    @NotNull
    public CompletableFuture<@Nullable IP> getIP(@NotNull String ip) {
        return CompletableFuture.supplyAsync(() -> {
            CachedConfig cachedConfig = ConfigUtil.getCachedConfig();

            for (StorageService service : cachedConfig.getStorage()) {
                IPModel model = service.getIpModel(ip, cachedConfig.getSourceCacheTime());
                if (model != null) {
                    try {
                        return new GenericIP(InetAddress.getByName(ip), AlgorithmMethod.values()[model.getType()], model.getCascade(), model.getConsensus());
                    } catch (UnknownHostException ex) {
                        throw new IllegalArgumentException("Could not create InetAddress for " + model.getIp());
                    }
                }
            }
            return null;
        });
    }

    @Override
    @NotNull
    public CompletableFuture<Void> saveIP(@NotNull IP ip) {
        return CompletableFuture.runAsync(() -> {
            CachedConfig cachedConfig = ConfigUtil.getCachedConfig();

            for (StorageService service : cachedConfig.getStorage()) {
                IPModel model = service.getOrCreateIpModel(ip.getIP().getHostAddress(), ip.getType().ordinal());
                model.setCascade(ip.getCascade());
                model.setConsensus(ip.getConsensus());
                service.storeModel(model);
            }

            IPPacket packet = new IPPacket();
            packet.setIp(ip.getIP().getHostAddress());
            packet.setType(ip.getType());
            packet.setCascade(ip.getCascade());
            packet.setConsensus(ip.getConsensus());
            PacketUtil.queuePacket(packet);
        });
    }

    @Override
    @NotNull
    public CompletableFuture<Void> deleteIP(@NotNull String ip) {
        return CompletableFuture.runAsync(() -> {
            CachedConfig cachedConfig = ConfigUtil.getCachedConfig();

            for (StorageService service : cachedConfig.getStorage()) {
                IPModel model = new IPModel();
                model.setIp(ip);

                service.deleteModel(model);
            }

            DeleteIPPacket packet = new DeleteIPPacket();
            packet.setIp(ip);
            PacketUtil.queuePacket(packet);
        });
    }

    @Override
    @NotNull
    public CompletableFuture<@NotNull Set<@NotNull InetAddress>> getIPs() {
        return CompletableFuture.supplyAsync(() -> {
            CachedConfig cachedConfig = ConfigUtil.getCachedConfig();

            Set<InetAddress> retVal = new HashSet<>();
            for (StorageService service : cachedConfig.getStorage()) {
                Set<IPModel> models = service.getAllIps(cachedConfig.getSourceCacheTime());
                if (!models.isEmpty()) {
                    for (IPModel model : models) {
                        try {
                            retVal.add(InetAddress.getByName(model.getIp()));
                        } catch (UnknownHostException ex) {
                            logger.warn("Could not create InetAddress for " + model.getIp());
                        }
                    }
                    break;
                }
            }
            return retVal;
        });
    }

    @Override
    @NotNull
    public AlgorithmMethod getCurrentAlgorithmMethod() { return ConfigUtil.getCachedConfig().getVPNAlgorithmMethod(); }

    @Override
    @NotNull
    public CompletableFuture<@NotNull Boolean> cascade(@NotNull String ip, boolean useCache) {
        return CompletableFuture.supplyAsync(() -> {
            IPModel model;
            if (useCache) {
                try {
                    model = ipCache.get(new Pair<>(ip, AlgorithmMethod.CASCADE));
                } catch (CompletionException ex) {
                    if (ex.getCause() instanceof APIException) {
                        throw (APIException) ex.getCause();
                    } else {
                        throw new APIException(false, "Could not get data for IP " + ip, ex);
                    }
                } catch (RuntimeException ex) {
                    throw new APIException(false, "Could not get data for IP " + ip, ex);
                }
            } else {
                model = calculateIpResult(ip, AlgorithmMethod.CASCADE, false);
            }
            if (model == null) {
                throw new APIException(false, "Could not get data for IP " + ip);
            }
            return Boolean.TRUE.equals(model.getCascade());
        });
    }

    @Override
    @NotNull
    public CompletableFuture<@NotNull Double> consensus(@NotNull String ip, boolean useCache) {
        return CompletableFuture.supplyAsync(() -> {
            IPModel model;
            if (useCache) {
                try {
                    model = ipCache.get(new Pair<>(ip, AlgorithmMethod.CONSESNSUS));
                } catch (CompletionException ex) {
                    if (ex.getCause() instanceof APIException) {
                        throw (APIException) ex.getCause();
                    } else {
                        throw new APIException(false, "Could not get data for IP " + ip, ex);
                    }
                } catch (RuntimeException ex) {
                    throw new APIException(false, "Could not get data for IP " + ip, ex);
                }
            } else {
                model = calculateIpResult(ip, AlgorithmMethod.CONSESNSUS, false);
            }
            if (model == null) {
                throw new APIException(false, "Could not get data for IP " + ip);
            }
            return model.getConsensus() == null ? 1.0d : model.getConsensus();
        });
    }

    @Override
    public double getMinConsensusValue() { return ConfigUtil.getCachedConfig().getVPNAlgorithmConsensus(); }

    @NotNull
    private IPModel calculateIpResult(@NotNull String ip, @NotNull AlgorithmMethod method, boolean useCache) throws APIException {
        CachedConfig cachedConfig = ConfigUtil.getCachedConfig();

        if (useCache) {
            for (StorageService service : cachedConfig.getStorage()) {
                IPModel model = service.getIpModel(ip, cachedConfig.getSourceCacheTime());
                if (model != null && model.getType() == method.ordinal()) {
                    if (cachedConfig.getDebug()) {
                        logger.info("Found database value for IP " + ip + ".");
                    }
                    return model;
                }
            }
        }

        if (cachedConfig.getDebug()) {
            logger.info("Getting web result for IP " + ip + ".");
        }

        IPModel retVal = new IPModel();
        retVal.setIp(ip);
        retVal.setType(cachedConfig.getVPNAlgorithmMethod().ordinal());

        if (method == AlgorithmMethod.CONSESNSUS) {
            ExecutorService pool = Executors.newWorkStealingPool(cachedConfig.getThreads());
            List<Source<SourceModel>> sources = sourceManager.getSources();
            CountDownLatch latch = new CountDownLatch(sources.size());
            AtomicLong results = new AtomicLong(0L);
            AtomicLong totalSources = new AtomicLong(0L);
            for (Source<SourceModel> source : sources) {
                pool.submit(() -> {
                    if (Boolean.TRUE.equals(sourceInvalidationCache.get(source.getName()))) {
                        if (cachedConfig.getDebug()) {
                            logger.info("Skipping source " + source.getName() + " due to recent failure.");
                        }
                        latch.countDown();
                        return;
                    }
                    if (cachedConfig.getDebug()) {
                        logger.info("Getting result from source " + source.getName() + ".");
                    }
                    try {
                        if (source.getResult(ip).get()) {
                            results.addAndGet(1L);
                        }
                        totalSources.addAndGet(1L);
                    } catch (InterruptedException ignored) {
                        logger.error("Source " + source.getName() + " returned an error. Skipping.");
                        sourceInvalidationCache.put(source.getName(), Boolean.TRUE);
                        Thread.currentThread().interrupt();
                    } catch (ExecutionException | CancellationException ex) {
                        logger.error("Source " + source.getName() + " returned an error. Skipping.", ex);
                        sourceInvalidationCache.put(source.getName(), Boolean.TRUE);
                    }
                    latch.countDown();
                });
            }

            try {
                if (!latch.await(20L, TimeUnit.SECONDS)) {
                    logger.warn("Consensus timed out before all sources could be queried.");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            pool.shutdownNow(); // Kill it with fire

            if (totalSources.get() > 0L) {
                retVal.setConsensus((double) results.get() / (double) totalSources.get());
                if (useCache) {
                    storeResult(retVal, cachedConfig);
                    sendResult(retVal, cachedConfig);
                }
                return retVal;
            }
        } else {
            for (Source<SourceModel> source : sourceManager.getSources()) {
                if (Boolean.TRUE.equals(sourceInvalidationCache.get(source.getName()))) {
                    if (cachedConfig.getDebug()) {
                        logger.info("Skipping source " + source.getName() + " due to recent failure.");
                    }
                    continue;
                }
                if (cachedConfig.getDebug()) {
                    logger.info("Getting result from source " + source.getName() + ".");
                }
                try {
                    retVal.setCascade(source.getResult(ip).get());
                    if (retVal.getCascade() != null) {
                        break;
                    } else {
                        logger.error("Source " + source.getName() + " returned an error. Skipping.");
                        sourceInvalidationCache.put(source.getName(), Boolean.TRUE);
                    }
                } catch (InterruptedException ignored) {
                    logger.error("Source " + source.getName() + " returned an error. Skipping.");
                    sourceInvalidationCache.put(source.getName(), Boolean.TRUE);
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | CancellationException ex) {
                    logger.error("Source " + source.getName() + " returned an error. Skipping.", ex);
                    sourceInvalidationCache.put(source.getName(), Boolean.TRUE);
                }
            }

            if (useCache && retVal.getCascade() != null) {
                storeResult(retVal, cachedConfig);
                sendResult(retVal, cachedConfig);
            }
            return retVal;
        }

        throw new APIException(false, "No sources were available to query. See https://github.com/egg82/Anti-VPN/wiki/FAQ#Errors");
    }

    private void storeResult(@NotNull IPModel model, @NotNull CachedConfig cachedConfig) {
        for (StorageService service : cachedConfig.getStorage()) {
            IPModel m = service.getOrCreateIpModel(model.getIp(), model.getType());
            m.setCascade(model.getCascade());
            m.setConsensus(model.getConsensus());
            service.storeModel(m);
        }

        if (cachedConfig.getDebug()) {
            logger.info("Stored data for " + model.getIp() + " in storage.");
        }
    }

    private void sendResult(@NotNull IPModel model, @NotNull CachedConfig cachedConfig) {
        IPPacket packet = new IPPacket();
        packet.setIp(model.getIp());
        packet.setType(AlgorithmMethod.values()[model.getType()]);
        packet.setCascade(model.getCascade());
        packet.setConsensus(model.getConsensus());
        PacketUtil.queuePacket(packet);

        if (cachedConfig.getDebug()) {
            logger.info("Queued packet for " + model.getIp() + " in messaging.");
        }
    }
}
