package com.scientificlens.harness.ui;

import java.util.List;
import java.util.Objects;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextBoundsType;
import javafx.util.Duration;

/** The web edition's illustrated council entrance, with native controls over the original artwork. */
final class CouncilOpeningView extends Region {
    private enum Language {
        KO("ko", "한국어", "노벨리스트", "15개의 방법론, 하나의 의회", "언어 메뉴 밖 화면을 눌러 입장"),
        EN("en", "English", "Nobellist", "Fifteen lenses. One council.", "Click outside the language menu to enter"),
        JA("ja", "日本語", "ノーベリスト", "15の視点、ひとつの評議会", "言語メニュー以外をクリックして入る"),
        ZH("zh", "中文", "诺贝利斯特", "十五种视角，一个议会", "点击语言菜单外的区域进入");

        final String code, name, title, subtitle, prompt;
        Language(String code, String name, String title, String subtitle, String prompt) {
            this.code = code;
            this.name = name;
            this.title = title;
            this.subtitle = subtitle;
            this.prompt = prompt;
        }
    }

    private static final double INSET = 24;
    private static final PseudoClass CHINESE = PseudoClass.getPseudoClass("chinese");
    private final ImageView artwork = new ImageView(new Image(Objects.requireNonNull(
            CouncilOpeningView.class.getResource("opening/nobellist-council-welcome.png")).toExternalForm()));
    private final ImageView tint = new ImageView();
    private final TranslateTransition parallax = new TranslateTransition(Duration.millis(240), artwork);
    private final DoubleProperty zoom = new SimpleDoubleProperty(OpeningMotion.ART_SCALE);
    private final Label kicker = new Label("15 LENSES · ONE COUNCIL");
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final VBox titles = new VBox(14, kicker, title, subtitle);
    private final HBox languages = new HBox(4);
    private final HBox windowButtons;
    private final Pane dragStrip = new Pane();
    private final Region keyboardOutline = new Region();
    private final Button enter = new Button();
    private final ToggleButton motion = new ToggleButton("모션 줄이기");
    private final Circle focusRing = new Circle(110);
    private final BooleanProperty reducedMotion;
    private final Runnable revealWorkspace;
    private final Runnable onEntered;
    private Timeline arrival;
    private Timeline departure;
    private KeyCode entryKey;
    private boolean entering;
    private boolean closed;

