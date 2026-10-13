package com.cloudstorage.folder;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The use cases of the folder tree: browse, create, rename, move and delete.
 * Every operation is scoped to one owner; another person's folder looks like it does not exist.
 */
@Service
public class FolderService {

    private final FolderRepository folders;
    private final FolderContents contents;
    private final Clock clock;

    public FolderService(FolderRepository folders, FolderContents contents, Clock clock) {
        this.folders = folders;
        this.contents = contents;
        this.clock = clock;
    }

    /** Lists the folders directly inside {@code parentId}, or at the top level when it is null. */
    @Transactional(readOnly = true)
    public List<FolderResponse> children(UUID ownerId, UUID parentId) {
        return childrenOf(ownerId, parentId).stream().map(this::withItemCount).toList();
    }

    @Transactional(readOnly = true)
    public List<FolderSummary> all(UUID ownerId) {
        return folders.findByOwnerId(ownerId).stream().map(FolderSummary::from).toList();
    }

    /** The folders from the top level down to {@code id}, inclusive; used for breadcrumbs. */
    @Transactional(readOnly = true)
    public List<FolderSummary> pathTo(UUID ownerId, UUID id) {
        List<FolderSummary> path = new ArrayList<>();
        Folder current = find(ownerId, id);
        while (current != null) {
            path.add(FolderSummary.from(current));
            current = current.getParentId() == null ? null : find(ownerId, current.getParentId());
        }
        Collections.reverse(path);
        return path;
    }

    @Transactional
    public FolderResponse create(UUID ownerId, String name, UUID parentId) {
        requireExists(ownerId, parentId);
        requireNameAvailable(ownerId, parentId, name, null);
        Folder folder = folders.save(Folder.create(ownerId, name.strip(), parentId, clock.instant()));
        return withItemCount(folder);
    }

    @Transactional
    public FolderResponse rename(UUID ownerId, UUID id, String newName) {
        Folder folder = find(ownerId, id);
        requireNameAvailable(ownerId, folder.getParentId(), newName, id);
        folder.rename(newName.strip());
        return withItemCount(folder);
    }

    @Transactional
    public FolderResponse move(UUID ownerId, UUID id, UUID newParentId) {
        Folder folder = find(ownerId, id);
        requireExists(ownerId, newParentId);
        if (newParentId != null && (newParentId.equals(id) || isInside(ownerId, newParentId, id))) {
            throw new InvalidFolderMoveException();
        }
        requireNameAvailable(ownerId, newParentId, folder.getName(), id);
        folder.moveTo(newParentId);
        return withItemCount(folder);
    }

    /** Deletes the folder together with every subfolder and file inside it. */
    @Transactional
    public void delete(UUID ownerId, UUID id) {
        Folder root = find(ownerId, id);
        Set<UUID> doomed = subtreeIds(ownerId, root.getId());
        contents.deleteFilesIn(doomed);
        folders.deleteAllById(doomed);
    }

    /** True if {@code candidateId} lies somewhere below {@code ancestorId} in the tree. */
    private boolean isInside(UUID ownerId, UUID candidateId, UUID ancestorId) {
        Set<UUID> visited = new HashSet<>();
        UUID current = candidateId;
        while (current != null && visited.add(current)) {
            if (current.equals(ancestorId)) {
                return true;
            }
            current = find(ownerId, current).getParentId();
        }
        return false;
    }

    private Set<UUID> subtreeIds(UUID ownerId, UUID rootId) {
        Set<UUID> ids = new HashSet<>();
        Deque<UUID> pending = new ArrayDeque<>(List.of(rootId));
        while (!pending.isEmpty()) {
            UUID current = pending.pop();
            if (ids.add(current)) {
                folders.findByOwnerIdAndParentIdOrderByNameAsc(ownerId, current)
                        .forEach(child -> pending.push(child.getId()));
            }
        }
        return ids;
    }

    private void requireNameAvailable(UUID ownerId, UUID parentId, String name, UUID ignoredFolderId) {
        boolean taken = childrenOf(ownerId, parentId).stream()
                .anyMatch(sibling -> sibling.getName().equalsIgnoreCase(name.strip()) && !sibling.getId().equals(ignoredFolderId));
        if (taken) {
            throw new DuplicateFolderNameException(name.strip());
        }
    }

    private void requireExists(UUID ownerId, UUID folderId) {
        if (folderId != null) {
            find(ownerId, folderId);
        }
    }

    private List<Folder> childrenOf(UUID ownerId, UUID parentId) {
        return parentId == null
                ? folders.findByOwnerIdAndParentIdIsNullOrderByNameAsc(ownerId)
                : folders.findByOwnerIdAndParentIdOrderByNameAsc(ownerId, parentId);
    }

    private FolderResponse withItemCount(Folder folder) {
        long subfolders = folders.findByOwnerIdAndParentIdOrderByNameAsc(folder.getOwnerId(), folder.getId()).size();
        long itemCount = subfolders + contents.countFilesIn(folder.getId());
        return new FolderResponse(folder.getId(), folder.getName(), folder.getParentId(), folder.getCreatedAt(), itemCount);
    }

    private Folder find(UUID ownerId, UUID id) {
        return folders.findByIdAndOwnerId(id, ownerId).orElseThrow(() -> new FolderNotFoundException(id));
    }
}
