package com.scientificlens.harness.ui;

import com.scientificlens.harness.core.AnalysisLibrary;
import com.scientificlens.harness.domain.StoredAnalysis;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

final class AnalysisLibraryView extends Dialog<Void> {
    private final AnalysisLibrary library;
    private final ExecutorService executor;
    private final Consumer<StoredAnalysis> reopen;
    private final BooleanProperty working = new SimpleBooleanProperty();
    private final BooleanProperty selected = new SimpleBooleanProperty();
    private final BooleanProperty dirty = new SimpleBooleanProperty();
    private final BooleanProperty blocked;
    private final TextField search = new TextField();
    private final CheckBox archived = new CheckBox("숨긴 보고서 보기");
    private final ListView<StoredAnalysis> list = new ListView<>();
    private final Label status = ResultView.text("불러오는 중…", "caption");
    private final Label count = ResultView.text("", "caption");
    private final TextArea title = new TextArea();
    private final TextArea notes = new TextArea();
    private final VBox detail = new VBox(16);
    private final VBox warning = new VBox(8);
    private final VBox compactBody = new VBox(16);
    private final ScrollPane compactScroll = new ScrollPane(compactBody);
    private final Button open = action("보고서 열기", "primary-button", "library-open");
    private final Button save = action("제목·메모 저장", "secondary-button", "library-save");
    private final Button move = action("숨기기", "secondary-button", "library-archive");
    private final Button revert = action("변경 취소", "link-button", "library-revert");
    private final Label unsaved = ResultView.text("저장하지 않은 변경사항 · 다른 보고서를 선택하기 전에 저장하세요.", "status-warn");
    private final Label busyNotice = ResultView.text("진행 중인 작업이 끝나면 저장된 보고서를 열 수 있습니다.", "caption");
    private List<StoredAnalysis> entries = List.of();
    private StoredAnalysis current;
    private boolean populating;
    private int columns;