    CouncilOpeningView(BooleanProperty reducedMotion, HBox windowButtons,
            Runnable revealWorkspace, Runnable onEntered) {
        this.reducedMotion = reducedMotion;
        this.windowButtons = windowButtons;
        this.revealWorkspace = revealWorkspace;
        this.onEntered = onEntered;
        setId("council-opening");
        getStyleClass().add("council-opening");
        getStylesheets().add(Objects.requireNonNull(getClass().getResource("opening.css")).toExternalForm());
        setMinSize(0, 0);
        setFocusTraversable(true);
        keyboardOutline.getStyleClass().add("opening-keyboard-outline");
        keyboardOutline.setMouseTransparent(true);
        keyboardOutline.visibleProperty().bind(focusVisibleProperty());

        artwork.setId("opening-art");
        artwork.setMouseTransparent(true);
        artwork.setScaleX(OpeningMotion.ART_SCALE);
        artwork.setScaleY(OpeningMotion.ART_SCALE);
        tint.setMouseTransparent(true);
        tint.setOpacity(0);
        tint.setScaleX(OpeningMotion.ART_SCALE);
        tint.setScaleY(OpeningMotion.ART_SCALE);
        zoom.addListener(observable -> updateArtwork());
        parallax.setInterpolator(OpeningMotion.POINTER_EASE);
        titles.setAlignment(Pos.CENTER);
        titles.setMouseTransparent(true);
        kicker.getStyleClass().add("opening-kicker");
        title.setId("opening-title");
        title.getStyleClass().add("opening-title");
        if (loadFace("NobellistOpening-Black.otf")) title.getStyleClass().add("opening-display");
        if (loadFace("NobellistOpeningSC-Bold.otf")) getStyleClass().add("opening-has-sc");
        // Align the visible glyphs to the same line across Hangul, Latin, kana and hanzi.
        Text lettering = new Text();
        lettering.setBoundsType(TextBoundsType.VISUAL);
        lettering.setTranslateY(2);
        lettering.textProperty().bind(title.textProperty());
        lettering.fontProperty().bind(title.fontProperty());
        lettering.fillProperty().bind(title.textFillProperty());
        title.setGraphic(lettering);
        title.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        subtitle.getStyleClass().add("opening-subtitle");
        focusRing.getStyleClass().add("opening-focus-ring");
        focusRing.setOpacity(0);
        focusRing.setMouseTransparent(true);
        languages.getStyleClass().add("opening-control-tray");
        languages.setAccessibleText("오프닝 제목 언어");
        ToggleGroup group = new ToggleGroup();
        for (Language language : Language.values()) {
            ToggleButton button = new ToggleButton(language.name);
            button.setId("opening-language-" + language.code);
            button.getStyleClass().add("opening-language");
            button.setToggleGroup(group);
            button.setOnAction(event -> {
                button.setSelected(true);
                useLanguage(language);
            });
            button.setSelected(language == Language.KO);
            languages.getChildren().add(button);
        }
        windowButtons.getStyleClass().addAll("opening-control-tray", "opening-window-buttons");
        motion.setId("opening-motion");
        motion.getStyleClass().add("opening-motion");
        motion.selectedProperty().bindBidirectional(reducedMotion);
        motion.selectedProperty().addListener((obs, previous, reduced) -> {
            if (reduced) {
                settleTitle();
                centerArt();
                if (entering) finishEntry();
            }
        });
        Circle ring = new Circle(9);
        ring.getStyleClass().add("opening-prompt-ring");
        enter.setId("opening-enter");
        enter.getStyleClass().add("opening-enter");
        enter.setGraphic(ring);
        enter.setOnAction(event -> enter());
        useLanguage(Language.KO);
        getChildren().addAll(artwork, tint, focusRing, titles, dragStrip, languages, windowButtons, motion, enter, keyboardOutline);
        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.isStillSincePress()
                    && event.getTarget() != languages && event.getTarget() != windowButtons
                    && controlAt(event.getTarget()) == null) enter();
        });
        setOnMouseMoved(event -> {
            if (reducedMotion.get() || entering || getWidth() <= 0 || getHeight() <= 0) return;
            var point = sceneToLocal(event.getSceneX(), event.getSceneY());
            shiftArt((.5 - point.getX() / getWidth()) * 10, (.5 - point.getY() / getHeight()) * 8);
        });
        setOnMouseExited(event -> { if (!reducedMotion.get() && !entering) shiftArt(0, 0); });
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
        addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (entryKey != null || entering) event.consume();
        });
        addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (entryKey != null && event.getCode() == entryKey) {
                event.consume();
                entryKey = null;
                enter();
            }
        });
    }

    Pane dragStrip() { return dragStrip; }

    private static boolean loadFace(String filename) {
        return Font.loadFont(Objects.requireNonNull(CouncilOpeningView.class
                .getResource("/fonts/" + filename)).toExternalForm(), 16) != null;
    }

    void present() {
        requestFocus();
        if (!reducedMotion.get()) {
            prepareTint();
            arrival = OpeningMotion.titleArrival(List.of(kicker, title, subtitle));
            arrival.playFromStart();
        }
    }

    private void useLanguage(Language language) {
        pseudoClassStateChanged(CHINESE, language == Language.ZH);
        title.setText(language.title);
        subtitle.setText(language.subtitle);
        enter.setText(language.prompt);
        setAccessibleText(language.title + " · " + language.subtitle);
        setAccessibleHelp(language.prompt + " · Enter / Space");
    }

    private void handleKey(KeyEvent event) {
        if (entering) { event.consume(); return; }
        if (event.isAltDown() || event.isMetaDown() || event.isControlDown()) return;
        if (event.getCode() != KeyCode.ENTER && event.getCode() != KeyCode.SPACE) return;
        ButtonBase target = controlAt(event.getTarget());
        if (target != null && target != enter) {
            if (event.getCode() == KeyCode.ENTER) {
                target.fire();
                event.consume();
            }
            return;
        }
        event.consume();
        // Complete the gesture on release so its typed space/newline cannot reach the draft.
        entryKey = event.getCode();
    }

    private static ButtonBase controlAt(Object target) {
        for (Node node = target instanceof Node n ? n : null; node != null; node = node.getParent()) {
            if (node instanceof ButtonBase button) return button;
        }
        return null;
    }

    private void enter() {
        if (entering || closed) return;
        entering = true;
        setMouseTransparent(true);
        if (arrival != null) arrival.stop();
        centerArt();
        revealWorkspace.run();
        if (reducedMotion.get()) { finishEntry(); return; }
        prepareTint();
        departure = OpeningMotion.irisExit(this, tint, zoom, titles, focusRing, enter,
                List.of(languages, windowButtons, motion));
        departure.setOnFinished(event -> finishEntry());
        departure.playFromStart();
    }

    private void shiftArt(double x, double y) {
        double fromX = artwork.getTranslateX(), fromY = artwork.getTranslateY();
        parallax.stop();
        parallax.setFromX(fromX);
        parallax.setFromY(fromY);
        parallax.setToX(x);
        parallax.setToY(y);
        parallax.playFromStart();
    }

    private void centerArt() {
        parallax.stop();
        artwork.setTranslateX(0);
        artwork.setTranslateY(0);
    }

    private void settleTitle() {
        if (arrival != null) arrival.stop();
        for (Node word : titles.getChildren()) {
            word.setOpacity(1);
            word.setTranslateY(0);
        }
    }

    private void finishEntry() {
        if (closed) return;
        close();
        onEntered.run();
    }

    void close() {
        closed = true;
        parallax.stop();
        if (arrival != null) arrival.stop();
        if (departure != null) departure.stop();
        motion.selectedProperty().unbindBidirectional(reducedMotion);
    }

    private void prepareTint() {
        if (tint.getImage() == null) {
            tint.setImage(OpeningMotion.exitTint(artwork.getImage()));
            updateArtwork();
        }
    }

    private void updateArtwork() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        cropToWindow(artwork, w, h);
        if (tint.getImage() != null) cropToWindow(tint, w, h);
    }

    private void cropToWindow(ImageView layer, double w, double h) {
        Image source = layer.getImage();
        // A changing source crop keeps the render surface the size of the window throughout the zoom.
        double cover = Math.max(w / source.getWidth(), h / source.getHeight()) * zoom.get() / OpeningMotion.ART_SCALE;
        double cropW = w / cover, cropH = h / cover;
        layer.setViewport(new Rectangle2D((source.getWidth() - cropW) / 2,
                (source.getHeight() - cropH) / 2, cropW, cropH));
        layer.setFitWidth(w);
        layer.setFitHeight(h);
    }

    @Override protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        updateArtwork();
        keyboardOutline.resizeRelocate(0, 0, w, h);
        dragStrip.resizeRelocate(0, 0, w, 76);
        windowButtons.autosize();
        windowButtons.relocate(INSET, INSET);
        languages.autosize();
        languages.relocate(w - INSET - languages.getWidth(), INSET);
        titles.autosize();
        titles.relocate((w - titles.getWidth()) / 2, h * .39 - titles.getHeight() / 2);
        enter.autosize();
        enter.relocate((w - enter.getWidth()) / 2, h * .92 - enter.getHeight() / 2 - 10);
        motion.autosize();
        motion.relocate(INSET, h - INSET - motion.getHeight());
        focusRing.setCenterX(w * .5);
        focusRing.setCenterY(h * .48);
    }
}
