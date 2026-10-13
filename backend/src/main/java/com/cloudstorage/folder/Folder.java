package com.cloudstorage.folder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A node in the folder tree. A null parent means the folder sits at the top level of the drive. */
@Entity
@Table(name = "folders")
public class Folder {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    /** The account that owns this folder. */
    private UUID ownerId;

    private UUID parentId;

    @Column(nullable = false)
    private Instant createdAt;

    protected Folder() {
        // required by JPA
    }

    public static Folder create(UUID ownerId, String name, UUID parentId, Instant now) {
        Folder folder = new Folder();
        folder.id = UUID.randomUUID();
        folder.ownerId = ownerId;
        folder.name = name;
        folder.parentId = parentId;
        folder.createdAt = now;
        return folder;
    }

    public void rename(String newName) {
        this.name = newName;
    }

    public void moveTo(UUID newParentId) {
        this.parentId = newParentId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public UUID getParentId() {
        return parentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
