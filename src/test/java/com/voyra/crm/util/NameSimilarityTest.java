package com.voyra.crm.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NameSimilarityTest {

    @Test
    void namesThatDifferInAWholeWordAreDifferentParties() {
        assertThat(NameSimilarity.likelySame("CRB2 Credit Client", "CRB2 Lane Client")).isFalse();
        assertThat(NameSimilarity.likelySame("Global Travels Pvt Ltd", "Global Tours Pvt Ltd")).isFalse();
    }

    @Test
    void typosReorderingsAndShortFormsStayFlagged() {
        assertThat(NameSimilarity.likelySame("Ajay Sharma", "Ajay Sharme")).isTrue();
        assertThat(NameSimilarity.likelySame("Rohan Gupta", "Rohan Guptha")).isTrue();
        assertThat(NameSimilarity.likelySame("Sharma Ajay", "ajay  sharma")).isTrue();
        assertThat(NameSimilarity.likelySame("Ajay Sharma", "Ajay Kumar Sharma")).isTrue();
        assertThat(NameSimilarity.likelySame("Acme Corp", "Acme Corporation")).isTrue();
    }
}
