package com.voyra.crm.repository.spec;

import org.springframework.data.jpa.domain.Specification;

import java.util.List;

/** {@link CriteriaRecorder} for specifications built outside this package (e.g. inside a service). */
public final class CriteriaProbe {

    private final CriteriaRecorder rec = new CriteriaRecorder();

    @SuppressWarnings({"unchecked", "rawtypes"})
    public String render(Specification<?> spec) {
        return rec.render(((Specification) spec).toPredicate(rec.root(), rec.query, rec.cb));
    }

    public List<String> calls() {
        return rec.calls();
    }
}
