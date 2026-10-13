package com.cloudstorage.file;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileRepository extends JpaRepository<StoredFile, UUID> {

    Optional<StoredFile> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<StoredFile> findByOwnerIdAndNameContainingIgnoreCaseOrderByUploadedAtDesc(UUID ownerId, String nameFragment);

    List<StoredFile> findByOwnerIdAndFolderIdOrderByNameAsc(UUID ownerId, UUID folderId);

    List<StoredFile> findByOwnerIdAndFolderIdIsNullOrderByNameAsc(UUID ownerId);

    /** Everything directly inside a folder, whoever owns it; callers must have verified the folder already. */
    List<StoredFile> findByFolderIdOrderByNameAsc(UUID folderId);

    long countByFolderId(UUID folderId);

    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.ownerId = :ownerId")
    long totalSizeBytes(@Param("ownerId") UUID ownerId);
}
