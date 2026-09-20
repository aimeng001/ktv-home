package com.homektv.repo;

import com.homektv.domain.PlaybackVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PlaybackVariantRepository extends JpaRepository<PlaybackVariant, Long> {

    Optional<PlaybackVariant> findBySourceFileIdAndSourceFingerprintAndProfile(
            Long sourceFileId, String sourceFingerprint, String profile);

    /** Serializes creation of one source/profile variant inside the current PostgreSQL transaction. */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:lockKey AS text), 0))", nativeQuery = true)
    void lockForCreation(@Param("lockKey") String lockKey);
}
