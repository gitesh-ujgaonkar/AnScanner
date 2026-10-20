package com.anscanner.app.model.draft;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.util.Stack;

/**
 * Undo and Redo history manager for document scan adjustments.
 * Maintains non-destructive ScanEditState stacks without duplicating raw bitmaps in memory.
 */
public class EditHistoryManager implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Stack<ScanEditState> undoStack = new Stack<>();
    private final Stack<ScanEditState> redoStack = new Stack<>();

    public EditHistoryManager() {
        this(ScanEditState.createDefault());
    }

    public EditHistoryManager(@Nullable ScanEditState initialState) {
        if (initialState != null) {
            undoStack.push(initialState);
        }
    }

    /**
     * Pushes a new edit state and clears redoStack.
     * Duplicate contiguous identical states are ignored.
     */
    public synchronized void pushState(@Nullable ScanEditState state) {
        if (state == null) return;
        if (!undoStack.isEmpty() && undoStack.peek().equals(state)) {
            return;
        }
        undoStack.push(state);
        redoStack.clear();
    }

    /**
     * Pops the current state into redoStack and returns the previous state to re-render.
     * Returns null or current state if undo is not available.
     */
    @Nullable
    public synchronized ScanEditState undo() {
        if (!canUndo()) {
            return getCurrentState();
        }
        ScanEditState current = undoStack.pop();
        redoStack.push(current);
        return undoStack.peek();
    }

    /**
     * Pops from redoStack, pushes to undoStack, and returns the state to re-render.
     */
    @Nullable
    public synchronized ScanEditState redo() {
        if (!canRedo()) {
            return getCurrentState();
        }
        ScanEditState next = redoStack.pop();
        undoStack.push(next);
        return next;
    }

    /**
     * Undo is available if there is at least one previous state to return to.
     */
    public synchronized boolean canUndo() {
        return undoStack.size() > 1;
    }

    /**
     * Redo is available if redoStack contains at least one state.
     */
    public synchronized boolean canRedo() {
        return !redoStack.isEmpty();
    }

    @Nullable
    public synchronized ScanEditState getCurrentState() {
        return undoStack.isEmpty() ? null : undoStack.peek();
    }

    public synchronized boolean hasEdits() {
        if (canUndo()) {
            return true;
        }
        ScanEditState current = getCurrentState();
        return current != null && current.hasEdits();
    }

    public synchronized void reset(@Nullable ScanEditState initialState) {
        undoStack.clear();
        redoStack.clear();
        if (initialState != null) {
            undoStack.push(initialState);
        }
    }

    public synchronized int getUndoCount() {
        return undoStack.size();
    }

    public synchronized int getRedoCount() {
        return redoStack.size();
    }

    public synchronized JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        JSONArray undoArr = new JSONArray();
        for (ScanEditState state : undoStack) {
            undoArr.put(state.toJson());
        }
        json.put("undoStack", undoArr);

        JSONArray redoArr = new JSONArray();
        for (ScanEditState state : redoStack) {
            redoArr.put(state.toJson());
        }
        json.put("redoStack", redoArr);
        return json;
    }

    @NonNull
    public static EditHistoryManager fromJson(@Nullable JSONObject json) throws JSONException {
        EditHistoryManager manager = new EditHistoryManager(null);
        if (json == null) {
            manager.pushState(ScanEditState.createDefault());
            return manager;
        }

        if (json.has("undoStack")) {
            JSONArray undoArr = json.getJSONArray("undoStack");
            for (int i = 0; i < undoArr.length(); i++) {
                manager.undoStack.push(ScanEditState.fromJson(undoArr.getJSONObject(i)));
            }
        }
        if (json.has("redoStack")) {
            JSONArray redoArr = json.getJSONArray("redoStack");
            for (int i = 0; i < redoArr.length(); i++) {
                manager.redoStack.push(ScanEditState.fromJson(redoArr.getJSONObject(i)));
            }
        }

        if (manager.undoStack.isEmpty()) {
            manager.undoStack.push(ScanEditState.createDefault());
        }
        return manager;
    }
}
