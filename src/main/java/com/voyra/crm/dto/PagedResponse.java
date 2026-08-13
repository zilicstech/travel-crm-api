package com.voyra.crm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Returned only when a caller supplies ?page=. Without it, list endpoints return a bare
 * JSON array exactly as before, so adding pagination breaks no existing consumer.
 */
@Data
@Builder
@Schema(description = "A single page of results")
public class PagedResponse<T> {

    @Schema(description = "The results on this page")
    private List<T> content;

    @Schema(description = "Zero-based index of this page", example = "0")
    private int page;

    @Schema(description = "Maximum results per page", example = "25")
    private int size;

    @Schema(description = "Total results across all pages", example = "120")
    private long totalElements;

    @Schema(description = "Total number of pages", example = "5")
    private int totalPages;

    public static <E, D> PagedResponse<D> from(Page<E> source, Function<E, D> mapper) {
        return PagedResponse.<D>builder()
                .content(source.getContent().stream().map(mapper).toList())
                .page(source.getNumber())
                .size(source.getSize())
                .totalElements(source.getTotalElements())
                .totalPages(source.getTotalPages())
                .build();
    }
}
