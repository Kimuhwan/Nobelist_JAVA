package com.scientificlens.harness.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/** Vertical checklist of pipeline stages: done / active / pending / stopped. */
final class StageTracker extends VBox {
    private final List<String> names = new ArrayList<>();
    private final List<Label> rows = new ArrayList<>();
    private int active = -1;

    StageTracker() {
        super(2);
        getStyleClass().add("stage-list");
        setAccessibleText("분석 단계 진행 목록");
    }

    void reset(List<String> stageNames) {
        names.clear();
        names.addAll(stageNames);
        rows.clear();
        getChildren().clear();
        for (String name : stageNames) {
            Label row = new Label();
            row.getStyleClass().add("stage");
            rows.add(row);
            getChildren().add(row);
        }
        update(0);
    }

    void clear() {
        names.clear();
        rows.clear();
        getChildren().clear();
        active = -1;
    }

    /** @param done number of completed stages; the next one is shown as active */
    void update(int done) {
        active = done < rows.size() ? done : -1;
        for (int i = 0; i < rows.size(); i++) {
            if (i < done) {
                set(i, "✓  ", "stage-done");
            } else if (i == done) {
                set(i, "●  ", "stage-active");
            } else {
                set(i, "○  ", "stage-pending");
            }
        }
    }

    void markStopped() {
        if (active >= 0 && active < rows.size()) {
            set(active, "✕  ", "stage-stopped");
        }
    }

    private void set(int index, String mark, String style) {
        Label row = rows.get(index);
        row.setText(mark + names.get(index));
        row.getStyleClass().removeAll("stage-done", "stage-active", "stage-pending", "stage-stopped");
        row.getStyleClass().add(style);
    }
}
