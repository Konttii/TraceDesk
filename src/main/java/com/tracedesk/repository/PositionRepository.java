package com.tracedesk.repository;

import com.tracedesk.domain.entity.Position;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PositionRepository extends JpaRepository<Position, Short> {

    List<Position> findAllByOrderByIdAsc();
}
