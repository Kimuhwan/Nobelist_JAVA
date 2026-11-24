package com.scientificlens.harness.ui;

import java.util.List;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.geometry.Rectangle2D;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/** One-shot title arrival and the web council's 920 ms circular exit. */
final class OpeningMotion {
    static final double ART_SCALE = 1.025;
    static final Interpolator POINTER_EASE = Interpolator.SPLINE(0, 0, .58, 1);
    private static final Interpolator OUT = Interpolator.SPLINE(.22, 1, .36, 1);
    private static final Interpolator IN = Interpolator.SPLINE(.7, 0, .84, 0);

    private OpeningMotion() {}

    static Timeline titleArrival(List<Node> lines) {
        Timeline timeline = new Timeline();
        for (int i = 0; i < lines.size(); i++) {
            Node line = lines.get(i);
            line.setOpacity(0);
            line.setTranslateY(12);
            double start = i * 60;
            timeline.getKeyFrames().addAll(
                    new KeyFrame(Duration.millis(start), new KeyValue(line.opacityProperty(), 0),
                            new KeyValue(line.translateYProperty(), 12)),
                    new KeyFrame(Duration.millis(start + 500), new KeyValue(line.opacityProperty(), 1, OUT),
                            new KeyValue(line.translateYProperty(), 0, OUT)));
        }
        return timeline;
    }

    static Image exitTint(Image source) {
        // Prepare the soft, bright endpoint once. Crossfading it avoids filtering a full window every pulse.
        ImageView thumbnail = new ImageView(source);
        int width = 640, height = (int) Math.round(width * source.getHeight() / source.getWidth());
        thumbnail.setFitWidth(width);
        thumbnail.setFitHeight(height);
        ColorAdjust color = new ColorAdjust();
        color.setSaturation(.2);
        color.setBrightness(.12);
        GaussianBlur blur = new GaussianBlur(2);
        blur.setInput(color);
        thumbnail.setEffect(blur);
        SnapshotParameters sample = new SnapshotParameters();
        sample.setViewport(new Rectangle2D(0, 0, width, height));
        return thumbnail.snapshot(sample, null);
    }

    static Timeline irisExit(Region opening, Node tint, DoubleProperty zoom, Node title, Circle ring,
            Node prompt, List<Node> controls) {
        DoubleProperty fraction = new SimpleDoubleProperty(1.5);
        Circle iris = new Circle();
        iris.centerXProperty().bind(opening.widthProperty().multiply(.5));
        iris.centerYProperty().bind(opening.heightProperty().multiply(.48));
        // CSS circle percentages use the viewport's normalized diagonal. Recompute on resize.
        iris.radiusProperty().bind(Bindings.createDoubleBinding(
                () -> Math.hypot(opening.getWidth(), opening.getHeight()) / Math.sqrt(2) * fraction.get(),
                opening.widthProperty(), opening.heightProperty(), fraction));
        opening.setClip(iris);
        ring.setScaleX(1.7);
        ring.setScaleY(1.7);
        Timeline exit = new Timeline(
                new KeyFrame(Duration.millis(200), new KeyValue(ring.opacityProperty(), .75, OUT)),
                new KeyFrame(Duration.millis(240), new KeyValue(zoom, 3.4, POINTER_EASE)),
                new KeyFrame(Duration.millis(360), new KeyValue(prompt.opacityProperty(), 0, Interpolator.EASE_IN)),
                new KeyFrame(Duration.millis(540), new KeyValue(title.opacityProperty(), 0, Interpolator.EASE_IN)),
                new KeyFrame(Duration.millis(620), new KeyValue(prompt.scaleXProperty(), .2, Interpolator.EASE_IN),
                        new KeyValue(prompt.scaleYProperty(), .2, Interpolator.EASE_IN)),
                new KeyFrame(Duration.millis(662), new KeyValue(fraction, .12, IN),
                        new KeyValue(opening.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(760), new KeyValue(title.scaleXProperty(), .08, IN),
                        new KeyValue(title.scaleYProperty(), .08, IN)),
                new KeyFrame(Duration.millis(820), new KeyValue(tint.opacityProperty(), 1, Interpolator.EASE_IN),
                        new KeyValue(ring.scaleXProperty(), .02, IN), new KeyValue(ring.scaleYProperty(), .02, IN),
                        new KeyValue(ring.opacityProperty(), 0)),
                new KeyFrame(Duration.millis(920), new KeyValue(fraction, 0, IN),
                        new KeyValue(opening.opacityProperty(), 0, IN)));
        for (Node control : controls) {
            exit.getKeyFrames().add(new KeyFrame(Duration.millis(360),
                    new KeyValue(control.opacityProperty(), 0, Interpolator.EASE_IN)));
        }
        return exit;
    }
}
