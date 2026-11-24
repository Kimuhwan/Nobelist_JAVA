package com.scientificlens.harness.ui;

import com.scientificlens.harness.core.LensRegistry;
import com.scientificlens.harness.domain.DebateFinding;
import com.scientificlens.harness.domain.LensProfile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javafx.animation.AnimationTimer;
import javafx.beans.value.ObservableValue;
import javafx.css.CssMetaData;
import javafx.css.Styleable;
import javafx.css.StyleableProperty;
import javafx.css.StyleablePropertyFactory;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Canvas-based interactive diagram visualising the debate between lenses.
 *
 * <p>Each lens that participates in at least one {@link DebateFinding} is drawn as a
 * circular node. A directed arrow from A to B means "lens A raised a concern about lens
 * B's analysis". Hovering a node or arrow shows a popup with the concern and suggested
 * revision. Nodes animate in with a fade-in on first render; edges are drawn with an
 * animated dashed stroke while findings are loading.
 */
final class DebateGraphView extends StackPane {

    // ---- layout constants --------------------------------------------------
    private static final double NODE_R = 36;
    private static final double MIN_CANVAS_H = 340;
    private static final double EDGE_HIT = 12;          // px tolerance for edge hover
    private static final double ARROW_HEAD = 10;
    private static final double ARROW_ANGLE = 0.45;     // radians

    // Canvas colors resolve the same CSS tokens as the native controls.
    private static final StyleablePropertyFactory<DebateGraphView> COLORS =
            new StyleablePropertyFactory<>(StackPane.getClassCssMetaData());
    private final List<StyleableProperty<Color>> nodeColors = new ArrayList<>();
    private final StyleableProperty<Color> neutralColor = color("neutral", graph -> graph.neutralColor);
    private final StyleableProperty<Color> hoverColor = color("hover", graph -> graph.hoverColor);
    private final StyleableProperty<Color> labelColor = color("label", graph -> graph.labelColor);

    // ---- data ------------------------------------------------------------------
    private record GraphNode(String lensId, String displayName, double cx, double cy,
                             double alpha) {}
    private record GraphEdge(int from, int to, DebateFinding finding) {}

    private List<GraphNode> nodes = List.of();
    private List<GraphEdge> edges = List.of();

    // ---- render state ----------------------------------------------------------
    private final Canvas canvas;
    private final Pane canvasPane;
    private final VBox tooltip;
    private final Label tooltipTitle;
    private final Label tooltipConcern;
    private final Label tooltipRevision;

    private double[] alphas = new double[0];  // per-node fade progress [0..1]
    private double[] edgeProgress = new double[0]; // per-edge draw progress [0..1]
    private AnimationTimer anim;
    private int hoveredNode = -1;
    private int hoveredEdge = -1;

    // ---- construction ----------------------------------------------------------

