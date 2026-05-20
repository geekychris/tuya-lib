package com.hitorro.tuya.server.cloud;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "cloud_config")
public class CloudConfigEntity {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "access_id", length = 64)
    private String accessId;

    @Column(name = "access_secret", length = 128)
    private String accessSecret;

    @Column(nullable = false, length = 8)
    private String region = "us";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAccessId() { return accessId; }
    public void setAccessId(String accessId) { this.accessId = accessId; }
    public String getAccessSecret() { return accessSecret; }
    public void setAccessSecret(String accessSecret) { this.accessSecret = accessSecret; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public Instant getUpdatedAt() { return updatedAt; }
}
