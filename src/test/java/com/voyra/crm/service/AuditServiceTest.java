package com.voyra.crm.service;

import com.voyra.crm.dto.AuditChange;
import com.voyra.crm.dto.AuditLogResponse;
import com.voyra.crm.dto.PagedResponse;
import com.voyra.crm.entity.AuditLog;
import com.voyra.crm.enums.AuditAction;
import com.voyra.crm.enums.AuditEntityType;
import com.voyra.crm.repository.AuditLogRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The audit trail: what each record call stores, the empty-update no-op, and the search filters. */
@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock private AuditLogRepository auditLogRepository;
    @Mock private AuthorResolver authorResolver;
    @InjectMocks private AuditService service;

    private AuditLog saved() {
        ArgumentCaptor<AuditLog> c = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(c.capture());
        return c.getValue();
    }

    @Test
    void createRevealAndDeleteStoreTheActorActionAndSnapshot() {
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Agent One"));

        service.recordCreate(AuditEntityType.CLIENT, "C1", "Acme");
        AuditLog create = saved();
        assertThat(create.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(create.getEntityType()).isEqualTo(AuditEntityType.CLIENT);
        assertThat(create.getEntityId()).isEqualTo("C1");
        assertThat(create.getEntityLabel()).isEqualTo("Acme");
        assertThat(create.getActorId()).isEqualTo("A1");
        assertThat(create.getActorName()).isEqualTo("Agent One");
        assertThat(create.getFieldChanges()).isEmpty();
        assertThat(create.getId()).isNotBlank();
        assertThat(create.getCreatedAt()).isNotNull();

        org.mockito.Mockito.clearInvocations(auditLogRepository);
        service.recordReveal(AuditEntityType.CLIENT, "C1", "Acme");
        assertThat(saved().getAction()).isEqualTo(AuditAction.REVEAL);

        org.mockito.Mockito.clearInvocations(auditLogRepository);
        List<AuditChange> snapshot = List.of(new AuditChange("name", "Acme", null));
        service.recordDelete(AuditEntityType.CLIENT, "C1", "Acme", snapshot);
        AuditLog delete = saved();
        assertThat(delete.getAction()).isEqualTo(AuditAction.DELETE);
        assertThat(delete.getFieldChanges()).isEqualTo(snapshot);
    }

    @Test
    void anUpdateWithNoChangesLeavesNoRow() {
        service.recordUpdate(AuditEntityType.CLIENT, "C1", "Acme", List.of());
        verifyNoInteractions(auditLogRepository, authorResolver);
    }

    @Test
    void anUpdateStoresItsFieldChanges() {
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("O1", "Owner"));
        List<AuditChange> changes = List.of(new AuditChange("name", "Acme", "Acme Pvt Ltd"));

        service.recordUpdate(AuditEntityType.CLIENT, "C1", "Acme Pvt Ltd", changes);

        AuditLog u = saved();
        assertThat(u.getAction()).isEqualTo(AuditAction.UPDATE);
        assertThat(u.getFieldChanges()).isEqualTo(changes);
    }

    @Test
    void listForRecordMapsEveryField() {
        LocalDateTime at = LocalDateTime.of(2026, 10, 1, 12, 0);
        AuditLog row = AuditLog.builder().id("AL1").entityType(AuditEntityType.CLIENT).entityId("C1").entityLabel("Acme")
                .action(AuditAction.UPDATE).actorId("O1").actorName("Owner")
                .fieldChanges(List.of(new AuditChange("name", "a", "b"))).createdAt(at).build();
        when(auditLogRepository.findTop200ByEntityTypeAndEntityIdOrderByCreatedAtDesc(AuditEntityType.CLIENT, "C1"))
                .thenReturn(List.of(row));

        List<AuditLogResponse> out = service.listForRecord(AuditEntityType.CLIENT, "C1");

        AuditLogResponse r = out.get(0);
        assertThat(r.getId()).isEqualTo("AL1");
        assertThat(r.getEntityType()).isEqualTo(AuditEntityType.CLIENT);
        assertThat(r.getEntityId()).isEqualTo("C1");
        assertThat(r.getEntityLabel()).isEqualTo("Acme");
        assertThat(r.getAction()).isEqualTo(AuditAction.UPDATE);
        assertThat(r.getActorId()).isEqualTo("O1");
        assertThat(r.getActorName()).isEqualTo("Owner");
        assertThat(r.getChanges()).hasSize(1);
        assertThat(r.getCreatedAt()).isEqualTo(at);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchAddsOnePredicatePerSuppliedFilterAndSortsNewestFirst() {
        Pageable asked = PageRequest.of(2, 25);
        Pageable sorted = PageRequest.of(2, 25, Sort.by(Sort.Direction.DESC, "createdAt"));
        ArgumentCaptor<Specification<AuditLog>> spec = ArgumentCaptor.forClass(Specification.class);
        AuditLog row = AuditLog.builder().id("AL1").build();
        when(auditLogRepository.findAll(spec.capture(), eq(sorted))).thenReturn(new PageImpl<>(List.of(row), sorted, 51));

        PagedResponse<AuditLogResponse> page = service.search(AuditEntityType.VENDOR, "O1",
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), asked);

        assertThat(page.getContent()).extracting(AuditLogResponse::getId).containsExactly("AL1");
        assertThat(page.getTotalElements()).isEqualTo(51);

        Root<AuditLog> root = mock(Root.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> entityType = mock(Path.class);
        Path<Object> actorId = mock(Path.class);
        Path<Object> createdAt = mock(Path.class);
        when(root.get("entityType")).thenReturn(entityType);
        when(root.get("actorId")).thenReturn(actorId);
        when(root.get("createdAt")).thenReturn(createdAt);
        Predicate p = mock(Predicate.class);
        when(cb.and(any(Predicate.class), any(Predicate.class))).thenReturn(p);
        when(cb.equal(entityType, AuditEntityType.VENDOR)).thenReturn(p);
        when(cb.equal(actorId, "O1")).thenReturn(p);
        when(cb.greaterThanOrEqualTo(any(), eq(LocalDateTime.of(2026, 10, 1, 0, 0)))).thenReturn(p);
        when(cb.lessThan(any(), eq(LocalDateTime.of(2026, 11, 1, 0, 0)))).thenReturn(p);

        assertThat(spec.getValue().toPredicate(root, mock(CriteriaQuery.class), cb)).isSameAs(p);
        verify(cb).equal(entityType, AuditEntityType.VENDOR);
        verify(cb).equal(actorId, "O1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithNoFiltersMatchesEverything() {
        Pageable sorted = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));
        ArgumentCaptor<Specification<AuditLog>> spec = ArgumentCaptor.forClass(Specification.class);
        when(auditLogRepository.findAll(spec.capture(), eq(sorted))).thenReturn(new PageImpl<>(List.of(), sorted, 0));

        assertThat(service.search(null, null, null, null, PageRequest.of(0, 10)).getContent()).isEmpty();

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        assertThat(spec.getValue().toPredicate(mock(Root.class), mock(CriteriaQuery.class), cb)).isNull();
        verify(cb, never()).equal(any(), any(Object.class));
    }
}
