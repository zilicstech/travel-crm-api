package com.voyra.crm.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Size caps, the logo rules, the Office signatures and an unreadable stream. */
class UploadValidatorCoverageTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] OLE = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    @Test
    void aFileOverTheCallersCapIsRefusedInWholeMegabytes() {
        assertThatThrownBy(() -> UploadValidator.assertAllowed(file("a.png", PNG), 4))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("File is larger than 0 MB");
        assertThatThrownBy(() -> UploadValidator.assertAllowed(file("a.png", new byte[3 * 1024 * 1024]), 2L * 1024 * 1024))
                .hasMessage("File is larger than 2 MB");
        assertThatCode(() -> UploadValidator.assertAllowed(file("a.png", PNG), 1024)).doesNotThrowAnyException();
    }

    @Test
    void legacyWordAndExcelFilesNeedTheOleSignature() {
        assertThatCode(() -> UploadValidator.assertAllowed(file("a.doc", OLE))).doesNotThrowAnyException();
        assertThatCode(() -> UploadValidator.assertAllowed(file("a.xls", OLE))).doesNotThrowAnyException();
        assertThatThrownBy(() -> UploadValidator.assertAllowed(file("a.doc", new byte[]{(byte) 0xD0, (byte) 0xCF, 0, 0, 0, 0, 0, 0})))
                .hasMessageContaining("doesn't match its .doc extension");
    }

    @Test
    void aWebpNeedsBothRiffAndWebpMarkers() {
        byte[] riffOnly = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'A', 'V', 'I', ' '};
        assertThatThrownBy(() -> UploadValidator.assertAllowed(file("a.webp", riffOnly)))
                .hasMessageContaining(".webp extension");
        assertThatThrownBy(() -> UploadValidator.assertAllowed(file("a.webp", new byte[]{'X', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'})))
                .hasMessageContaining(".webp extension");
    }

    @Test
    void aCsvWithANulByteIsBinary() {
        assertThatThrownBy(() -> UploadValidator.assertAllowed(file("a.csv", new byte[]{'a', 0, 'b'})))
                .hasMessageContaining(".csv extension");
    }

    @Test
    void aFileWithoutANameIsRefused() {
        MultipartFile unnamed = mock(MultipartFile.class);
        when(unnamed.getOriginalFilename()).thenReturn(null);
        assertThatThrownBy(() -> UploadValidator.assertAllowed(unnamed))
                .hasMessage("This file type is not allowed. Upload a PDF, JPG, PNG, WEBP, Word, Excel or CSV file.");
    }

    @Test
    void anUnreadableFileAsksForAnotherUpload() throws IOException {
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getOriginalFilename()).thenReturn("a.png");
        InputStream in = mock(InputStream.class);
        when(broken.getInputStream()).thenReturn(in);
        when(in.readNBytes(1024)).thenThrow(new IOException("boom"));

        assertThatThrownBy(() -> UploadValidator.assertAllowed(broken))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("The file could not be read - try uploading it again");
    }

    @Test
    void aLogoIsAPngJpegOrWebpOfAtMostOneMegabyteWithMatchingBytes() {
        assertThatCode(() -> UploadValidator.assertLogo(file("logo.PNG", PNG))).doesNotThrowAnyException();
        assertThatThrownBy(() -> UploadValidator.assertLogo(null)).hasMessage(UploadValidator.LOGO_MESSAGE);
        assertThatThrownBy(() -> UploadValidator.assertLogo(file("logo.png", new byte[0]))).hasMessage(UploadValidator.LOGO_MESSAGE);
        assertThatThrownBy(() -> UploadValidator.assertLogo(file("logo.png", new byte[(int) UploadValidator.MAX_LOGO_BYTES + 1])))
                .hasMessage(UploadValidator.LOGO_MESSAGE);
        assertThatThrownBy(() -> UploadValidator.assertLogo(file("logo.pdf", "%PDF-".getBytes()))).hasMessage(UploadValidator.LOGO_MESSAGE);
        assertThatThrownBy(() -> UploadValidator.assertLogo(file("logo.png", new byte[]{1, 2, 3}))).hasMessage(UploadValidator.LOGO_MESSAGE);
    }

    @Test
    void theLogoContentTypeComesFromTheExtension() {
        assertThat(UploadValidator.logoContentType("tenants/x/logo.JPG")).isEqualTo("image/jpeg");
        assertThat(UploadValidator.logoContentType("a.webp")).isEqualTo("image/webp");
        assertThat(UploadValidator.logoContentType("a.pdf")).isNull();
        assertThat(UploadValidator.logoContentType("noext")).isNull();
        assertThat(UploadValidator.logoContentType(null)).isNull();
    }
}
