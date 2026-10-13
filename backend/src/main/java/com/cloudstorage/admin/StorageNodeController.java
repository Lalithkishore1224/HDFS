package com.cloudstorage.admin;

import com.cloudstorage.storage.ReplicatedFileStore;
import com.cloudstorage.storage.ReplicatedFileStore.ClusterStatus;
import com.cloudstorage.storage.ReplicatedFileStore.FilePlacement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Administrator view of the storage nodes: where chunks live, take a node offline, add a node, repair. */
@RestController
@RequestMapping("/api/admin/storage")
class StorageNodeController {

    private final ReplicatedFileStore store;

    StorageNodeController(ReplicatedFileStore store) {
        this.store = store;
    }

    @GetMapping("/nodes")
    ClusterStatus nodes() {
        return store.status();
    }

    @PostMapping("/nodes")
    ClusterStatus addNode() {
        try {
            store.addNode();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return store.status();
    }

    @GetMapping("/files/{id}/placement")
    FilePlacement placement(@PathVariable UUID id) {
        try {
            return store.placementOf(id);
        } catch (java.io.UncheckedIOException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No stored content for that file");
        }
    }

    @PostMapping("/nodes/{name}/offline")
    ClusterStatus offline(@PathVariable String name) {
        return switchNode(name, false);
    }

    @PostMapping("/nodes/{name}/online")
    ClusterStatus online(@PathVariable String name) {
        return switchNode(name, true);
    }

    /** Several nodes failing together, for example {@code names=node-1,node-2}. */
    @PostMapping("/nodes/offline")
    ClusterStatus offlineTogether(@RequestParam List<String> names) {
        return switchNodes(names, false);
    }

    @PostMapping("/nodes/online")
    ClusterStatus onlineTogether(@RequestParam List<String> names) {
        return switchNodes(names, true);
    }

    @PostMapping("/repair")
    Map<String, Object> repair() {
        int copies = store.repair();
        return Map.of("copiesMade", copies, "cluster", store.status());
    }

    private ClusterStatus switchNode(String name, boolean online) {
        try {
            store.setOnline(name, online);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return store.status();
    }

    private ClusterStatus switchNodes(List<String> names, boolean online) {
        try {
            store.setOnline(names, online);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return store.status();
    }
}
