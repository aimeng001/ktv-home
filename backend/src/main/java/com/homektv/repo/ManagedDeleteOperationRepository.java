package com.homektv.repo;

import com.homektv.domain.ManagedDeleteOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Repository for the durable Managed-library deletion journal. */
public interface ManagedDeleteOperationRepository extends JpaRepository<ManagedDeleteOperation, String> {
    List<ManagedDeleteOperation> findByStatusInOrderByCreatedAtAsc(Collection<String> statuses);
}
