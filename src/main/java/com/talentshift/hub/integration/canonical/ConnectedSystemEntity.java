package com.talentshift.hub.integration.canonical;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "connected_system")
public class ConnectedSystemEntity {
    @Id private UUID id;
    @Column(name="system_key", nullable=false, unique=true) private String systemKey;
    @Column(name="display_name", nullable=false) private String displayName;
    private boolean enabled;
    @Column(name="created_at", insertable=false, updatable=false) private Instant createdAt;
    @Column(name="updated_at", insertable=false, updatable=false) private Instant updatedAt;
    protected ConnectedSystemEntity() {}
    public ConnectedSystemEntity(UUID id, String systemKey, String displayName, boolean enabled) {
        this.id=id; this.systemKey=systemKey; this.displayName=displayName; this.enabled=enabled;
    }
    public UUID getId(){return id;} public String getSystemKey(){return systemKey;}
    public String getDisplayName(){return displayName;} public boolean isEnabled(){return enabled;}
}
