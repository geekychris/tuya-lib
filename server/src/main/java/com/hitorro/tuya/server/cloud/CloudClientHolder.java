package com.hitorro.tuya.server.cloud;

import com.hitorro.tuya.cloud.TuyaCloudClient;
import com.hitorro.tuya.cloud.TuyaCloudRegion;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the currently-active {@link TuyaCloudClient}. The client can be
 * reconfigured at runtime from the UI — when settings change, this holder
 * builds a new client and swaps it in atomically. Reads from the DB on
 * startup; if the DB row is blank, falls back to env-var defaults
 * ({@code TUYA_CLOUD_ACCESS_ID} / {@code TUYA_CLOUD_ACCESS_SECRET} /
 * {@code TUYA_CLOUD_REGION}).
 */
@Component
public class CloudClientHolder {

    private static final Logger log = LoggerFactory.getLogger(CloudClientHolder.class);

    private final CloudConfigRepository repository;
    private final String envAccessId;
    private final String envAccessSecret;
    private final String envRegion;

    private final AtomicReference<TuyaCloudClient> client = new AtomicReference<>();
    private volatile boolean enabled = false;
    private volatile String accessId = "";
    private volatile String region = "us";

    public CloudClientHolder(
            CloudConfigRepository repository,
            @Value("${tuya.cloud.access-id:}") String envAccessId,
            @Value("${tuya.cloud.access-secret:}") String envAccessSecret,
            @Value("${tuya.cloud.region:us}") String envRegion) {
        this.repository = repository;
        this.envAccessId = envAccessId;
        this.envAccessSecret = envAccessSecret;
        this.envRegion = envRegion;
    }

    @PostConstruct
    void initFromDb() {
        Optional<CloudConfigEntity> opt = repository.findById(CloudConfigEntity.SINGLETON_ID);
        if (opt.isPresent() && isBlank(opt.get().getAccessId()) && !isBlank(envAccessId)) {
            // DB row exists but is empty and env vars are set: seed the DB so the UI
            // shows what's in env on first load.
            CloudConfigEntity e = opt.get();
            e.setAccessId(envAccessId);
            e.setAccessSecret(envAccessSecret);
            e.setRegion(isBlank(envRegion) ? "us" : envRegion);
            repository.save(e);
            apply(envAccessId, envAccessSecret, e.getRegion());
        } else if (opt.isPresent() && !isBlank(opt.get().getAccessId())) {
            apply(opt.get().getAccessId(), opt.get().getAccessSecret(), opt.get().getRegion());
        } else {
            apply(envAccessId, envAccessSecret, envRegion);
        }
    }

    /**
     * Apply a new configuration. Empty/blank credentials disable the client.
     * Returns the resulting enabled state.
     */
    public synchronized boolean apply(String newAccessId, String newAccessSecret, String newRegion) {
        TuyaCloudRegion regionEnum = TuyaCloudRegion.fromCode(isBlank(newRegion) ? "us" : newRegion);
        if (isBlank(newAccessId) || isBlank(newAccessSecret)) {
            this.client.set(null);
            this.enabled = false;
            this.accessId = "";
            this.region = regionEnum.name().toLowerCase();
            log.info("Tuya Cloud client disabled (no credentials configured)");
            return false;
        }
        this.client.set(new TuyaCloudClient(newAccessId, newAccessSecret, regionEnum));
        this.enabled = true;
        this.accessId = newAccessId;
        this.region = regionEnum.name().toLowerCase();
        log.info("Tuya Cloud client configured for region {} with access_id={}…", region, mask(newAccessId));
        return true;
    }

    public boolean isEnabled() { return enabled; }
    public String accessId() { return accessId; }
    public String region() { return region; }

    /** @throws IllegalStateException if disabled. */
    public TuyaCloudClient client() {
        TuyaCloudClient c = client.get();
        if (c == null) {
            throw new IllegalStateException(
                    "Tuya Cloud client is not configured — set credentials in the UI or env vars");
        }
        return c;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String mask(String s) {
        if (s == null || s.length() < 4) return "****";
        return s.substring(0, Math.min(6, s.length())) + "…";
    }
}
