package com.cloudstorage.file;

import com.cloudstorage.account.UserAccount;
import com.cloudstorage.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/files")
class FileController {

    private final FileService fileService;

    FileController(FileService fileService) {
        this.fileService = fileService;
    }

    record RenameRequest(@NotBlank String name) {
    }

    /** @param folderId the destination folder, or null for the top level */
    record MoveRequest(UUID folderId) {
    }

    @GetMapping
    List<FileResponse> list(@CurrentUser UserAccount user,
                            @RequestParam(defaultValue = "") String search,
                            @RequestParam(required = false) UUID folderId) {
        return fileService.list(user, search, folderId).stream().map(FileResponse::from).toList();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<FileResponse> upload(@CurrentUser UserAccount user,
                                        @RequestParam("file") MultipartFile upload,
                                        @RequestParam(required = false) UUID folderId) throws IOException {
        StoredFile file = fileService.upload(
                user, upload.getOriginalFilename(), folderId, upload.getContentType(), upload.getSize(), upload.getInputStream());
        return ResponseEntity.status(201).body(FileResponse.from(file));
    }

    @GetMapping("/{id}/download")
    ResponseEntity<InputStreamResource> download(@CurrentUser UserAccount user, @PathVariable UUID id) {
        FileService.Download download = fileService.download(user, id);
        StoredFile file = download.file();

        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.getName(), StandardCharsets.UTF_8)
                .build();
        MediaType mediaType = file.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(file.getContentType());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(mediaType)
                .contentLength(file.getSizeBytes())
                .body(new InputStreamResource(download.content()));
    }

    /**
     * Streams a file for display in the browser. Only types {@link PreviewTypes} considers safe are
     * served, and the content type comes from the file extension, not from the uploader.
     */
    @GetMapping("/{id}/view")
    ResponseEntity<InputStreamResource> view(@CurrentUser UserAccount user, @PathVariable UUID id) {
        StoredFile meta = fileService.get(user, id);
        PreviewTypes.Inline inline = PreviewTypes.inlineFor(meta.getName())
                .orElseThrow(() -> new NotPreviewableException(meta.getName()));

        FileService.Download download = fileService.download(user, id);
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(meta.getName(), StandardCharsets.UTF_8)
                .build();

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(inline.mediaType())
                .contentLength(meta.getSizeBytes());
        if (inline.sandboxed()) {
            response.header("Content-Security-Policy", "sandbox; default-src 'none'; style-src 'unsafe-inline'");
        }
        return response.body(new InputStreamResource(download.content()));
    }

    @PatchMapping("/{id}")
    FileResponse rename(@CurrentUser UserAccount user, @PathVariable UUID id,
                        @Valid @RequestBody RenameRequest request) {
        return FileResponse.from(fileService.rename(user, id, request.name()));
    }

    @PostMapping("/{id}/move")
    FileResponse move(@CurrentUser UserAccount user, @PathVariable UUID id, @RequestBody MoveRequest request) {
        return FileResponse.from(fileService.move(user, id, request.folderId()));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@CurrentUser UserAccount user, @PathVariable UUID id) {
        fileService.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
