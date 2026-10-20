package com.anscanner.app.service;

import org.junit.Test;
import static org.junit.Assert.*;

public class PdfGeneratorTest {

    @Test
    public void testEnumValues() {
        assertEquals(3, PdfGenerator.PageFormat.values().length);
        assertEquals(PdfGenerator.PageFormat.FIT_IMAGE, PdfGenerator.PageFormat.valueOf("FIT_IMAGE"));
        assertEquals(PdfGenerator.PageFormat.A4, PdfGenerator.PageFormat.valueOf("A4"));
        assertEquals(PdfGenerator.PageFormat.LETTER, PdfGenerator.PageFormat.valueOf("LETTER"));

        assertEquals(2, PdfGenerator.PageFitMode.values().length);
        assertEquals(PdfGenerator.PageFitMode.FIT_PAGE, PdfGenerator.PageFitMode.valueOf("FIT_PAGE"));
        assertEquals(PdfGenerator.PageFitMode.FILL_PAGE, PdfGenerator.PageFitMode.valueOf("FILL_PAGE"));
    }

    @Test
    public void testComputePageLayoutFitImageDefaultDpi() {
        // 1080 x 2400 screenshot at default 72 DPI -> 1080 x 2400 pt
        PdfGenerator.PageLayout layout = PdfGenerator.computePageLayout(
                1080, 2400, 72f, PdfGenerator.PageFormat.FIT_IMAGE, PdfGenerator.PageFitMode.FIT_PAGE
        );
        assertEquals(1080, layout.pageWidth);
        assertEquals(2400, layout.pageHeight);
        assertNull(layout.srcRect);
    }

    @Test
    public void testComputePageLayoutFitImageCustomDpi() {
        // 3000 x 4000 image at 300 DPI: (72/300) = 0.24 -> 720 x 960 pt
        PdfGenerator.PageLayout layout = PdfGenerator.computePageLayout(
                3000, 4000, 300f, PdfGenerator.PageFormat.FIT_IMAGE, PdfGenerator.PageFitMode.FIT_PAGE
        );
        assertEquals(720, layout.pageWidth);
        assertEquals(960, layout.pageHeight);
        assertNull(layout.srcRect);
    }

    @Test
    public void testComputePageLayoutA4Portrait() {
        PdfGenerator.PageLayout layout = PdfGenerator.computePageLayout(
                1000, 2000, 72f, PdfGenerator.PageFormat.A4, PdfGenerator.PageFitMode.FIT_PAGE
        );
        assertEquals(PdfGenerator.PAGE_WIDTH_A4, layout.pageWidth);
        assertEquals(PdfGenerator.PAGE_HEIGHT_A4, layout.pageHeight);
    }

    @Test
    public void testComputePageLayoutA4Landscape() {
        PdfGenerator.PageLayout layout = PdfGenerator.computePageLayout(
                2000, 1000, 72f, PdfGenerator.PageFormat.A4, PdfGenerator.PageFitMode.FIT_PAGE
        );
        assertEquals(PdfGenerator.PAGE_HEIGHT_A4, layout.pageWidth); // 842 pt
        assertEquals(PdfGenerator.PAGE_WIDTH_A4, layout.pageHeight);  // 595 pt
    }

    @Test
    public void testComputePageLayoutFillPage() {
        // When FILL_PAGE is selected, srcRect must be non-null and destRect covers the entire page
        PdfGenerator.PageLayout layout = PdfGenerator.computePageLayout(
                1000, 2000, 72f, PdfGenerator.PageFormat.A4, PdfGenerator.PageFitMode.FILL_PAGE
        );
        assertEquals(PdfGenerator.PAGE_WIDTH_A4, layout.pageWidth);
        assertEquals(PdfGenerator.PAGE_HEIGHT_A4, layout.pageHeight);
        assertNotNull(layout.srcRect);
    }

    @Test
    public void testCalculateFillSrcRectWiderSource() {
        // Source is 1000 x 500 (aspect 2.0), target is 500 x 500 (aspect 1.0)
        // Crop width = 500 * 1.0 = 500. left = (1000 - 500)/2 = 250
        int[] bounds = PdfGenerator.calculateFillCropBounds(1000, 500, 500, 500);
        assertEquals(250, bounds[0]); // left
        assertEquals(0, bounds[1]);   // top
        assertEquals(750, bounds[2]); // right
        assertEquals(500, bounds[3]); // bottom
    }

    @Test
    public void testCalculateFillSrcRectTallerSource() {
        // Source is 500 x 1000 (aspect 0.5), target is 500 x 500 (aspect 1.0)
        // Crop height = 500 / 1.0 = 500. top = (1000 - 500)/2 = 250
        int[] bounds = PdfGenerator.calculateFillCropBounds(500, 1000, 500, 500);
        assertEquals(0, bounds[0]);   // left
        assertEquals(250, bounds[1]); // top
        assertEquals(500, bounds[2]); // right
        assertEquals(750, bounds[3]); // bottom
    }

    @Test
    public void testMultiPageIndependentDimensions() {
        // Page 1: tall mobile screenshot 1080 x 2400 (aspect 9:20)
        PdfGenerator.PageLayout p1 = PdfGenerator.computePageLayout(1080, 2400, 72f, PdfGenerator.PageFormat.FIT_IMAGE, PdfGenerator.PageFitMode.FIT_PAGE);
        assertEquals(1080, p1.pageWidth);
        assertEquals(2400, p1.pageHeight);

        // Page 2: landscape photo 1920 x 1080 (aspect 16:9)
        PdfGenerator.PageLayout p2 = PdfGenerator.computePageLayout(1920, 1080, 72f, PdfGenerator.PageFormat.FIT_IMAGE, PdfGenerator.PageFitMode.FIT_PAGE);
        assertEquals(1920, p2.pageWidth);
        assertEquals(1080, p2.pageHeight);

        // Page 3: small square receipt 600 x 600 (aspect 1:1)
        PdfGenerator.PageLayout p3 = PdfGenerator.computePageLayout(600, 600, 72f, PdfGenerator.PageFormat.FIT_IMAGE, PdfGenerator.PageFitMode.FIT_PAGE);
        assertEquals(600, p3.pageWidth);
        assertEquals(600, p3.pageHeight);

        // Each page has its own independent dimensions matching its source image
        assertNotEquals(p1.pageWidth, p2.pageWidth);
        assertNotEquals(p1.pageHeight, p2.pageHeight);
        assertNotEquals(p2.pageWidth, p3.pageWidth);
    }
}
