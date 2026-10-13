package com.cloudstorage.folder;

import com.cloudstorage.account.UserAccount;
import com.cloudstorage.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/folders")
class FolderController {

    private static final String NAME_RULE = "[^/\\\\]+";
    private static final String NAME_MESSAGE = "Folder names cannot contain / or \\";

    private final FolderService folderService;

    FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    record CreateRequest(@NotBlank @Pattern(regexp = NAME_RULE, message = NAME_MESSAGE) String name, UUID parentId) {
    }

    record RenameRequest(@NotBlank @Pattern(regexp = NAME_RULE, message = NAME_MESSAGE) String name) {
    }

    /** @param parentId the new parent, or null to move the folder to the top level */
    record MoveRequest(UUID parentId) {
    }

    @GetMapping
    List<FolderResponse> children(@CurrentUser UserAccount user, @RequestParam(required = false) UUID parentId) {
        return folderService.children(user.getId(), parentId);
    }

    @GetMapping("/all")
    List<FolderSummary> all(@CurrentUser UserAccount user) {
        return folderService.all(user.getId());
    }

    @GetMapping("/{id}/path")
    List<FolderSummary> path(@CurrentUser UserAccount user, @PathVariable UUID id) {
        return folderService.pathTo(user.getId(), id);
    }

    @PostMapping
    ResponseEntity<FolderResponse> create(@CurrentUser UserAccount user, @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(201).body(folderService.create(user.getId(), request.name(), request.parentId()));
    }

    @PatchMapping("/{id}")
    FolderResponse rename(@CurrentUser UserAccount user, @PathVariable UUID id,
                          @Valid @RequestBody RenameRequest request) {
        return folderService.rename(user.getId(), id, request.name());
    }

    @PostMapping("/{id}/move")
    FolderResponse move(@CurrentUser UserAccount user, @PathVariable UUID id, @RequestBody MoveRequest request) {
        return folderService.move(user.getId(), id, request.parentId());
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@CurrentUser UserAccount user, @PathVariable UUID id) {
        folderService.delete(user.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
