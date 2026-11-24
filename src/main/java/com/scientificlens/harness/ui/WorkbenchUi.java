package com.scientificlens.harness.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/** Small native primitives shared by the workflow and report surfaces. */
final class WorkbenchUi {
    private WorkbenchUi() {}

    static FontIcon icon(String code, String... styles) {
        FontIcon icon = new FontIcon(code);
        icon.getStyleClass().add("workbench-icon");
        icon.getStyleClass().addAll(styles);
        icon.setMouseTransparent(true);
        return icon;
    }

    static VBox panel(String... styles) {
        VBox box = new VBox(12);
        box.getStyleClass().add("workbench-panel");
        box.getStyleClass().addAll(styles);
        box.setMinWidth(0);
        box.setMaxWidth(Double.MAX_VALUE);
        return box;
    }

    static HBox heading(String icon, String title, Node... trailing) {
        Label label = ResultView.text(title, "workbench-heading");
        HBox row = new HBox(10, icon(icon, "icon-primary"), label);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(0);
        for (Node node : trailing) {
            row.getChildren().add(node);
        }
        return row;
    }

    static Label badge(String text, String... styles) {
        Label badge = new Label(text);
        badge.getStyleClass().add("workbench-badge");
        badge.getStyleClass().addAll(styles);
        badge.setMinWidth(Region.USE_PREF_SIZE);
        return badge;
    }

    static Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    static Region divider() {
        Region divider = new Region();
        divider.getStyleClass().add("workbench-divider");
        return divider;
    }

    static VBox reportSection(String number, String title, String helper, Node... content) {
        VBox section = panel("report-section");
        section.getChildren().add(reportHeading(number, title, helper));
        section.getChildren().addAll(content);
        return section;
    }

    static VBox reportHeading(String number, String title, String helper) {
        Label folio = ResultView.text(number, "report-folio");
        folio.setMinWidth(Region.USE_PREF_SIZE);
        Label heading = ResultView.text(title, "workbench-heading");
        HBox row = new HBox(12, folio, heading);
        row.setAlignment(Pos.BASELINE_LEFT);
        row.setMinWidth(0);
        HBox.setHgrow(heading, Priority.ALWAYS);
        VBox group = new VBox(8, row);
        group.setMinWidth(0);
        if (helper != null && !helper.isBlank()) {
            group.getChildren().add(ResultView.text(helper, "workbench-helper"));
        }
        return group;
    }

    static VBox reportField(String title, Node content, String... styles) {
        VBox group = new VBox(12, ResultView.text(title, "field-title"), content);
        group.getStyleClass().addAll(styles);
        group.setMinWidth(0);
        group.setMaxWidth(Double.MAX_VALUE);
        group.setMaxHeight(Region.USE_PREF_SIZE);
        return group;
    }

    static TitledPane disclosure(String title, Node content) {
        TitledPane pane = new TitledPane(title, content);
        pane.setExpanded(false);
        pane.setAnimated(false);
        pane.getStyleClass().add("workbench-extra");
        pane.setMinWidth(0);
        pane.setMaxWidth(Double.MAX_VALUE);
        return pane;
    }

    static GridPane facts(String... pairs) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("experiment-table");
        grid.setMinWidth(0);
        ColumnConstraints labels = new ColumnConstraints(128);
        ColumnConstraints values = new ColumnConstraints();
        values.setMinWidth(0);
        values.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().setAll(labels, values);
        for (int i = 0; i < pairs.length; i += 2) {
            Label label = ResultView.text(pairs[i], "experiment-label");
            Label value = ResultView.text(pairs[i + 1], "experiment-value");
            GridPane.setValignment(label, javafx.geometry.VPos.TOP);
            GridPane.setValignment(value, javafx.geometry.VPos.TOP);
            grid.add(label, 0, i / 2);
            grid.add(value, 1, i / 2);
        }
        return grid;
    }

    static GridPane readingColumns(Node... content) {
        return new ReadingColumns(content);
    }

    private static final class ReadingColumns extends GridPane {
        private final Node[] content;
        private int columns;

        private ReadingColumns(Node[] content) {
            this.content = content;
            setMinWidth(0);
            getStyleClass().add("reading-columns");
            widthProperty().addListener((obs, oldWidth, newWidth) -> reflow(newWidth.doubleValue()));
            reflow(0);
        }

        private void reflow(double width) {
            int count = width >= 1000 ? 2 : 1;
            if (columns == count) {
                return;
            }
            columns = count;
            getChildren().clear();
            getColumnConstraints().clear();
            for (int i = 0; i < count; i++) {
                ColumnConstraints column = new ColumnConstraints();
                column.setPercentWidth(100.0 / count);
                column.setMinWidth(0);
                column.setHgrow(Priority.ALWAYS);
                getColumnConstraints().add(column);
            }
            for (int i = 0; i < content.length; i++) {
                GridPane.setValignment(content[i], javafx.geometry.VPos.TOP);
                GridPane.setFillHeight(content[i], false);
                add(content[i], i % count, i / count);
            }
        }
    }
}

