package com.homektv.library;

import org.springframework.stereotype.Service;

import java.util.List;

/** Applies the audited allowlist to one identity-verified reference record. */
@Service
public final class IktvMetadataEnrichmentService {
    public List<IktvReferenceClassificationPolicy.CategoryAssignment> getCategoryAssignmentsForMatchedReference(
            IktvSongReferenceMatcher.ReferenceSong referenceSong) {
        return IktvReferenceClassificationPolicy.assignments(referenceSong);
    }
}
