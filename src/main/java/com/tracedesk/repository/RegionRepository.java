package com.tracedesk.repository;

import com.tracedesk.domain.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RegionRepository extends JpaRepository<Region, String> {

    List<Region> findAllByOrderByIdAsc();
}
