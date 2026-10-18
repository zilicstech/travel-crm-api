package com.voyra.crm.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadValidatorTest {

    private static final byte[] PDF = "%PDF-1.7\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
    private static final byte[] CSV = "date,amount\n".getBytes(StandardCharsets.UTF_8);

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    @Test
    void acceptsDocumentAndImageTypesCaseInsensitively() {
        Object[][] cases = {{"passport.pdf", PDF}, {"scan.JPG", JPG}, {"a.jpeg", JPG}, {"b.PNG", PNG}, {"c.docx", ZIP},
                {"d.xlsx", ZIP}, {"e.webp", WEBP}, {"statement.csv", CSV}, {"STMT.CSV", CSV}};
        for (Object[] c : cases) {
            assertThatCode(() -> UploadValidator.assertAllowed(file((String) c[0], (byte[]) c[1]))).doesNotThrowAnyException();
        }
    }

    @Test
    void rejectsExecutablesScriptsAndFilesWithNoExtension() {
        for (String name : new String[]{"setup.exe", "run.sh", "page.html", "x.svg", "noextension", "evil.pdf.exe"}) {
            assertThatThrownBy(() -> UploadValidator.assertAllowed(file(name, PDF)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not allowed");
        }
    }

    /** CRC-18: a renamed file is refused by its content, not just its name. */
    @Test
    void rejectsAFileWhoseContentIsNotWhatItsExtensionClaims() {
        byte[] exe = "MZ fake".getBytes(StandardCharsets.ISO_8859_1);
        for (String name : new String[]{"crc-disguised.pdf", "a.png", "b.jpg", "c.docx", "d.webp"}) {
            assertThatThrownBy(() -> UploadValidator.assertAllowed(file(name, exe)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("doesn't match");
        }
        assertThatThrownBy(() -> UploadValidator.assertAllowed(file("x.csv", new byte[]{'M', 'Z', 0, 0})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UploadValidator.assertAllowed(file("x.pdf", JPG)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
