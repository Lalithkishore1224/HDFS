package com.cloudstorage.tiering;

import static org.assertj.core.api.Assertions.assertThat;

import com.cloudstorage.TestDatabase;
import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.UserAccount;
import com.cloudstorage.file.FileService;
import com.cloudstorage.file.StoredFile;
import java.io.ByteArrayInputStream;
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
 * Real workloads: several people uploading and opening files while the optimizer runs. Nothing may
 * fail, and every file must end up stored exactly once.
 */
@SpringBootTest(properties = {
        "storage.seed-demo-data=false",
        "optimizer.min-training-samples=1000"   // keep the optimizer on its simple baseline: this test is about concurrency
})
class ConcurrentUseTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    FileService files;

    @Autowired
    TieringService tiering;

    @Autowired
    AccountService accounts;

    @Test
    void parallelUploadsDownloadsAndAnOptimizerRunAllSucceed() throws Exception {
        List<UserAccount> people = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            people.add(accounts.register("c" + UUID.randomUUID().toString().substring(0, 12), "correct horse battery"));
        }
        byte[] payload = new byte[400 * 1024];
        java.util.Arrays.fill(payload, (byte) 'x');

        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Callable<Integer>> work = new ArrayList<>();
            for (UserAccount person : people) {
                for (int upload = 0; upload < 5; upload++) {
                    int number = upload;
                    work.add(() -> {
                        StoredFile stored = files.upload(person, "doc-" + number + ".txt", null, "text/plain",
                                payload.length, new ByteArrayInputStream(payload));
                        try (var content = files.download(person, stored.getId()).content()) {
                            return content.readAllBytes().length;
                        }
                    });
                }
            }
            for (int run = 0; run < 3; run++) {
                work.add(() -> {
                    tiering.optimize();
                    return 0;
                });
            }

            int downloadedFiles = 0;
            for (Future<Integer> result : pool.invokeAll(work)) {
                int bytes = result.get(); // rethrows any "database is locked" or I/O failure
                if (bytes > 0) {
                    downloadedFiles++;
                    assertThat(bytes).isEqualTo(payload.length);
                }
            }
            assertThat(downloadedFiles).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }

        for (UserAccount person : people) {
            assertThat(files.list(person, "", null)).hasSize(5);
        }
    }
}
