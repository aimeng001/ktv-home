package com.homektv.library;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LibraryIdentityTest {

    @Test
    void differentRootsDoNotCompareAsTheSameLibrary() throws Exception {
        Path first = Files.createTempDirectory("library-identity-a-");
        Path second = Files.createTempDirectory("library-identity-b-");

        LibraryIdentity firstIdentity = LibraryIdentity.resolve(first);
        LibraryIdentity secondIdentity = LibraryIdentity.resolve(second);

        assertThat(LibraryIdentity.compare(
                firstIdentity.persistedValue(), secondIdentity))
                .isEqualTo(LibraryIdentity.IdentityState.MISMATCH);
    }

    @Test
    void sameRootKeepsThePersistedIdentity() throws Exception {
        Path root = Files.createTempDirectory("library-identity-");
        LibraryIdentity identity = LibraryIdentity.resolve(root);

        assertThat(LibraryIdentity.compare(
                identity.persistedValue(), LibraryIdentity.resolve(root)))
                .isEqualTo(LibraryIdentity.IdentityState.MATCH);
    }
}

