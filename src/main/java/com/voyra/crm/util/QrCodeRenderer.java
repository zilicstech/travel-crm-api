package com.voyra.crm.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.lowagie.text.Image;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Renders a QR code as an embeddable {@link Image} for the printed invoice (ACCOUNTING_EXPANSION
 * epic A). OpenPDF 1.3.39 ships no {@code BarcodeQRCode} class (that is an iText-only type), so
 * this encodes with zxing's {@code core} module directly - no AWT/Swing beyond what the JDK
 * already provides - and hands OpenPDF the resulting PNG bytes rather than a vector barcode.
 */
public final class QrCodeRenderer {

    private QrCodeRenderer() {
    }

    public static Image render(String payload, int sizePx) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN, 1);
            BitMatrix matrix = writer.encode(payload, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);

            BufferedImage image = new BufferedImage(sizePx, sizePx, BufferedImage.TYPE_BYTE_GRAY);
            for (int x = 0; x < sizePx; x++) {
                for (int y = 0; y < sizePx; y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            return Image.getInstance(png.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render QR code", e);
        }
    }
}
