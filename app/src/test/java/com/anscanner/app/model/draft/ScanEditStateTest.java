package com.anscanner.app.model.draft;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ScanEditStateTest {

    @Test
    public void testDefaultState() {
        ScanEditState state = ScanEditState.createDefault();
        assertNull(state.getNormalizedCropPoints());
        assertEquals(0, state.getRotationDegrees());
        assertEquals("ORIGINAL", state.getFilterMode());
        assertFalse(state.hasEdits());
    }

    @Test
    public void testStateWithEdits() {
        float[] crop = new float[]{0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f};
        ScanEditState state = new ScanEditState(crop, 90, "MAGIC", System.currentTimeMillis());

        assertTrue(state.hasEdits());
        assertArrayEquals(crop, state.getNormalizedCropPoints(), 0.0001f);
        assertEquals(90, state.getRotationDegrees());
        assertEquals("MAGIC", state.getFilterMode());
    }

    @Test
    public void testNormalizedRotationDegrees() {
        ScanEditState s1 = new ScanEditState(null, 450, "ORIGINAL", 0);
        assertEquals(90, s1.getRotationDegrees());

        ScanEditState s2 = new ScanEditState(null, -90, "ORIGINAL", 0);
        assertEquals(270, s2.getRotationDegrees());
    }

    @Test
    public void testImmutability() {
        float[] crop = new float[]{0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f};
        ScanEditState state = new ScanEditState(crop, 0, "ORIGINAL", 0);

        // Modifying local array should not affect state
        crop[0] = 0.5f;
        assertEquals(0.1f, state.getNormalizedCropPoints()[0], 0.0001f);

        // Modifying getter output should not affect state
        float[] returned = state.getNormalizedCropPoints();
        returned[0] = 0.99f;
        assertEquals(0.1f, state.getNormalizedCropPoints()[0], 0.0001f);
    }

    @Test
    public void testTransitions() {
        ScanEditState initial = ScanEditState.createDefault();
        float[] crop = new float[]{0.05f, 0.05f, 0.95f, 0.05f, 0.95f, 0.95f, 0.05f, 0.95f};

        ScanEditState cropped = initial.withCrop(crop);
        assertTrue(cropped.hasEdits());
        assertEquals(0, cropped.getRotationDegrees());
        assertArrayEquals(crop, cropped.getNormalizedCropPoints(), 0.0001f);

        ScanEditState rotated = cropped.withRotation(90);
        assertEquals(90, rotated.getRotationDegrees());
        assertArrayEquals(crop, rotated.getNormalizedCropPoints(), 0.0001f);

        ScanEditState filtered = rotated.withFilter("BW");
        assertEquals("BW", filtered.getFilterMode());
        assertEquals(90, filtered.getRotationDegrees());
        assertArrayEquals(crop, filtered.getNormalizedCropPoints(), 0.0001f);
    }

    @Test
    public void testJsonSerialization() throws Exception {
        float[] crop = new float[]{0.12f, 0.34f, 0.56f, 0.78f, 0.90f, 0.11f, 0.22f, 0.33f};
        ScanEditState state = new ScanEditState(crop, 180, "GRAYSCALE", 123456789L);

        JSONObject json = state.toJson();
        assertNotNull(json);

        ScanEditState restored = ScanEditState.fromJson(json);
        assertEquals(state, restored);
        assertEquals(180, restored.getRotationDegrees());
        assertEquals("GRAYSCALE", restored.getFilterMode());
        assertEquals(123456789L, restored.getTimestamp());
        assertArrayEquals(crop, restored.getNormalizedCropPoints(), 0.0001f);
    }

    @Test
    public void testEqualsAndHashCode() {
        float[] crop1 = new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f};
        float[] crop2 = new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f};

        ScanEditState s1 = new ScanEditState(crop1, 90, "BW", 100L);
        ScanEditState s2 = new ScanEditState(crop2, 90, "BW", 200L); // different timestamp still equals in edit contents

        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());

        ScanEditState s3 = s1.withRotation(180);
        assertNotEquals(s1, s3);
    }

    @Test
    public void testAnnotationRotationJsonSerialization() throws Exception {
        com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem text =
                new com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem("Approved", 50f, 100f, 0xFF00FF00, 20f);
        text.setRotationAngle(45f);

        java.util.List<com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem> texts = new java.util.ArrayList<>();
        texts.add(text);

        com.anscanner.app.ui.custom.AnnotationDrawingView.SignatureItem sig =
                new com.anscanner.app.ui.custom.AnnotationDrawingView.SignatureItem(null, 10f, 20f, 150f, 80f);
        sig.setRotationAngle(90f);

        java.util.List<com.anscanner.app.ui.custom.AnnotationDrawingView.SignatureItem> sigs = new java.util.ArrayList<>();
        sigs.add(sig);

        com.anscanner.app.ui.custom.AnnotationDrawingView.AnnotationState stateAnnos =
                new com.anscanner.app.ui.custom.AnnotationDrawingView.AnnotationState(new java.util.ArrayList<>(), texts, sigs);

        ScanEditState state = new ScanEditState(null, 0, "ORIGINAL", 12345L, stateAnnos);

        JSONObject json = state.toJson();
        assertNotNull(json);
        assertTrue(json.has("annotations"));

        ScanEditState restored = ScanEditState.fromJson(json);
        assertNotNull(restored.getAnnotations());
        assertEquals(1, restored.getAnnotations().textItems.size());
        assertEquals(45f, restored.getAnnotations().textItems.get(0).getRotationAngle(), 0.001f);
        assertEquals("Approved", restored.getAnnotations().textItems.get(0).text);

        assertEquals(1, restored.getAnnotations().signatureItems.size());
        assertEquals(90f, restored.getAnnotations().signatureItems.get(0).getRotationAngle(), 0.001f);
    }
}
