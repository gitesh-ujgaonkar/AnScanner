package com.anscanner.app.model.draft;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EditHistoryManagerTest {

    @Test
    public void testInitialState() {
        EditHistoryManager manager = new EditHistoryManager();
        assertNotNull(manager.getCurrentState());
        assertFalse(manager.canUndo());
        assertFalse(manager.canRedo());
        assertEquals(1, manager.getUndoCount());
        assertEquals(0, manager.getRedoCount());
        assertFalse(manager.hasEdits());
    }

    @Test
    public void testPushAndUndoRedo() {
        EditHistoryManager manager = new EditHistoryManager();
        ScanEditState s0 = manager.getCurrentState();

        ScanEditState s1 = s0.withRotation(90);
        manager.pushState(s1);

        assertTrue(manager.canUndo());
        assertFalse(manager.canRedo());
        assertEquals(s1, manager.getCurrentState());
        assertTrue(manager.hasEdits());

        ScanEditState s2 = s1.withFilter("MAGIC");
        manager.pushState(s2);

        assertEquals(3, manager.getUndoCount());
        assertEquals(s2, manager.getCurrentState());

        // Undo once -> back to s1
        ScanEditState undone1 = manager.undo();
        assertEquals(s1, undone1);
        assertEquals(s1, manager.getCurrentState());
        assertTrue(manager.canUndo());
        assertTrue(manager.canRedo());

        // Undo again -> back to s0
        ScanEditState undone0 = manager.undo();
        assertEquals(s0, undone0);
        assertEquals(s0, manager.getCurrentState());
        assertFalse(manager.canUndo());
        assertTrue(manager.canRedo());

        // Redo -> forward to s1
        ScanEditState redone1 = manager.redo();
        assertEquals(s1, redone1);
        assertEquals(s1, manager.getCurrentState());
        assertTrue(manager.canUndo());
        assertTrue(manager.canRedo());

        // Redo again -> forward to s2
        ScanEditState redone2 = manager.redo();
        assertEquals(s2, redone2);
        assertEquals(s2, manager.getCurrentState());
        assertTrue(manager.canUndo());
        assertFalse(manager.canRedo());
    }

    @Test
    public void testPushClearsRedo() {
        EditHistoryManager manager = new EditHistoryManager();
        ScanEditState s0 = manager.getCurrentState();

        ScanEditState s1 = s0.withRotation(90);
        manager.pushState(s1);

        manager.undo();
        assertTrue(manager.canRedo());

        // Pushing a new state branches history and clears redo
        ScanEditState sBranch = s0.withRotation(180);
        manager.pushState(sBranch);

        assertFalse(manager.canRedo());
        assertEquals(sBranch, manager.getCurrentState());
    }

    @Test
    public void testDuplicateContiguousStatesIgnored() {
        EditHistoryManager manager = new EditHistoryManager();
        ScanEditState s0 = manager.getCurrentState();

        manager.pushState(s0);
        assertEquals(1, manager.getUndoCount());

        ScanEditState s1 = s0.withRotation(90);
        manager.pushState(s1);
        assertEquals(2, manager.getUndoCount());

        manager.pushState(s1); // duplicate push
        assertEquals(2, manager.getUndoCount());
    }

    @Test
    public void testJsonSerialization() throws Exception {
        EditHistoryManager manager = new EditHistoryManager();
        ScanEditState s0 = manager.getCurrentState();
        ScanEditState s1 = s0.withRotation(90);
        ScanEditState s2 = s1.withFilter("BW");

        manager.pushState(s1);
        manager.pushState(s2);
        manager.undo(); // now s2 is in redoStack, s1 is at top of undoStack

        JSONObject json = manager.toJson();
        assertNotNull(json);

        EditHistoryManager restored = EditHistoryManager.fromJson(json);
        assertTrue(restored.canUndo());
        assertTrue(restored.canRedo());
        assertEquals(s1, restored.getCurrentState());

        ScanEditState redone = restored.redo();
        assertEquals(s2, redone);
    }

    @Test
    public void testSequentialAnnotationAndCropUndoRedo() {
        EditHistoryManager manager = new EditHistoryManager();
        ScanEditState s0 = manager.getCurrentState();
        assertFalse(s0.hasEdits());

        // Step 1: User adds a text annotation
        com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem t1 =
                new com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem("Invoice", 100f, 200f, 0xFF000000, 24f);
        java.util.List<com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem> list1 = new java.util.ArrayList<>();
        list1.add(t1);
        com.anscanner.app.ui.custom.AnnotationDrawingView.AnnotationState stateAnnos1 =
                new com.anscanner.app.ui.custom.AnnotationDrawingView.AnnotationState(new java.util.ArrayList<>(), list1, new java.util.ArrayList<>());

        ScanEditState s1 = s0.withAnnotations(stateAnnos1);
        manager.pushState(s1);

        assertTrue(manager.canUndo());
        assertFalse(manager.canRedo());
        assertEquals(2, manager.getUndoCount());
        assertEquals(s1, manager.getCurrentState());
        assertNotNull(manager.getCurrentState().getAnnotations());
        assertEquals(1, manager.getCurrentState().getAnnotations().size());

        // Step 2: User applies Crop (with vector remapped annotations)
        float[] cropPoints = new float[]{0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f};
        com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem t1Remapped =
                new com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem("Invoice", 120f, 240f, 0xFF000000, 28f);
        java.util.List<com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem> list2 = new java.util.ArrayList<>();
        list2.add(t1Remapped);
        com.anscanner.app.ui.custom.AnnotationDrawingView.AnnotationState stateAnnosCrop =
                new com.anscanner.app.ui.custom.AnnotationDrawingView.AnnotationState(new java.util.ArrayList<>(), list2, new java.util.ArrayList<>());

        ScanEditState s2 = s1.withCrop(cropPoints, stateAnnosCrop);
        manager.pushState(s2);

        assertEquals(3, manager.getUndoCount());
        assertEquals(s2, manager.getCurrentState());
        assertNotNull(manager.getCurrentState().getNormalizedCropPoints());

        // Step 3: User adds a second text annotation post-crop
        com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem t2PostCrop =
                new com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem("Approved", 50f, 80f, 0xFF00FF00, 20f);
        java.util.List<com.anscanner.app.ui.custom.AnnotationDrawingView.TextItem> list3 = new java.util.ArrayList<>(list2);
        list3.add(t2PostCrop);
        com.anscanner.app.ui.custom.AnnotationDrawingView.AnnotationState stateAnnosPostCrop =
                new com.anscanner.app.ui.custom.AnnotationDrawingView.AnnotationState(new java.util.ArrayList<>(), list3, new java.util.ArrayList<>());

        ScanEditState s3 = s2.withAnnotations(stateAnnosPostCrop);
        manager.pushState(s3);

        assertEquals(4, manager.getUndoCount());
        assertEquals(2, manager.getCurrentState().getAnnotations().size());

        // ── SEQUENTIAL UNDO VERIFICATION ──
        // Undo 1: Reverts post-crop annotation -> returns s2 (crop intact, textItem2 removed)
        ScanEditState undoneToS2 = manager.undo();
        assertEquals(s2, undoneToS2);
        assertEquals(1, undoneToS2.getAnnotations().size());
        assertEquals(120f, undoneToS2.getAnnotations().textItems.get(0).x, 0.001f);
        assertNotNull(undoneToS2.getNormalizedCropPoints());
        assertTrue(manager.canUndo());
        assertTrue(manager.canRedo());

        // Undo 2: Reverts crop -> returns s1 (uncropped image, original uncropped annotation restored)
        ScanEditState undoneToS1 = manager.undo();
        assertEquals(s1, undoneToS1);
        assertNull(undoneToS1.getNormalizedCropPoints());
        assertEquals(1, undoneToS1.getAnnotations().size());
        assertEquals(100f, undoneToS1.getAnnotations().textItems.get(0).x, 0.001f);
        assertTrue(manager.canUndo());
        assertTrue(manager.canRedo());

        // Undo 3: Reverts pre-crop annotation -> returns s0 (original raw image, 0 annotations)
        ScanEditState undoneToS0 = manager.undo();
        assertEquals(s0, undoneToS0);
        assertNull(undoneToS0.getNormalizedCropPoints());
        assertNull(undoneToS0.getAnnotations());
        assertFalse(manager.canUndo());
        assertTrue(manager.canRedo());

        // ── SEQUENTIAL REDO VERIFICATION ──
        // Redo 1: Restores pre-crop annotation
        ScanEditState redoneToS1 = manager.redo();
        assertEquals(s1, redoneToS1);
        assertEquals(100f, redoneToS1.getAnnotations().textItems.get(0).x, 0.001f);

        // Redo 2: Restores crop and remapped annotations
        ScanEditState redoneToS2 = manager.redo();
        assertEquals(s2, redoneToS2);
        assertNotNull(redoneToS2.getNormalizedCropPoints());
        assertEquals(120f, redoneToS2.getAnnotations().textItems.get(0).x, 0.001f);

        // Redo 3: Restores post-crop annotation
        ScanEditState redoneToS3 = manager.redo();
        assertEquals(s3, redoneToS3);
        assertEquals(2, redoneToS3.getAnnotations().size());
        assertFalse(manager.canRedo());
    }
}
