package com.hitorro.tuya.server.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "devices")
public class DeviceEntity {

    public enum Source { LOCAL, CLOUD }

    /**
     * Per-device control transport.
     * AUTO: try LAN first; fall back to cloud (default; uses cloud for v3.4/3.5).
     * LAN: force LAN, never fall back. Surfaces real errors when LAN fails.
     * CLOUD: force cloud passthrough even if LAN would work.
     */
    public enum Transport { AUTO, LAN, CLOUD }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "gw_id", nullable = false, unique = true, length = 64)
    private String gwId;

    @Column(name = "local_key", length = 64)
    private String localKey;

    @Column(length = 64)
    private String ip;

    @Column(name = "protocol_version", nullable = false, length = 8)
    private String protocolVersion = "3.3";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source = Source.LOCAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_transport", nullable = false, length = 16)
    private Transport preferredTransport = Transport.AUTO;

    @Column(name = "dps_schema_json", columnDefinition = "CLOB")
    private String dpsSchemaJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getGwId() { return gwId; }
    public void setGwId(String gwId) { this.gwId = gwId; }
    public String getLocalKey() { return localKey; }
    public void setLocalKey(String localKey) { this.localKey = localKey; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getProtocolVersion() { return protocolVersion; }
    public void setProtocolVersion(String protocolVersion) { this.protocolVersion = protocolVersion; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public Transport getPreferredTransport() { return preferredTransport; }
    public void setPreferredTransport(Transport preferredTransport) { this.preferredTransport = preferredTransport; }
    public String getDpsSchemaJson() { return dpsSchemaJson; }
    public void setDpsSchemaJson(String dpsSchemaJson) { this.dpsSchemaJson = dpsSchemaJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