    DebateGraphView() {
        getStyleClass().add("debate-graph");
        for (int i = 0; i < 8; i++) {
            final int index = i;
            nodeColors.add(color("node-" + (i + 1), graph -> graph.nodeColors.get(index)));
        }
        canvas = new Canvas(600, MIN_CANVAS_H);
        canvasPane = new Pane(canvas);

        // tooltip overlay
        tooltipTitle = new Label();
        tooltipTitle.getStyleClass().add("debate-tip-title");
        tooltipTitle.setWrapText(true);
        tooltipConcern = new Label();
        tooltipConcern.getStyleClass().add("debate-tip-concern");
        tooltipConcern.setWrapText(true);
        tooltipRevision = new Label();
        tooltipRevision.getStyleClass().add("debate-tip-revision");
        tooltipRevision.setWrapText(true);
        tooltipRevision.managedProperty().bind(tooltipRevision.visibleProperty());
        for (Label label : List.of(tooltipTitle, tooltipConcern, tooltipRevision)) {
            label.setMinHeight(Region.USE_PREF_SIZE);
        }

        VBox tipContent = new VBox(6, tooltipTitle, tooltipConcern, tooltipRevision);
        tipContent.setPadding(new Insets(10, 14, 10, 14));
        tipContent.setMaxWidth(280);

        tooltip = new VBox(tipContent);
        tooltip.getStyleClass().add("debate-tooltip");
        tooltip.setVisible(false);
        tooltip.setManaged(false);
        tooltip.setMinWidth(280);
        tooltip.setPrefWidth(280);
        tooltip.setMaxWidth(280);
        tooltip.setMaxHeight(Region.USE_PREF_SIZE);
        tooltip.setMouseTransparent(true);

        getChildren().addAll(canvasPane, tooltip);

        // resize canvas with pane
        widthProperty().addListener((_, _, w) -> {
            hideTooltip();
            canvas.setWidth(w.doubleValue());
            canvasPane.setPrefWidth(w.doubleValue());
            relayout();
            redraw();
        });
        heightProperty().addListener((_, _, h) -> {
            hideTooltip();
            double ch = Math.max(h.doubleValue(), MIN_CANVAS_H);
            canvas.setHeight(ch);
            canvasPane.setPrefHeight(ch);
            relayout();
            redraw();
        });

        // mouse interaction
        canvas.setOnMouseMoved(e -> onMouseMoved(e.getX(), e.getY()));
        canvas.setOnMouseExited(_ -> {
            hideTooltip();
            redraw();
        });
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return COLORS.getCssMetaData();
    }

    private StyleableProperty<Color> color(String name,
            Function<DebateGraphView, StyleableProperty<Color>> getter) {
        StyleableProperty<Color> property = COLORS.createStyleableColorProperty(
                this, name + "Color", "-slh-" + name + "-color", getter, Color.GRAY);
        if (property instanceof ObservableValue<?> observable) {
            observable.addListener((_, _, _) -> redraw());
        }
        return property;
    }

    // ---- public API ------------------------------------------------------------

    /** Replace the current diagram with findings from a new result. */
    void setFindings(List<DebateFinding> findings) {
        stopAnim();
        hideTooltip();
        if (findings.isEmpty()) {
            nodes = List.of();
            edges = List.of();
            alphas = new double[0];
            edgeProgress = new double[0];
            redraw();
            return;
        }
        buildGraph(findings);
        startAnim();
    }

    void clear() {
        stopAnim();
        nodes = List.of();
        edges = List.of();
        alphas = new double[0];
        edgeProgress = new double[0];
        tooltip.setVisible(false);
        redraw();
    }

    // ---- graph construction ---------------------------------------------------

    private void buildGraph(List<DebateFinding> findings) {
        // collect unique lens ids preserving encounter order
        List<String> ids = new ArrayList<>();
        for (DebateFinding f : findings) {
            if (!ids.contains(f.sourceLensId())) ids.add(f.sourceLensId());
            if (!ids.contains(f.targetLensId())) ids.add(f.targetLensId());
        }

        // arrange nodes in a circle (or line if only 2)
        int n = ids.size();
        double cx = canvas.getWidth() / 2;
        double cy = canvas.getHeight() / 2;
        double radius = Math.min(cx, cy) - NODE_R - 20;
        if (radius < NODE_R + 10) radius = NODE_R + 10;

        Map<String, Integer> idxMap = new HashMap<>();
        List<GraphNode> newNodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = ids.get(i);
            double angle = -Math.PI / 2 + (2 * Math.PI * i) / n;
            double nx = cx + radius * Math.cos(angle);
            double ny = cy + radius * Math.sin(angle);
            newNodes.add(new GraphNode(id, displayName(id), nx, ny, 0));
            idxMap.put(id, i);
        }

        List<GraphEdge> newEdges = new ArrayList<>();
        for (DebateFinding f : findings) {
            int from = idxMap.get(f.sourceLensId());
            int to = idxMap.get(f.targetLensId());
            newEdges.add(new GraphEdge(from, to, f));
        }

