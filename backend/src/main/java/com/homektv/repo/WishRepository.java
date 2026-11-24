package com.homektv.repo;

import com.homektv.domain.Wish;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Wish 心愿单数据访问接口，操作 wish 数据表。
 *
 * Wish repository for CRUD operations on the wish table.
 */
public interface WishRepository extends JpaRepository<Wish, Long> {

    List<Wish> findAllByOrderByCreatedAtDesc();

    List<Wish> findTop500ByOrderByCreatedAtDesc();

    List<Wish> findTop5000ByOrderByCreatedAtDesc();
}
