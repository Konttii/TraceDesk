package com.tracedesk.repository;

import com.tracedesk.domain.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DepartmentRepository extends JpaRepository<Department, Short> {

    List<Department> findAllByOrderByIdAsc();
}