        nodes = List.copyOf(newNodes);
        edges = List.copyOf(newEdges);
        alphas = new double[n];       // all 0 → animate to 1
        edgeProgress = new double[newEdges.size()];
    }

    private void relayout() {
        if (nodes.isEmpty()) return;
        int n = nodes.size();
        double cx = canvas.getWidth() / 2;
        double cy = canvas.getHeight() / 2;
        double radius = Math.min(cx, cy) - NODE_R - 20;
        if (radius < NODE_R + 10) radius = NODE_R + 10;

        List<GraphNode> updated = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            GraphNode old = nodes.get(i);
            double angle = -Math.PI / 2 + (2 * Math.PI * i) / n;
            double nx = cx + radius * Math.cos(angle);
            double ny = cy + radius * Math.sin(angle);
            updated.add(new GraphNode(old.lensId(), old.displayName(), nx, ny, old.alpha()));
        }
        nodes = List.copyOf(updated);
    }

    // ---- animation -------------------------------------------------------------

    private void startAnim() {
        anim = new AnimationTimer() {
            private long last = 0;
            @Override
            public void handle(long now) {
                if (last == 0) { last = now; return; }
                double dt = (now - last) / 1_000_000_000.0; // seconds
                last = now;
                boolean done = true;

                // fade-in nodes
                for (int i = 0; i < alphas.length; i++) {
                    if (alphas[i] < 1) {
                        alphas[i] = Math.min(1, alphas[i] + dt * 2.5);
                        done = false;
                    }
                }
                // draw edges (start after nodes are partially visible)
                for (int i = 0; i < edgeProgress.length; i++) {
                    if (edgeProgress[i] < 1) {
                        edgeProgress[i] = Math.min(1, edgeProgress[i] + dt * 1.8);
                        done = false;
                    }
                }
                redraw();
                if (done) stop();
            }
        };
        anim.start();
    }

    private void stopAnim() {
        if (anim != null) { anim.stop(); anim = null; }
    }

    // ---- drawing ---------------------------------------------------------------

    private void redraw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        gc.clearRect(0, 0, w, h);

        if (nodes.isEmpty()) {
            gc.setFill(neutralColor.getValue());
            gc.setFont(Font.font("System", 14));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("토론 데이터가 없습니다", w / 2, h / 2);
            return;
        }

        // draw edges first (under nodes)
        for (int i = 0; i < edges.size(); i++) {
            drawEdge(gc, edges.get(i), i);
        }

        // draw nodes on top
        for (int i = 0; i < nodes.size(); i++) {
            drawNode(gc, nodes.get(i), i, alphas.length > i ? alphas[i] : 1.0);
        }
    }

    private void drawEdge(GraphicsContext gc, GraphEdge e, int idx) {
        GraphNode src = nodes.get(e.from());
        GraphNode dst = nodes.get(e.to());
        double progress = edgeProgress.length > idx ? edgeProgress[idx] : 1.0;
        if (progress <= 0) return;

        double dx = dst.cx() - src.cx();
        double dy = dst.cy() - src.cy();
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1) return;
        double ux = dx / len, uy = dy / len;

        // start/end points offset to node radius
        double x1 = src.cx() + ux * NODE_R;
        double y1 = src.cy() + uy * NODE_R;
        double x2e = dst.cx() - ux * NODE_R;  // full end
        double y2e = dst.cy() - uy * NODE_R;

        // animated endpoint
        double x2 = x1 + (x2e - x1) * progress;
        double y2 = y1 + (y2e - y1) * progress;

        boolean hovered = (hoveredEdge == idx);
        Color baseColor = hovered ? hoverColor.getValue() : neutralColor.getValue();
        gc.setStroke(baseColor.deriveColor(0, 1, 1, hovered ? 1.0 : 0.7));
        gc.setLineWidth(hovered ? 2.5 : 1.8);
        gc.setLineDashes(hovered ? null : new double[]{6, 4});
        gc.beginPath();
        gc.moveTo(x1, y1);
        gc.lineTo(x2, y2);
        gc.stroke();
        gc.setLineDashes((double[]) null);

        // arrowhead only when edge is fully drawn
        if (progress >= 1.0) {
            double ax1 = x2 - ARROW_HEAD * Math.cos(Math.atan2(uy, ux) - ARROW_ANGLE);
            double ay1 = y2 - ARROW_HEAD * Math.sin(Math.atan2(uy, ux) - ARROW_ANGLE);
            double ax2 = x2 - ARROW_HEAD * Math.cos(Math.atan2(uy, ux) + ARROW_ANGLE);
            double ay2 = y2 - ARROW_HEAD * Math.sin(Math.atan2(uy, ux) + ARROW_ANGLE);
            gc.setFill(baseColor.deriveColor(0, 1, 1, hovered ? 1.0 : 0.7));
            gc.beginPath();
            gc.moveTo(x2, y2);
            gc.lineTo(ax1, ay1);
            gc.lineTo(ax2, ay2);
            gc.closePath();
            gc.fill();
        }
    }

    private void drawNode(GraphicsContext gc, GraphNode node, int idx, double alpha) {
        if (alpha <= 0) return;
        boolean hovered = (hoveredNode == idx);
        Color fill = nodeColors.get(idx % nodeColors.size()).getValue();

        // glow shadow when hovered
        if (hovered) {
            gc.setFill(fill.deriveColor(0, 1, 1, 0.25 * alpha));
            gc.fillOval(node.cx() - NODE_R - 8, node.cy() - NODE_R - 8,
                    (NODE_R + 8) * 2, (NODE_R + 8) * 2);
        }

        // circle fill
        gc.setGlobalAlpha(alpha);
        gc.setFill(hovered ? fill.brighter() : fill);
        gc.fillOval(node.cx() - NODE_R, node.cy() - NODE_R, NODE_R * 2, NODE_R * 2);

        // circle stroke
        gc.setStroke(labelColor.getValue().deriveColor(0, 1, 1, 0.4));
        gc.setLineWidth(hovered ? 2.5 : 1.5);
        gc.strokeOval(node.cx() - NODE_R, node.cy() - NODE_R, NODE_R * 2, NODE_R * 2);

        // label (abbreviated display name)
        gc.setFill(labelColor.getValue());
        gc.setFont(Font.font("Malgun Gothic", FontWeight.BOLD, 12));
        gc.setTextAlign(TextAlignment.CENTER);
        String label = abbreviate(node.displayName());
        String[] lines = label.split("\n");
        double baseline = node.cy() + 5 - (lines.length - 1) * 8;
        for (int line = 0; line < lines.length; line++) {
            gc.fillText(lines[line], node.cx(), baseline + line * 16, NODE_R * 2 - 8);
        }

        gc.setGlobalAlpha(1.0);
    }

    // ---- mouse interaction -----------------------------------------------------

    private void onMouseMoved(double mx, double my) {
        int prevNode = hoveredNode;
        int prevEdge = hoveredEdge;
        hoveredNode = -1;
        hoveredEdge = -1;

        // check nodes first
        for (int i = 0; i < nodes.size(); i++) {
            GraphNode n = nodes.get(i);
            double dx = mx - n.cx(), dy = my - n.cy();
            if (dx * dx + dy * dy <= NODE_R * NODE_R) {
                hoveredNode = i;
                break;
            }
        }

        // check edges if no node hit
        if (hoveredNode < 0) {
            for (int i = 0; i < edges.size(); i++) {
                if (edgeProgress.length > i && edgeProgress[i] < 0.5) continue;
                GraphEdge e = edges.get(i);
                GraphNode src = nodes.get(e.from());
                GraphNode dst = nodes.get(e.to());
                if (distToSegment(mx, my, src.cx(), src.cy(), dst.cx(), dst.cy()) < EDGE_HIT) {
                    hoveredEdge = i;
                    break;
                }
            }
        }

        boolean changed = hoveredNode != prevNode || hoveredEdge != prevEdge;
        if (changed) redraw();

        // update tooltip
        if (hoveredNode >= 0) {
            GraphNode n = nodes.get(hoveredNode);
            // show all edges touching this node
            StringBuilder concerns = new StringBuilder();
            for (GraphEdge e : edges) {
                if (e.from() == hoveredNode || e.to() == hoveredNode) {
                    String from = nodes.get(e.from()).displayName();
                    String to = nodes.get(e.to()).displayName();
                    concerns.append(from).append(" → ").append(to).append("\n");
                    concerns.append(e.finding().concern()).append("\n\n");
                }
            }
            tooltipTitle.setText(n.displayName());
            tooltipConcern.setText(concerns.toString().strip());
            tooltipRevision.setVisible(false);
            positionTooltip(mx, my);
        } else if (hoveredEdge >= 0) {
            GraphEdge e = edges.get(hoveredEdge);
            String from = nodes.get(e.from()).displayName();
            String to = nodes.get(e.to()).displayName();
            tooltipTitle.setText(from + "  →  " + to);
            tooltipConcern.setText(e.finding().concern());
            tooltipRevision.setText("수정 제안: " + e.finding().suggestedRevision());
            tooltipRevision.setVisible(true);
            positionTooltip(mx, my);
        } else {
            tooltip.setVisible(false);
        }
    }

    private void positionTooltip(double mx, double my) {
        Bounds visible = canvas.getLayoutBounds();
        for (Node ancestor = getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            if (ancestor instanceof ScrollPane scroll) {
                Node viewport = scroll.lookup(".viewport");
                if (viewport != null) {
                    visible = canvas.sceneToLocal(viewport.localToScene(viewport.getLayoutBounds()));
                }
                break;
            }
        }
        tooltip.applyCss();
        double tw = 280;
        double th = tooltip.prefHeight(tw);
        double tx = mx + 16;
        if (tx + tw > visible.getMaxX() - 8) tx = mx - tw - 8;
        tx = Math.max(visible.getMinX() + 8, Math.min(tx, visible.getMaxX() - tw - 8));
        double ty = Math.max(visible.getMinY() + 8,
                Math.min(my - 10, visible.getMaxY() - th - 8));
        tooltip.resizeRelocate(tx, ty, tw, th);
        tooltip.layout();
        tooltip.setVisible(true);
    }

    private void hideTooltip() {
        hoveredNode = -1;
        hoveredEdge = -1;
        tooltip.setVisible(false);
    }

    // ---- utilities -------------------------------------------------------------

    private static double distToSegment(double px, double py,
                                        double ax, double ay,
                                        double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) return Math.hypot(px - ax, py - ay);
        double t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / lenSq));
        double projX = ax + t * dx, projY = ay + t * dy;
        return Math.hypot(px - projX, py - projY);
    }

    private static String abbreviate(String name) {
        if (name == null || name.isBlank()) return "?";
        // split on whitespace; use up to two words
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            // single word: up to 8 chars
            return parts[0].length() > 8 ? parts[0].substring(0, 7) + "…" : parts[0];
        }
        // multi-word: first + last initial or first two words truncated
        String first = parts[0].length() > 9 ? parts[0].substring(0, 8) + "…" : parts[0];
        String second = parts[parts.length - 1].length() > 6
                ? parts[parts.length - 1].substring(0, 5) + "." : parts[parts.length - 1];
        return first + "\n" + second;
    }

    private static String displayName(String lensId) {
        try {
            LensProfile p = LensRegistry.get(lensId);
            return p.displayName();
        } catch (IllegalArgumentException e) {
            return lensId;
        }
    }
}
