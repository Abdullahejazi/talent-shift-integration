package com.talentshift.hub.integration.canonical;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface ConnectedSystemRepository extends JpaRepository<ConnectedSystemEntity, UUID> {
    Optional<ConnectedSystemEntity> findBySystemKey(String systemKey);
}
