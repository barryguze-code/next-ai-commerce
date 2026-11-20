package com.nextaicommerce.platform.web;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.assertThat;
class BuildVersionAdviceTest {
    @TempDir Path root;
    @Test void localBadgeTracksTheRealBranch() throws Exception {
        Files.createDirectory(root.resolve(".git"));
        Files.writeString(root.resolve(".git/HEAD"),"ref: refs/heads/development/next\n");
        assertThat(BuildVersionAdvice.checkoutBranch(root,"local")).isEqualTo("development/next");
        Files.writeString(root.resolve(".git/HEAD"),"ref: refs/heads/release/v1.0.0\n");
        assertThat(BuildVersionAdvice.checkoutBranch(root,"local")).isEqualTo("release/v1.0.0");
    }
    @Test void worktreesAndNonGitFoldersHaveUsefulLabels() throws Exception {
        assertThat(BuildVersionAdvice.checkoutBranch(root,"local")).isEqualTo("local");
        Files.createDirectory(root.resolve("git-metadata"));
        Files.writeString(root.resolve(".git"),"gitdir: git-metadata\n");
        Files.writeString(root.resolve("git-metadata/HEAD"),"1234567890123456789012345678901234567890\n");
        assertThat(BuildVersionAdvice.checkoutBranch(root,"local")).isEqualTo("detached · 12345678");
    }
}
