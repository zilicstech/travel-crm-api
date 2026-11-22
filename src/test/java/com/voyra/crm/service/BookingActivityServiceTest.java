package com.voyra.crm.service;

import com.voyra.crm.dto.BookingActivityResponse;
import com.voyra.crm.entity.BookingActivity;
import com.voyra.crm.repository.BookingActivityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingActivityServiceTest {

    @Mock private BookingActivityRepository repository;
    @Mock private AuthorResolver authorResolver;
    @InjectMocks private BookingActivityService service;

    @Test
    void recordsWhoDidItAndCutsALongTextTo500() {
        when(authorResolver.resolveCurrentAuthor()).thenReturn(new AuthorResolver.AuthorInfo("A1", "Vikram"));
        when(repository.existsById(anyString())).thenReturn(false);

        service.record("B1", "X", "short");
        service.record("B1", "X", "y".repeat(600));

        ArgumentCaptor<BookingActivity> saved = ArgumentCaptor.forClass(BookingActivity.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0).getText()).isEqualTo("short");
        assertThat(saved.getAllValues().get(0).getByName()).isEqualTo("Vikram");
        assertThat(saved.getAllValues().get(0).getById()).isEqualTo("A1");
        assertThat(saved.getAllValues().get(0).getAt()).isNotNull();
        assertThat(saved.getAllValues().get(1).getText()).hasSize(500);
    }

    @Test
    void listsNewestFirstAsStored() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 22, 10, 0);
        when(repository.findByBookingIdOrderByAtDesc("B1")).thenReturn(List.of(
                BookingActivity.builder().id("1").bookingId("B1").kind("K").text("Did it").byName("Vikram").at(at).build()));
        List<BookingActivityResponse> rows = service.list("B1");
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getId()).isEqualTo("1");
            assertThat(r.getKind()).isEqualTo("K");
            assertThat(r.getText()).isEqualTo("Did it");
            assertThat(r.getByName()).isEqualTo("Vikram");
            assertThat(r.getAt()).isEqualTo(at);
        });
    }
}
