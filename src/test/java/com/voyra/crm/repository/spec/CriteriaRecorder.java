package com.voyra.crm.repository.spec;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * Mockito-backed Criteria API whose every result describes the call that made it, so a test asserts the
 * predicate a Specification builds as text: {@code or(equal(root.agentId, A1), ...)}. {@code cb} calls
 * render without a receiver, {@code x.get("f")} renders as {@code x.f}, a subquery root as its entity name.
 * Every call is also kept in {@link #calls()} so subquery {@code where} clauses can be asserted.
 */
final class CriteriaRecorder implements Answer<Object> {

    private final Map<Object, String> names = new IdentityHashMap<>();
    private final List<String> calls = new ArrayList<>();

    final CriteriaBuilder cb = named(CriteriaBuilder.class, "");
    @SuppressWarnings("unchecked")
    final CriteriaQuery<Object> query = named(CriteriaQuery.class, "query");

    @SuppressWarnings("unchecked")
    <T> Root<T> root() {
        return named(Root.class, "root");
    }

    List<String> calls() {
        return calls;
    }

    String render(Object o) {
        if (o == null) return "null";
        if (names.containsKey(o)) return names.get(o);
        if (o instanceof Class<?> c) return c.getSimpleName();
        if (o instanceof Object[] arr) return Arrays.stream(arr).map(this::render).collect(Collectors.joining(", "));
        if (o instanceof Collection<?> c) return c.stream().map(this::render).collect(Collectors.joining(", ", "[", "]"));
        return String.valueOf(o);
    }

    @Override
    public Object answer(InvocationOnMock inv) {
        String method = inv.getMethod().getName();
        if (method.equals("toString")) return names.get(inv.getMock());
        String receiver = names.get(inv.getMock());
        Object[] args = inv.getArguments();
        String text;
        if (method.equals("get") && args.length == 1 && args[0] instanceof String field) {
            text = receiver + "." + field;
        } else if (method.equals("from") && args.length == 1 && args[0] instanceof Class<?> entity) {
            text = entity.getSimpleName();
        } else {
            text = (receiver.isEmpty() ? "" : receiver + ".") + method + "(" + render(args) + ")";
        }
        calls.add(text);
        Class<?> type = inv.getMethod().getReturnType();
        if (type == void.class || type.isPrimitive() || type.isArray() || Modifier.isFinal(type.getModifiers())) {
            return null;
        }
        return named(type, text);
    }

    @SuppressWarnings("unchecked")
    private <T> T named(Class<?> type, String name) {
        Object m = mock(type, withSettings().defaultAnswer(this));
        names.put(m, name);
        return (T) m;
    }
}
