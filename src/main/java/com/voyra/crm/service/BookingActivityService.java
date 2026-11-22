package com.voyra.crm.service;

import com.voyra.crm.dto.BookingActivityResponse;
import com.voyra.crm.entity.BookingActivity;
import com.voyra.crm.repository.BookingActivityRepository;
import com.voyra.crm.util.UniqueIdResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** The booking's activity list: what people did on it, readable by everyone who may open it. */
@Service
@RequiredArgsConstructor
public class BookingActivityService {

    private static final int MAX_TEXT = 500;

    private final BookingActivityRepository repository;
    private final AuthorResolver authorResolver;

    /** Records it as the signed-in user, in the caller's transaction. */
    @Transactional
    public void record(String bookingId, String kind, String text) {
        AuthorResolver.AuthorInfo by = authorResolver.resolveCurrentAuthor();
        repository.save(BookingActivity.builder()
                .id(UniqueIdResolver.resolve(repository::existsById))
                .bookingId(bookingId).kind(kind)
                .text(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text)
                .byId(by.id()).byName(by.name()).at(LocalDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<BookingActivityResponse> list(String bookingId) {
        return repository.findByBookingIdOrderByAtDesc(bookingId).stream()
                .map(a -> BookingActivityResponse.builder().id(a.getId()).kind(a.getKind()).text(a.getText())
                        .byName(a.getByName()).at(a.getAt()).build())
                .toList();
    }
}
