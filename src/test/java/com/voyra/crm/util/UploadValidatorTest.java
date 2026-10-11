package com.voyra.crm.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadValidatorTest {

    private static MockMultipartFile file(String name) {
        return new MockMultipartFile("file", name, "application/octet-stream", new byte[]{1});
    }

    @Test
    void acceptsDocumentAndImageTypesCaseInsensitively() {
        for (String name : new String[]{"passport.pdf", "scan.JPG", "a.jpeg", "b.PNG", "c.docx", "d.xlsx", "statement.csv", "STMT.CSV"}) {
            assertThatCode(() -> UploadValidator.assertAllowed(file(name))).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsExecutablesScriptsAndFilesWithNoExtension() {
        for (String name : new String[]{"setup.exe", "run.sh", "page.html", "x.svg", "noextension", "evil.pdf.exe"}) {
            assertThatThrownBy(() -> UploadValidator.assertAllowed(file(name)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not allowed");
        }
    }
}
