package com.anscanner.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PdfToolsHelperTest {

    @Test
    public void testPageOrderInfoRotationNormalization() {
        PdfToolsHelper.PageOrderInfo info0 = new PdfToolsHelper.PageOrderInfo(0, 0);
        assertEquals(0, info0.originalPageIndex);
        assertEquals(0, info0.rotationDegrees);

        PdfToolsHelper.PageOrderInfo info90 = new PdfToolsHelper.PageOrderInfo(1, 90);
        assertEquals(1, info90.originalPageIndex);
        assertEquals(90, info90.rotationDegrees);

        PdfToolsHelper.PageOrderInfo info450 = new PdfToolsHelper.PageOrderInfo(2, 450);
        assertEquals(2, info450.originalPageIndex);
        assertEquals(90, info450.rotationDegrees);

        PdfToolsHelper.PageOrderInfo infoNegative = new PdfToolsHelper.PageOrderInfo(3, -90);
        assertEquals(3, infoNegative.originalPageIndex);
        assertEquals(270, infoNegative.rotationDegrees);
    }
}
