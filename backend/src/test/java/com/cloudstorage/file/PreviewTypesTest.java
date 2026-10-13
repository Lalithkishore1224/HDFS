package com.cloudstorage.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class PreviewTypesTest {

    @Test
    void imagesPdfAndMediaAreServedWithTheirRealType() {
        assertThat(PreviewTypes.inlineFor("photo.PNG")).get().extracting(PreviewTypes.Inline::mediaType)
                .isEqualTo(MediaType.parseMediaType("image/png"));
        assertThat(PreviewTypes.inlineFor("a.b.jpeg")).get().extracting(PreviewTypes.Inline::mediaType)
                .isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(PreviewTypes.inlineFor("report.pdf")).get().extracting(PreviewTypes.Inline::mediaType)
                .isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(PreviewTypes.inlineFor("clip.mp4")).isPresent();
        assertThat(PreviewTypes.inlineFor("song.mp3")).isPresent();
    }

    @Test
    void markupAndScriptsAreShownAsPlainTextNeverAsExecutableContent() {
        for (String name : new String[] {"page.html", "page.HTM", "data.xml", "app.js", "notes.txt", "config.yml"}) {
            assertThat(PreviewTypes.inlineFor(name)).as(name).get().extracting(PreviewTypes.Inline::mediaType)
                    .satisfies(type -> {
                        assertThat(type.getType()).isEqualTo("text");
                        assertThat(type.getSubtype()).isEqualTo("plain");
                    });
        }
    }

    @Test
    void svgIsAnImageButSandboxedSoScriptsInsideItCannotRun() {
        assertThat(PreviewTypes.inlineFor("logo.svg")).get().satisfies(inline -> {
            assertThat(inline.mediaType()).isEqualTo(MediaType.parseMediaType("image/svg+xml"));
            assertThat(inline.sandboxed()).isTrue();
        });
    }

    @Test
    void unknownBinaryAndExtensionlessFilesAreNotPreviewable() {
        for (String name : new String[] {"archive.zip", "tool.exe", "doc.docx", "README", "trailingdot.", ".hidden"}) {
            assertThat(PreviewTypes.inlineFor(name)).as(name).isEmpty();
        }
    }
}
