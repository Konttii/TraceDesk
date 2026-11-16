package com.tracedesk.web.controller;

import com.tracedesk.repository.DepartmentRepository;
import com.tracedesk.repository.PositionRepository;
import com.tracedesk.repository.RegionRepository;
import com.tracedesk.web.dto.DepartmentResponse;
import com.tracedesk.web.dto.PositionResponse;
import com.tracedesk.web.dto.RegionResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dictionaries")
@Tag(name = "Dictionaries", description = "Публичные справочники (регионы, следственные управления, должности)")
public class DictionaryController {

    private final RegionRepository regionRepository;
    private final DepartmentRepository departmentRepository;
    private final PositionRepository positionRepository;

    public DictionaryController(
            RegionRepository regionRepository,
            DepartmentRepository departmentRepository,
            PositionRepository positionRepository
    ) {
        this.regionRepository = regionRepository;
        this.departmentRepository = departmentRepository;
        this.positionRepository = positionRepository;
    }

    @Operation(summary = "Список регионов Республики Казахстан")
    @GetMapping("/regions")
    public ResponseEntity<List<RegionResponse>> getRegions() {
        List<RegionResponse> regions = regionRepository.findAllByOrderByIdAsc()
                .stream()
                .map(r -> new RegionResponse(r.getId(), r.getName()))
                .toList();
        return ResponseEntity.ok(regions);
    }

    @Operation(summary = "Список следственных управлений (департаментов)")
    @GetMapping("/departments")
    public ResponseEntity<List<DepartmentResponse>> getDepartments() {
        List<DepartmentResponse> departments = departmentRepository.findAllByOrderByIdAsc()
                .stream()
                .map(d -> new DepartmentResponse(d.getId(), d.getName()))
                .toList();
        return ResponseEntity.ok(departments);
    }

    @Operation(summary = "Список должностей")
    @GetMapping("/positions")
    public ResponseEntity<List<PositionResponse>> getPositions() {
        List<PositionResponse> positions = positionRepository.findAllByOrderByIdAsc()
                .stream()
                .map(p -> new PositionResponse(p.getId(), p.getName()))
                .toList();
        return ResponseEntity.ok(positions);
    }
}
