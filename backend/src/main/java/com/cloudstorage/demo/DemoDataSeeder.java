package com.cloudstorage.demo;

import com.cloudstorage.access.AccessLog;
import com.cloudstorage.access.AccessLogRepository;
import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.SignInProfile;
import com.cloudstorage.account.UserAccount;
import com.cloudstorage.config.AuthProperties;
import com.cloudstorage.file.FileRepository;
import com.cloudstorage.file.FileService;
import com.cloudstorage.file.StoredFile;
import com.cloudstorage.folder.FolderService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills an empty drive with files that have a believable past, so the optimizer has history
 * to learn from the first time the app is opened. Disabled with {@code storage.seed-demo-data=false}.
 */
@Component
@ConditionalOnProperty("storage.seed-demo-data")
class DemoDataSeeder implements ApplicationRunner {

    private static final String SAMPLE_ROW = "2026,EMEA,enterprise,renewal,0.87,approved,quarterly-review\n";

    private enum Place { TOP_LEVEL, REPORTS, REPORTS_ARCHIVE, NOTES }

    private record SampleFile(String name, Place place, String contentType, int kilobytes, int uploadedDaysAgo,
                              List<Integer> accessedDaysAgo) {
    }

    private static final List<SampleFile> SAMPLE_FILES = List.of(
            new SampleFile("quarterly-report.txt", Place.REPORTS, "text/plain", 480, 60, List.of(1, 2, 3, 5, 8, 12, 20)),
            new SampleFile("customers.csv", Place.REPORTS, "text/csv", 1200, 45, List.of(0, 1, 4, 6, 9, 15)),
            new SampleFile("meeting-notes.md", Place.NOTES, "text/markdown", 40, 30, List.of(0, 1, 2, 3)),
            new SampleFile("project-brief.md", Place.NOTES, "text/markdown", 90, 20, List.of(1, 4, 11, 18)),
            new SampleFile("design-spec.md", Place.NOTES, "text/markdown", 150, 25, List.of(2, 3, 24)),
            new SampleFile("roadmap.md", Place.TOP_LEVEL, "text/markdown", 60, 40, List.of(10, 25)),
            new SampleFile("old-proposal.txt", Place.REPORTS_ARCHIVE, "text/plain", 300, 55, List.of(44, 50)),
            new SampleFile("server-2026-07.log", Place.REPORTS_ARCHIVE, "text/plain", 2000, 70, List.of(40, 65)),
            new SampleFile("invoice-0412.txt", Place.REPORTS_ARCHIVE, "text/plain", 20, 50, List.of(48)),
            new SampleFile("backup-config.json", Place.TOP_LEVEL, "application/json", 75, 80, List.of(78)),
            new SampleFile("archive-2025.csv", Place.REPORTS_ARCHIVE, "text/csv", 1600, 90, List.of(85)),
            new SampleFile("budget-2025.csv", Place.REPORTS_ARCHIVE, "text/csv", 220, 100, List.of(99)));

    private final FileService fileService;
    private final FolderService folderService;
    private final AccountService accounts;
    private final AuthProperties authProperties;
    private final FileRepository files;
    private final AccessLogRepository accessLogs;
    private final Clock clock;

    DemoDataSeeder(FileService fileService, FolderService folderService, AccountService accounts,
                   AuthProperties authProperties, FileRepository files, AccessLogRepository accessLogs, Clock clock) {
        this.fileService = fileService;
        this.folderService = folderService;
        this.accounts = accounts;
        this.authProperties = authProperties;
        this.files = files;
        this.accessLogs = accessLogs;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (files.count() > 0 || authProperties.adminEmails().isEmpty()) {
            return;
        }
        // The sample drive belongs to the first configured administrator, so signing in as them shows it.
        String ownerEmail = authProperties.normalized(authProperties.adminEmails().getFirst());
        UserAccount owner = accounts.signIn(SignInProfile.forDevelopment(ownerEmail, "Dev Admin"));
        Instant now = clock.instant();

        UUID reports = folderService.create(owner.getId(), "Reports", null).id();
        Map<Place, UUID> folderIds = new EnumMap<>(Place.class);
        folderIds.put(Place.TOP_LEVEL, null);
        folderIds.put(Place.REPORTS, reports);
        folderIds.put(Place.REPORTS_ARCHIVE, folderService.create(owner.getId(), "Archive", reports).id());
        folderIds.put(Place.NOTES, folderService.create(owner.getId(), "Notes", null).id());

        SAMPLE_FILES.forEach(sample -> seed(owner, sample, folderIds.get(sample.place()), now));
    }

    private void seed(UserAccount owner, SampleFile sample, UUID folderId, Instant now) {
        byte[] content = SAMPLE_ROW.repeat(sample.kilobytes() * 1024 / SAMPLE_ROW.length()).getBytes(StandardCharsets.UTF_8);
        StoredFile file = fileService.upload(owner, sample.name(), folderId, sample.contentType(), content.length, new ByteArrayInputStream(content));

        List<Instant> accesses = sample.accessedDaysAgo().stream().map(days -> now.minus(Duration.ofDays(days))).toList();
        Instant lastAccess = accesses.stream().max(Instant::compareTo).orElse(now.minus(Duration.ofDays(sample.uploadedDaysAgo())));
        file.restoreHistory(now.minus(Duration.ofDays(sample.uploadedDaysAgo())), lastAccess, accesses.size());

        accesses.forEach(time -> accessLogs.save(new AccessLog(file.getId(), time, 12, false)));
    }
}
