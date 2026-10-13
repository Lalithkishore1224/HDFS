package com.cloudstorage.folder;

import com.cloudstorage.TestDatabase;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.SignInProfile;
import com.cloudstorage.account.UserAccount;
import com.cloudstorage.file.FileService;
import com.cloudstorage.file.StoredFile;
import com.cloudstorage.file.StoredFileNotFoundException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "storage.seed-demo-data=false"
})
class FolderServiceTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    FolderService folders;

    @Autowired
    FileService files;

    @Autowired
    AccountService accounts;

    private UserAccount newOwner() {
        String id = UUID.randomUUID().toString();
        return accounts.signIn(new SignInProfile("test:" + id, id + "@example.com", "Owner", null));
    }

    @Test
    void foldersNestAndReportTheirPathAndItemCount() {
        UserAccount owner = newOwner();
        UUID projects = folders.create(owner.getId(), "Projects", null).id();
        UUID year = folders.create(owner.getId(), "2026", projects).id();
        upload(owner, "plan.txt", year);

        assertThat(folders.pathTo(owner.getId(), year)).extracting(FolderSummary::name).containsExactly("Projects", "2026");
        assertThat(folders.children(owner.getId(), projects)).singleElement().satisfies(child -> {
            assertThat(child.name()).isEqualTo("2026");
            assertThat(child.itemCount()).isEqualTo(1);
        });
    }

    @Test
    void siblingsMustHaveDistinctNamesIgnoringCaseButDifferentParentsMayRepeat() {
        UserAccount owner = newOwner();
        UUID parentA = folders.create(owner.getId(), "Parent A", null).id();
        UUID parentB = folders.create(owner.getId(), "Parent B", null).id();
        folders.create(owner.getId(), "Docs", parentA);

        assertThatThrownBy(() -> folders.create(owner.getId(), "docs", parentA)).isInstanceOf(DuplicateFolderNameException.class);
        assertThat(folders.create(owner.getId(), "Docs", parentB).name()).isEqualTo("Docs");
    }

    @Test
    void aFolderCannotBeMovedIntoItselfOrIntoItsOwnDescendant() {
        UserAccount owner = newOwner();
        UUID outer = folders.create(owner.getId(), "Outer", null).id();
        UUID inner = folders.create(owner.getId(), "Inner", outer).id();
        UUID deepest = folders.create(owner.getId(), "Deepest", inner).id();

        assertThatThrownBy(() -> folders.move(owner.getId(), outer, outer)).isInstanceOf(InvalidFolderMoveException.class);
        assertThatThrownBy(() -> folders.move(owner.getId(), outer, deepest)).isInstanceOf(InvalidFolderMoveException.class);

        folders.move(owner.getId(), deepest, null);
        assertThat(folders.pathTo(owner.getId(), deepest)).extracting(FolderSummary::name).containsExactly("Deepest");
    }

    @Test
    void deletingAFolderRemovesSubfoldersAndFilesButLeavesTheRestUntouched() {
        UserAccount owner = newOwner();
        UUID doomed = folders.create(owner.getId(), "Doomed", null).id();
        UUID child = folders.create(owner.getId(), "Child", doomed).id();
        UUID inDoomed = upload(owner, "a.txt", doomed).getId();
        UUID inChild = upload(owner, "b.txt", child).getId();
        UUID elsewhere = upload(owner, "keep.txt", null).getId();

        folders.delete(owner.getId(), doomed);

        assertThatThrownBy(() -> folders.pathTo(owner.getId(), child)).isInstanceOf(FolderNotFoundException.class);
        assertThatThrownBy(() -> files.download(owner, inDoomed)).isInstanceOf(StoredFileNotFoundException.class);
        assertThatThrownBy(() -> files.download(owner, inChild)).isInstanceOf(StoredFileNotFoundException.class);
        assertThat(files.list(owner, "", null)).extracting(StoredFile::getId).contains(elsewhere);
    }

    @Test
    void filesCanBeMovedBetweenFoldersAndListedPerFolder() {
        UserAccount owner = newOwner();
        UUID source = folders.create(owner.getId(), "Source", null).id();
        UUID target = folders.create(owner.getId(), "Target", null).id();
        StoredFile file = upload(owner, "moving.txt", source);

        files.move(owner, file.getId(), target);

        assertThat(files.list(owner, "", source)).isEmpty();
        assertThat(files.list(owner, "", target)).extracting(StoredFile::getName).containsExactly("moving.txt");
        assertThat(files.list(owner, "moving", null)).hasSize(1);
    }

    @Test
    void uploadingIntoAMissingFolderFails() {
        UserAccount owner = newOwner();
        assertThatThrownBy(() -> upload(owner, "lost.txt", UUID.randomUUID())).isInstanceOf(FolderNotFoundException.class);
    }

    @Test
    void peopleNeverSeeOrTouchEachOthersFoldersAndFiles() {
        UserAccount alice = newOwner();
        UserAccount bob = newOwner();
        UUID aliceFolder = folders.create(alice.getId(), "Private", null).id();
        UUID aliceFile = upload(alice, "secret.txt", aliceFolder).getId();

        assertThat(folders.children(bob.getId(), null)).isEmpty();
        assertThat(files.list(bob, "secret", null)).isEmpty();
        assertThatThrownBy(() -> folders.delete(bob.getId(), aliceFolder)).isInstanceOf(FolderNotFoundException.class);
        assertThatThrownBy(() -> files.download(bob, aliceFile)).isInstanceOf(StoredFileNotFoundException.class);
        assertThatThrownBy(() -> upload(bob, "sneaky.txt", aliceFolder)).isInstanceOf(FolderNotFoundException.class);
        assertThat(files.list(alice, "", aliceFolder)).hasSize(1);
    }

    private StoredFile upload(UserAccount owner, String name, UUID folderId) {
        byte[] content = name.getBytes();
        return files.upload(owner, name, folderId, "text/plain", content.length, new ByteArrayInputStream(content));
    }
}
