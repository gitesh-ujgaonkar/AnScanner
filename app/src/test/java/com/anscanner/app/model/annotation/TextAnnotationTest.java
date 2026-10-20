package com.anscanner.app.model.annotation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TextAnnotationTest {

    @Test
    public void testTextAnnotationInitialization() {
        TextAnnotation annotation = new TextAnnotation("Document Title", 100f, 200f, 0xFFFF0000, 24f);

        assertEquals("Document Title", annotation.text);
        assertEquals(100f, annotation.x, 0.001f);
        assertEquals(200f, annotation.y, 0.001f);
        assertEquals(0xFFFF0000, annotation.textColor);
        assertEquals(0xFFFF0000, annotation.color);
        assertEquals(24f, annotation.textSize, 0.001f);
        assertEquals("sans", annotation.fontFamily);
        assertTrue(annotation.hasBackground);
        assertFalse(annotation.isSelected);
    }

    @Test
    public void testFontFamilyUpdate() {
        TextAnnotation annotation = new TextAnnotation("Test Text", 50f, 50f, 0xFF000000, 18f);
        assertEquals("sans", annotation.fontFamily);

        annotation.setFontFamily("serif");
        assertEquals("serif", annotation.fontFamily);

        annotation.setFontFamily("bold_condensed");
        assertEquals("bold_condensed", annotation.fontFamily);

        // Blank/null falls back to sans
        annotation.setFontFamily(null);
        assertEquals("sans", annotation.fontFamily);

        annotation.setFontFamily("   ");
        assertEquals("sans", annotation.fontFamily);
    }

    @Test
    public void testCloningAndEquality() {
        TextAnnotation original = new TextAnnotation("Contract Signature", 50f, 80f, 0xFF0000FF, 18f);
        original.setFontFamily("serif");
        original.isSelected = true;

        TextAnnotation clone = original.copy();

        assertEquals(original, clone);
        assertEquals(original.text, clone.text);
        assertEquals(original.fontFamily, clone.fontFamily);
        assertEquals(original.textColor, clone.textColor);
        assertEquals(original.isSelected, clone.isSelected);

        // Modifying clone does not mutate original
        clone.setFontFamily("bold_condensed");
        assertNotEquals(original.fontFamily, clone.fontFamily);
        assertNotEquals(original, clone);
    }

    @Test
    public void testColorAndSizeModifications() {
        TextAnnotation annotation = new TextAnnotation("Editable Text", 10f, 20f, 0xFF000000, 16f);

        annotation.setTextColor(0xFF00FF00);
        assertEquals(0xFF00FF00, annotation.textColor);
        assertEquals(0xFF00FF00, annotation.color);

        annotation.textSize = 32f;
        assertEquals(32f, annotation.textSize, 0.001f);

        annotation.hasBackground = false;
        assertFalse(annotation.hasBackground);
    }

    @Test
    public void testRotationAngle() {
        TextAnnotation annotation = new TextAnnotation("Rotating Text", 10f, 20f, 0xFF000000, 16f);
        assertEquals(0f, annotation.getRotationAngle(), 0.001f);

        annotation.setRotationAngle(45f);
        assertEquals(45f, annotation.getRotationAngle(), 0.001f);

        // Normalize > 360
        annotation.setRotationAngle(450f);
        assertEquals(90f, annotation.getRotationAngle(), 0.001f);

        // Normalize negative angles
        annotation.setRotationAngle(-90f);
        assertEquals(270f, annotation.getRotationAngle(), 0.001f);

        // Copy preserves rotation angle
        TextAnnotation copy = annotation.copy();
        assertEquals(270f, copy.getRotationAngle(), 0.001f);
        assertEquals(annotation, copy);

        copy.setRotationAngle(180f);
        assertNotEquals(annotation, copy);
    }
}
