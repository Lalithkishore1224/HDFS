package com.cloudstorage.file;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;

/**
 * Decides how a file may be shown inline in the browser.
 * <p>
 * The decision is made here, from the file extension, and never from the content type the uploader
 * claimed. Serving arbitrary uploads inline from the application's own origin would let an uploaded
 * HTML or script file run in the signed-in session, so:
 * <ul>
 *   <li>images, PDF, audio and video are served with their real media type;</li>
 *   <li>text, code and markup (including HTML and XML) are served as {@code text/plain}, so they are
 *       displayed as source and never executed;</li>
 *   <li>SVG is served as an image but sandboxed, so no script in it can run if opened directly;</li>
 *   <li>anything else is not previewable and is offered as a download only.</li>
 * </ul>
 * The extension lists are mirrored by {@code frontend/src/lib/preview.ts}.
 */
public final class PreviewTypes {

    /** @param sandboxed whether the response must carry a sandboxing Content-Security-Policy */
    public record Inline(MediaType mediaType, boolean sandboxed) {
    }

    private static final MediaType PLAIN_TEXT = new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);

    private static final Map<String, Inline> BY_EXTENSION = buildTable();

    private PreviewTypes() {
    }

    public static Optional<Inline> inlineFor(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_EXTENSION.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT)));
    }

    private static Map<String, Inline> buildTable() {
        Map<String, Inline> table = new java.util.HashMap<>();

        for (String extension : new String[] {"png", "gif", "webp", "bmp", "ico", "avif"}) {
            table.put(extension, new Inline(MediaType.parseMediaType("image/" + extension), false));
        }
        table.put("jpg", new Inline(MediaType.IMAGE_JPEG, false));
        table.put("jpeg", new Inline(MediaType.IMAGE_JPEG, false));
        table.put("svg", new Inline(MediaType.parseMediaType("image/svg+xml"), true));

        table.put("pdf", new Inline(MediaType.APPLICATION_PDF, false));

        table.put("mp4", new Inline(MediaType.parseMediaType("video/mp4"), false));
        table.put("webm", new Inline(MediaType.parseMediaType("video/webm"), false));
        table.put("ogv", new Inline(MediaType.parseMediaType("video/ogg"), false));
        table.put("mov", new Inline(MediaType.parseMediaType("video/quicktime"), false));

        table.put("mp3", new Inline(MediaType.parseMediaType("audio/mpeg"), false));
        table.put("wav", new Inline(MediaType.parseMediaType("audio/wav"), false));
        table.put("ogg", new Inline(MediaType.parseMediaType("audio/ogg"), false));
        table.put("m4a", new Inline(MediaType.parseMediaType("audio/mp4"), false));
        table.put("flac", new Inline(MediaType.parseMediaType("audio/flac"), false));

        for (String extension : new String[] {
                "txt", "md", "markdown", "csv", "tsv", "json", "log", "xml", "html", "htm", "css", "js", "mjs", "ts",
                "tsx", "jsx", "java", "kt", "py", "rb", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "sh", "bash",
                "sql", "yml", "yaml", "toml", "ini", "conf", "env", "properties", "gradle", "tex"}) {
            table.put(extension, new Inline(PLAIN_TEXT, false));
        }
        return Map.copyOf(table);
    }
}
