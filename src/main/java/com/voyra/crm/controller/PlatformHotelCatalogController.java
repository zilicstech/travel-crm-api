package com.voyra.crm.controller;

import com.voyra.crm.dto.HotelCatalogStatusResponse;
import com.voyra.crm.service.HotelCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/platform/hotel-catalog")
@RequiredArgsConstructor
@Tag(name = "Platform - Hotel catalogue", description = "SUPER_ADMIN load of each hotel supplier's hotel list, for hotel name search")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class PlatformHotelCatalogController {

    private final HotelCatalogService service;

    @PostMapping("/{supplierId}/refresh")
    @Operation(summary = "Load or refresh a supplier's hotel catalogue", description = "Creates the catalogue when there is "
            + "none and refreshes it otherwise. Runs in the background (202); follow it with GET. 409 CATALOG_RUNNING "
            + "while a load runs. Nothing loads on its own.")
    public ResponseEntity<HotelCatalogStatusResponse> refresh(@PathVariable String supplierId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.refresh(supplierId));
    }

    @GetMapping
    @Operation(summary = "Each supplier's catalogue: load state, hotels held, last load and its error")
    public ResponseEntity<List<HotelCatalogStatusResponse>> status() {
        return ResponseEntity.ok(service.status());
    }
}