    AnalysisLibraryView(Stage owner, AnalysisLibrary library, ExecutorService executor,
            BooleanProperty analysisBusy, Consumer<StoredAnalysis> reopen) {
        this.library = library;
        this.executor = executor;
        this.reopen = reopen;
        blocked = analysisBusy;
        busyNotice.visibleProperty().bind(blocked);
        busyNotice.managedProperty().bind(blocked);
        initOwner(owner);
        setTitle("연구 보관함");
        setResizable(true);
        getDialogPane().setId("analysis-library");
        getDialogPane().getStylesheets().add(MainApp.class.getResource("app.css").toExternalForm());
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setStyle("-fx-font-family: 'Noto Sans KR';");
        search.setId("library-search");
        search.setPromptText("질문, 제목, 가설, 메모 검색");
        search.setAccessibleText("저장된 연구 검색");
        archived.setId("library-show-archived");
        list.setId("library-list");
        list.setAccessibleText("저장된 연구 보고서 목록");
        list.setMinWidth(0);
        list.setMinHeight(180);
        list.setPrefHeight(250);
        list.getStyleClass().add("library-list");
        list.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(StoredAnalysis entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(null);
                setGraphic(null);
                setTooltip(null);
                setAccessibleText("");
                if (!empty && entry != null) {
                    Label heading = new Label(entry.title());
                    heading.setMaxWidth(Double.MAX_VALUE);
                    heading.getStyleClass().add("library-entry-title");
                    VBox row = new VBox(8, heading,
                            ResultView.text(timeLabel(entry.savedAt()) + " · " + entry.result().problem().mode().label(), "caption"),
                            ResultView.text(entry.result().runtime(), "caption"),
                            ResultView.text(entry.origin().equals("imported") ? "JSON에서 가져온 연구" : "분석 완료 후 저장", "caption"));
                    row.setMinWidth(0);
                    row.prefWidthProperty().bind(widthProperty().subtract(32));
                    setGraphic(row);
                    setTooltip(new Tooltip(entry.title()));
                    setAccessibleText(entry.title() + " · " + timeLabel(entry.savedAt()) + " · "
                            + entry.result().problem().mode().label() + " · " + entry.result().runtime());
                }
            }
        });
        title.setId("library-title");
        title.setAccessibleText("저장된 보고서 제목");
        title.setWrapText(true);
        title.setPrefRowCount(2);
        notes.setId("library-notes");
        notes.setAccessibleText("로컬 연구 메모");
        notes.setPromptText("결과를 읽고 남길 메모 · 모델에 전달되지 않습니다.");
        notes.setWrapText(true);
        notes.setPrefRowCount(5);
        notes.setMinHeight(140);
        title.disableProperty().bind(working);
        notes.disableProperty().bind(working);
        title.editableProperty().bind(archived.selectedProperty().not());
        notes.editableProperty().bind(archived.selectedProperty().not());
        unsaved.visibleProperty().bind(dirty);
        unsaved.managedProperty().bind(dirty);
        save.disableProperty().bind(working.or(selected.not()).or(dirty.not()).or(archived.selectedProperty()));
        open.disableProperty().bind(working.or(selected.not()).or(blocked).or(dirty));
        move.disableProperty().bind(working.or(selected.not()).or(dirty));
        revert.disableProperty().bind(dirty.not().or(working));
        revert.setOnAction(e -> display(current));
        list.disableProperty().bind(working.or(dirty));
        search.disableProperty().bind(working.or(dirty));
        archived.disableProperty().bind(working.or(dirty));
        title.textProperty().addListener((o, a, b) -> updateDirty());
        notes.textProperty().addListener((o, a, b) -> updateDirty());
        search.textProperty().addListener((o, a, b) -> filter(null));
        archived.selectedProperty().addListener((o, a, b) -> {
            move.setText(b ? "복원" : "숨기기");
            if (!populating) refresh(null);
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> display(b));
        save.setOnAction(e -> saveMetadata());
        move.setOnAction(e -> move());
        open.setOnAction(e -> openSelected());
        Button refresh = action("새로고침", "secondary-button", "library-refresh");
        refresh.disableProperty().bind(working.or(dirty));
        refresh.setOnAction(e -> refresh(current == null ? null : current.id()));
        Button importButton = action("JSON 가져오기", "secondary-button", "library-import");
        importButton.disableProperty().bind(working.or(dirty));
        importButton.setOnAction(e -> chooseImport());
        FlowPane tools = new FlowPane(12, 8, archived, refresh, importButton);
        VBox heading = new VBox(12, WorkbenchUi.heading("mdi2b-bookshelf", "연구 보관함"),
                ResultView.text("완료된 분석은 자동으로 저장됩니다. 보고서를 다시 열고 다음 실험을 준비하세요.", "caption"),
                search, tools, warning);
        VBox libraryColumn = new VBox(12, count, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        ScrollPane detailScroll = new ScrollPane(detail);
        detailScroll.setId("library-detail-scroll");
        detailScroll.setFitToWidth(true);
        detailScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailScroll.getStyleClass().add("page-scroll");
        detail.getStyleClass().add("library-detail");
        detail.setMinWidth(0);
        compactBody.setMinWidth(0);
        compactScroll.setId("library-stack-scroll");
        compactScroll.setFitToWidth(true);
        compactScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        compactScroll.getStyleClass().add("page-scroll");
        GridPane body = new GridPane();
        body.setMinWidth(0);
        body.setHgap(20);
        body.setVgap(16);
        body.widthProperty().addListener((o, a, b) -> reflow(body, libraryColumn, detailScroll, b.doubleValue()));
        reflow(body, libraryColumn, detailScroll, 0);
        BorderPane layout = new BorderPane(body);
        layout.setPadding(new Insets(20));
        layout.setTop(heading);
        FlowPane actions = new FlowPane(12, 8, open, save, move, revert);
        VBox footer = new VBox(8, unsaved, actions, status);
        layout.setBottom(footer);
        BorderPane.setMargin(heading, new Insets(0, 0, 16, 0));
        BorderPane.setMargin(footer, new Insets(12, 0, 0, 0));
        getDialogPane().setContent(layout);
        var bounds = Screen.getPrimary().getVisualBounds();
        getDialogPane().setPrefSize(Math.min(1120, bounds.getWidth() - 80), Math.min(800, bounds.getHeight() - 80));
        display(null);
        setOnShowing(e -> refresh(current == null ? null : current.id()));
        setOnCloseRequest(e -> {
            if (dirty.get()) {
                e.consume();
                status.setText("변경사항을 저장하거나 원래 내용으로 되돌린 뒤 닫으세요.");
            }
        });
    }

    void refresh(String id) {
        boolean hidden = archived.isSelected();
        run(() -> library.list(hidden), listing -> {
            entries = listing.entries();
            warning.getChildren().clear();
            if (!listing.unreadableFiles().isEmpty()) {
                warning.getChildren().add(WorkbenchUi.disclosure("읽을 수 없는 보고서 " + listing.unreadableFiles().size()
                        + "개 · 원본 파일은 유지됩니다", ResultView.text(String.join("\n", listing.unreadableFiles()), "status-warn")));
            }
            filter(id);
            status.setText("이 기기에 저장됩니다. 메모는 분석 모델이나 기본 보고서 내보내기에 전달되지 않습니다.");
        });
    }

    void importReport(Path input) {
        run(() -> library.importReport(input), record -> {
            populating = true;
            archived.setSelected(false);
            populating = false;
            search.clear();
            refresh(record.id());
        });
    }

    private void filter(String preferredId) {
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        String keep = preferredId != null ? preferredId : current == null ? null : current.id();
        var matches = entries.stream().filter(entry -> (entry.title() + "\n" + entry.notes() + "\n"
                + entry.result().problem().question() + "\n" + entry.result().finalDecision().coreHypothesis()
                + "\n" + entry.result().runtime() + " " + entry.result().problem().mode().value())
                .toLowerCase(Locale.ROOT).contains(query)).toList();
        list.getItems().setAll(matches);
        list.setPlaceholder(ResultView.text(query.isEmpty() ? "저장된 보고서가 없습니다" : "검색 결과가 없습니다", "caption"));
        count.setText((archived.isSelected() ? "숨긴 보고서 " : "연구 보고서 ") + matches.size() + " / " + entries.size());
        matches.stream().filter(entry -> entry.id().equals(keep)).findFirst().ifPresentOrElse(
                entry -> list.getSelectionModel().select(entry), () -> list.getSelectionModel().clearSelection());
    }

    private void display(StoredAnalysis record) {
        current = record;
        selected.set(record != null);
        populating = true;
        detail.getChildren().clear();
        title.setText(record == null ? "" : record.title());
        notes.setText(record == null ? "" : record.notes());
        dirty.set(false);
        populating = false;
        if (record == null) {
            detail.getChildren().addAll(ResultView.text("연구를 이어가세요", "section-heading"),
                    ResultView.text("왼쪽 목록에서 보고서를 선택하세요. 제목과 메모를 정리하거나 전체 결과를 다시 열 수 있습니다.", "field-value"));
            return;
        }
        detail.getChildren().addAll(ResultView.text("저장된 연구", "report-folio"),
                WorkbenchUi.reportField("제목", title),
                WorkbenchUi.facts("저장 시각", timeLabel(record.savedAt()), "마지막 편집", timeLabel(record.updatedAt()),
                        "분석 모드", record.result().problem().mode().label(), "분석 엔진", record.result().runtime()),
                WorkbenchUi.reportField("원래 질문", ResultView.text(record.result().problem().question(), "field-value")),
                WorkbenchUi.reportField("핵심 가설", ResultView.text(record.result().finalDecision().coreHypothesis(), "field-value")),
                WorkbenchUi.reportField("나의 연구 메모 · 이 기기에만 저장", notes), busyNotice);
        if (archived.isSelected()) detail.getChildren().add(ResultView.text("숨긴 보고서입니다. 복원하면 제목과 메모를 편집할 수 있습니다.", "caption"));
    }

    private void updateDirty() {
        if (!populating && current != null) dirty.set(!title.getText().equals(current.title()) || !notes.getText().equals(current.notes()));
    }

    private void saveMetadata() {
        if (current == null) return;
        String id = current.id();
        String nextTitle = title.getText();
        String nextNotes = notes.getText();
        run(() -> library.edit(id, nextTitle, nextNotes), saved -> {
            display(saved);
            refresh(id);
        });
    }

    private void move() {
        if (current == null) return;
        String id = current.id();
        boolean restore = archived.isSelected();
        run(() -> { if (restore) library.restore(id); else library.archive(id); return id; }, ignored -> refresh(null));
    }

    private void openSelected() {
        if (current == null || blocked.get()) return;
        String id = current.id();
        boolean hidden = archived.isSelected();
        run(() -> library.read(id, hidden), record -> { reopen.accept(record); close(); });
    }

    private void chooseImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("연구 보고서 JSON 가져오기");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("분석 JSON (*.json)", "*.json"));
        var input = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (input != null) importReport(input.toPath());
    }

    private <T> void run(Callable<T> operation, Consumer<T> success) {
        if (working.get()) return;
        working.set(true);
        status.setText("보관함 처리 중…");
        Task<T> task = new Task<>() { @Override protected T call() throws Exception { return operation.call(); } };
        task.setOnSucceeded(e -> { working.set(false); success.accept(task.getValue()); });
        task.setOnFailed(e -> {
            working.set(false);
            status.setText("처리하지 못했습니다 · " + task.getException().getMessage());
        });
        executor.submit(task);
    }

    private void reflow(GridPane body, VBox libraryColumn, ScrollPane detailScroll, double width) {
        int count = width >= 900 ? 2 : 1;
        if (columns == count) return;
        columns = count;
        body.getChildren().clear();
        compactBody.getChildren().clear();
        body.getColumnConstraints().clear();
        if (count == 2) {
            detailScroll.setContent(detail);
            ColumnConstraints left = new ColumnConstraints(); left.setPercentWidth(36); left.setMinWidth(0);
            ColumnConstraints right = new ColumnConstraints(); right.setPercentWidth(64); right.setMinWidth(0);
            body.getColumnConstraints().setAll(left, right);
            body.add(libraryColumn, 0, 0); body.add(detailScroll, 1, 0);
            GridPane.setVgrow(libraryColumn, Priority.ALWAYS);
            list.setMaxHeight(Double.MAX_VALUE);
        } else {
            detailScroll.setContent(null);
            ColumnConstraints one = new ColumnConstraints(); one.setPercentWidth(100); one.setMinWidth(0);
            body.getColumnConstraints().add(one);
            compactBody.getChildren().setAll(libraryColumn, detail);
            body.add(compactScroll, 0, 0);
            GridPane.setVgrow(compactScroll, Priority.ALWAYS);
            GridPane.setVgrow(libraryColumn, Priority.NEVER);
            list.setMinHeight(150);
            list.setMaxHeight(210);
        }
        GridPane.setVgrow(detailScroll, Priority.ALWAYS);
    }

    static String timeLabel(String value) {
        return DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value));
    }

    private static Button action(String label, String style, String id) {
        Button button = new Button(label); button.getStyleClass().add(style); button.setId(id); return button;
    }
}
