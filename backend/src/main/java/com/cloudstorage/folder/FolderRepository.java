package com.cloudstorage.folder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FolderRepository extends JpaRepository<Folder, UUID> {

    Optional<Folder> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<Folder> findByOwnerId(UUID ownerId);

    List<Folder> findByOwnerIdAndParentIdIsNullOrderByNameAsc(UUID ownerId);

    List<Folder> findByOwnerIdAndParentIdOrderByNameAsc(UUID ownerId, UUID parentId);
}
