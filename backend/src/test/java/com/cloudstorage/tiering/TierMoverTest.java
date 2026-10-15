package com.cloudstorage.tiering;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudstorage.TestDatabase;
import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.UserAccount;
import com.cloudstorage.file.FileService;
import com.cloudstorage.file.StoredFile;
import com.cloudstorage.storage.FileStore;
import com.cloudstorage.storage.StorageTier;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression tests for a real incident: two requests for the same archived file, or a download racing
 * the optimizer, used to fail because each acted on a stale idea of where the bytes were.
 */
@SpringBootTest(properties = "storage.seed-demo-data=false")
class TierMoverTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    TierMover mover;

    @Autowired
    FileService files;

    @Autowired
    FileStore store;

    @Autowired
    AccountService accounts;

    private UUID newFile(String content) {
        UserAccount owner = accounts.register("t" + UUID.randomUUID().toString().substring(0, 12), "correct horse battery");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        StoredFile uploaded = files.upload(owner, "f.txt", null, "text/plain", bytes.length, new ByteArrayInputStream(bytes));
        return uploaded.getId();
    }

    private String read(TierMover.HotContent content) throws IOException {
        try (var stream = content.stream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void anArchivedFileIsRestoredOnceEvenWhenManyRequestsAskAtTheSameTime() throws Exception {
        String text = "archived text ".repeat(2000);
        UUID id = newFile(text);
        mover.move(id, StorageTier.COLD);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<TierMover.HotContent>> requests = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                requests.add(() -> mover.openOnHotTier(id));
            }
            int restores = 0;
            for (Future<TierMover.HotContent> future : pool.invokeAll(requests)) {
                TierMover.HotContent content = future.get();
                if (content.restoredFromCold()) {
                    restores++;
                }
                assertThat(read(content)).isEqualTo(text);
            }
            assertThat(restores).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(store.locate(id)).isEqualTo(StorageTier.HOT);
    }

    @Test
    void aDownloadStillWorksAfterTheOptimizerArchivedTheFileMomentsEarlier() throws IOException {
        UUID id = newFile("recently archived");

        mover.move(id, StorageTier.COLD); // the optimizer wins the race
        assertThat(store.locate(id)).isEqualTo(StorageTier.COLD);

        TierMover.HotContent content = mover.openOnHotTier(id);

        assertThat(content.restoredFromCold()).isTrue();
        assertThat(read(content)).isEqualTo("recently archived");
        assertThat(store.locate(id)).isEqualTo(StorageTier.HOT);
    }

    @Test
    void movingAFileToTheTierItIsAlreadyInChangesNothing() {
        UUID id = newFile("already hot");

        long size = mover.move(id, StorageTier.HOT);

        assertThat(size).isEqualTo("already hot".length());
        assertThat(store.locate(id)).isEqualTo(StorageTier.HOT);
    }
}
