package com.dlsc.gemsfx.skins;

import com.dlsc.gemsfx.PDFView;
import com.dlsc.unitfx.IntegerInputField;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.FloatProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleFloatProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Service;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SkinBase;
import javafx.scene.control.Slider;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class PDFViewSkin extends SkinBase<PDFView> {

    // Access to PDF document must be single threaded (see Apache PdfBox website FAQs)
    private final Executor EXECUTOR = Executors.newSingleThreadExecutor();

    private final ObservableList<Integer> pdfFilePages = FXCollections.observableArrayList();
    private final BorderPane borderPane;

    private ListView<Integer> thumbnailListView = new ListView<>();

    private PDFRenderer renderer;

    private final Map<Integer, Image> imageCache = new HashMap<>();

    public PDFViewSkin(PDFView view) {
        super(view);

        thumbnailListView.getStyleClass().add("thumbnail-list-view");
        thumbnailListView.visibleProperty().bind(view.showThumbnailsProperty());
        thumbnailListView.managedProperty().bind(view.showThumbnailsProperty());
        thumbnailListView.setPlaceholder(null);
        thumbnailListView.getSelectionModel().selectedItemProperty().addListener(it -> {
            final Integer selectedItem = thumbnailListView.getSelectionModel().getSelectedItem();
            if (selectedItem != null) {
                view.setPage(selectedItem);
            }
        });
        thumbnailListView.setCellFactory(listView -> new PdfPageListCell());
        thumbnailListView.setItems(pdfFilePages);
        thumbnailListView.prefWidthProperty().bind(view.thumbnailSizeProperty().multiply(1.25));
        thumbnailListView.requestFocus();

        view.pageProperty().addListener(it -> thumbnailListView.getSelectionModel().select(view.getPage()));

        pdfFilePages.addListener((Observable it) -> {
            if (!pdfFilePages.isEmpty()) {
                thumbnailListView.getSelectionModel().select(0);
            }
        });

        view.documentProperty().addListener(it -> updatePagesList());
        updatePagesList();

        final ToolBar toolBar = createToolBar(view);

        MainAreaScrollPane mainArea = new MainAreaScrollPane();

        borderPane = new BorderPane();
        borderPane.setTop(toolBar);
        borderPane.setLeft(thumbnailListView);
        borderPane.setCenter(mainArea);
        borderPane.setFocusTraversable(false);

        getChildren().add(borderPane);

        view.documentProperty().addListener(it -> {
            imageCache.clear();
            view.setPage(-1);
            view.setPage(0);
        });
    }

    private final DoubleProperty requestedVValue = new SimpleDoubleProperty(-1);

    private ToolBar createToolBar(PDFView pdfView) {
        final PDFView view = getSkinnable();

        // show all
        ToggleButton showAll = new ToggleButton("Show All");
        showAll.selectedProperty().bindBidirectional(pdfView.showAllProperty());

        // paging
        Button goLeft = new Button("<");
        goLeft.setOnAction(evt -> view.gotoPreviousPage());
        goLeft.getStyleClass().add("left-button");
        goLeft.disableProperty().bind(Bindings.createBooleanBinding(() -> view.getPage() <= 0, view.pageProperty(), view.documentProperty()));

        Button goRight = new Button(">");
        goRight.setOnAction(evt -> view.gotoNextPage());
        goRight.getStyleClass().add("right-button");
        goRight.disableProperty().bind(Bindings.createBooleanBinding(() -> view.getDocument() == null || view.getDocument().getNumberOfPages() <= view.getPage() + 1, view.pageProperty(), view.documentProperty()));

        IntegerInputField pageField = new IntegerInputField();
        pageField.getStyleClass().add("page-field");
        pageField.setAllowNegatives(false);
        pageField.setMinimumValue(1);
        pageField.setAlignment(Pos.CENTER);
        pageField.setValue(view.getPage() + 1);
        view.pageProperty().addListener(it -> pageField.setValue(view.getPage() + 1));
        pageField.valueProperty().addListener(it -> {
            final Integer value = pageField.getValue();
            if (value != null) {
                view.setPage(value - 1);
            }
        });
        updateMaximumValue(pageField);
        view.documentProperty().addListener(it -> updateMaximumValue(pageField));

        HBox pageControl = new HBox(goLeft, pageField, goRight);
        pageControl.disableProperty().bind(view.documentProperty().isNull());
        pageControl.getStyleClass().add("page-control");

        // rotate buttons
        Button rotateLeft = new Button("Rotate Left");
        rotateLeft.setOnAction(evt -> view.rotateLeft());

        Button rotateRight = new Button("Rotate Right");
        rotateRight.setOnAction(evt -> view.rotateRight());

        // zoom slider
        Slider zoomSlider = new Slider();
        zoomSlider.setMin(1);
        zoomSlider.maxProperty().bind(view.maxZoomFactorProperty());
        zoomSlider.valueProperty().bindBidirectional(view.zoomFactorProperty());
        zoomSlider.disableProperty().bind(view.showAllProperty());

        final Label zoomLabel = new Label("Zoom");
        zoomLabel.disableProperty().bind(view.showAllProperty());

        // toolbar
        return new ToolBar(
                showAll,
                new Separator(Orientation.VERTICAL),
                zoomLabel,
                zoomSlider,
                new Separator(Orientation.VERTICAL),
                pageControl,
                new Separator(Orientation.VERTICAL),
                rotateLeft,
                rotateRight);
    }

    private void updateMaximumValue(IntegerInputField pageField) {
        final PDDocument document = getSkinnable().getDocument();
        if (document != null) {
            pageField.setMaximumValue(document.getNumberOfPages());
        }
    }

    class PagerService extends Service<Void> {
        private boolean up;

        public void setUp(boolean up) {
            this.up = up;
        }

        @Override
        protected Task<Void> createTask() {
            return new PagerTask(up);
        }
    }

    class PagerTask extends Task<Void> {
        private boolean up;

        public PagerTask(boolean up) {
            this.up = up;
        }

        @Override
        protected Void call() throws Exception {
            Thread.sleep(100);
            Platform.runLater(() -> {
                if (up) {
                    getSkinnable().gotoPreviousPage();
                    requestedVValue.set(1);
                } else {
                    getSkinnable().gotoNextPage();
                    requestedVValue.set(0);
                }
            });

            return null;
        }
    }

    private final PagerService pagerService = new PagerService();

    class MainAreaScrollPane extends ScrollPane {

        private final StackPane wrapper;
        private Pane pane;
        private Group group;
        private RenderService mainAreaRenderService = new RenderService(false);

        public MainAreaScrollPane() {

            mainAreaRenderService.setOnSucceeded(evt -> {
                double vValue = requestedVValue.get();
                if (vValue != -1) {
                    setVvalue(vValue);
                    requestedVValue.set(-1);
                }
            });

            addEventHandler(KeyEvent.KEY_PRESSED, evt -> {
                switch (evt.getCode()) {
                    case UP:
                    case LEFT:
                    case PAGE_UP:
                    case HOME:
                        if (getVvalue() == 0 || getSkinnable().isShowAll() || evt.getCode() == KeyCode.LEFT) {
                            requestedVValue.set(1);
                            getSkinnable().gotoPreviousPage();
                        }
                        break;
                    case DOWN:
                    case RIGHT:
                    case PAGE_DOWN:
                    case END:
                        if (getVvalue() == 1 || getSkinnable().isShowAll() || evt.getCode() == KeyCode.RIGHT) {
                            requestedVValue.set(0);
                            getSkinnable().gotoNextPage();
                        }
                        break;
                }
            });

            addEventHandler(ScrollEvent.SCROLL, evt -> {
                if (evt.isInertia()) {
                    return;
                }

                boolean success;

                if (evt.getDeltaY() > 0) {
                    success = getSkinnable().getPage() > 1;
                    pagerService.setUp(true);
                } else {
                    success = getSkinnable().getPage() < getSkinnable().getDocument().getNumberOfPages() - 1;
                    pagerService.setUp(false);
                }

                if (success) {
                    pagerService.restart();
                    evt.consume();
                }

            });

            setFitToWidth(true);

            setFitToHeight(true);

            setPannable(true);

            pane = new

                    Pane() {
                        @Override
                        protected void layoutChildren() {
                            wrapper.resizeRelocate((getWidth() - wrapper.prefWidth(-1)) / 2, (getHeight() - wrapper.prefHeight(-1)) / 2, wrapper.prefWidth(-1), wrapper.prefHeight(-1));
                        }
                    }

            ;

            wrapper = new

                    StackPane();
            wrapper.getStyleClass().

                    add("image-view-wrapper");
            wrapper.setMaxWidth(Region.USE_PREF_SIZE);
            wrapper.setMaxHeight(Region.USE_PREF_SIZE);
            wrapper.rotateProperty().

                    bind(getSkinnable().

                            pageRotationProperty());

            group = new

                    Group(wrapper);
            pane.getChildren().

                    add(group);

            viewportBoundsProperty().

                    addListener(it ->

                    {
                        final Bounds bounds = getViewportBounds();

                        pane.setPrefWidth(Region.USE_COMPUTED_SIZE);
                        pane.setMinWidth(Region.USE_COMPUTED_SIZE);

                        pane.setPrefHeight(Region.USE_COMPUTED_SIZE);
                        pane.setMinHeight(Region.USE_COMPUTED_SIZE);

                        if (isPortrait()) {

                            final double prefWidth = bounds.getWidth() * getSkinnable().getZoomFactor() - 5;
                            pane.setPrefWidth(prefWidth);
                            pane.setMinWidth(prefWidth);

                            if (getSkinnable().isShowAll()) {
                                pane.setPrefHeight(bounds.getHeight() - 5);
                            } else {
                                Image image = getImage();
                                if (image != null) {
                                    double scale = bounds.getWidth() / image.getWidth();
                                    double scaledImageHeight = image.getHeight() * scale;
                                    final double prefHeight = scaledImageHeight * getSkinnable().getZoomFactor();
                                    pane.setPrefHeight(prefHeight);
                                    pane.setMinHeight(prefHeight);
                                }
                            }

                        } else {

                            /*
                             * Image has been rotated.
                             */

                            final double prefHeight = bounds.getHeight() * getSkinnable().getZoomFactor() - 5;
                            pane.setPrefHeight(prefHeight);
                            pane.setMinHeight(prefHeight);

                            if (getSkinnable().isShowAll()) {
                                pane.setPrefWidth(bounds.getWidth() - 5);
                            } else {
                                Image image = getImage();
                                if (image != null) {
                                    double scale = bounds.getHeight() / image.getWidth();
                                    double scaledImageHeight = image.getHeight() * scale;
                                    final double prefWidth = scaledImageHeight * getSkinnable().getZoomFactor();
                                    pane.setPrefWidth(prefWidth);
                                    pane.setMinWidth(prefWidth);
                                }
                            }

                        }
                    });

            setContent(pane);

            mainAreaRenderService.setExecutor(EXECUTOR);
            mainAreaRenderService.scaleProperty().

                    bind(getSkinnable().

                            pageScaleProperty().

                            multiply(getSkinnable().

                                    zoomFactorProperty()));
            mainAreaRenderService.pageProperty().

                    bind(getSkinnable().

                            pageProperty());

            mainAreaRenderService.valueProperty().

                    addListener(it ->

                    {
                        Image image = mainAreaRenderService.getValue();
                        if (image != null) {
                            setImage(image);
                        }
                    });

            getSkinnable().

                    showAllProperty().

                    addListener(it ->

                    {
                        updateScrollbarPolicies();
                        layoutImage();
                        requestLayout();
                    });

            getSkinnable().

                    pageRotationProperty().

                    addListener(it ->

                    {
                        updateScrollbarPolicies();
                        layoutImage();
                    });

            getSkinnable().

                    zoomFactorProperty().

                    addListener(it ->

                    {
                        updateScrollbarPolicies();
                        requestLayout();
                    });

            updateScrollbarPolicies();

            layoutImage();
        }

        private final ObjectProperty<Image> image = new SimpleObjectProperty<>(this, "image");

        private void setImage(Image image) {
            this.image.set(image);
        }

        private Image getImage() {
            return image.get();
        }

        protected void layoutImage() {
            ImageView imageView = new ImageView();
            imageView.imageProperty().bind(image);
            imageView.setPreserveRatio(true);
            wrapper.getChildren().setAll(imageView);

            requestLayout();

            if (getSkinnable().isShowAll()) {
                fitAll(imageView);
            } else {
                fitWidth(imageView);
            }
        }

        private void fitWidth(ImageView imageView) {
            if (isPortrait()) {
                imageView.fitWidthProperty().bind(pane.widthProperty().subtract(40));
                imageView.fitHeightProperty().unbind();
            } else {
                imageView.fitWidthProperty().bind(pane.heightProperty().subtract(40));
                imageView.fitHeightProperty().unbind();
            }
        }

        private void fitAll(ImageView imageView) {
            if (isPortrait()) {
                imageView.fitWidthProperty().bind(pane.widthProperty().subtract(40));
                imageView.fitHeightProperty().bind(pane.heightProperty().subtract(40));
            } else {
                imageView.fitWidthProperty().bind(pane.heightProperty().subtract(40));
                imageView.fitHeightProperty().bind(pane.widthProperty().subtract(40));
            }
        }

        private void updateScrollbarPolicies() {
            if (getSkinnable().isShowAll()) {
                setVbarPolicy(ScrollBarPolicy.NEVER);
                setHbarPolicy(ScrollBarPolicy.NEVER);
            } else {
                if (getSkinnable().getZoomFactor() > 1) {
                    setVbarPolicy(ScrollBarPolicy.ALWAYS);
                    setHbarPolicy(ScrollBarPolicy.ALWAYS);
                } else {
                    if (isPortrait()) {
                        setVbarPolicy(ScrollBarPolicy.ALWAYS);
                        setHbarPolicy(ScrollBarPolicy.NEVER);
                    } else {
                        setVbarPolicy(ScrollBarPolicy.NEVER);
                        setHbarPolicy(ScrollBarPolicy.ALWAYS);
                    }
                }
            }
        }

        private boolean isPortrait() {
            return getSkinnable().getPageRotation() % 180 == 0;
        }
    }

    private class RenderService extends Service<Image> {

        private final boolean thumbnailRenderer;

        public RenderService(boolean thumbnailRenderer) {
            this.thumbnailRenderer = thumbnailRenderer;

            setExecutor(EXECUTOR);

            final InvalidationListener restartListener = it -> restart();
            page.addListener(restartListener);
            scale.addListener(restartListener);
        }

        private final FloatProperty scale = new SimpleFloatProperty();

        private float getScale() {
            return scale.get();
        }

        FloatProperty scaleProperty() {
            return scale;
        }

        // initialize with -1 to ensure property fires
        private final IntegerProperty page = new SimpleIntegerProperty(-1);

        private final void setPage(int page) {
            this.page.set(page);
        }

        private int getPage() {
            return page.get();
        }

        IntegerProperty pageProperty() {
            return page;
        }

        @Override
        protected Task<Image> createTask() {
            return new RenderTask(thumbnailRenderer, getPage(), getScale());
        }
    }

    private class RenderTask extends Task<Image> {

        private final int page;
        private final float scale;
        private final boolean thumbnail;

        public RenderTask(boolean thumbnail, int page, float scale) {
            this.thumbnail = thumbnail;
            this.page = page;
            this.scale = scale;
        }

        @Override
        protected Image call() {
            if (page >= 0 && page < getSkinnable().getDocument().getNumberOfPages()) {
                if (!isCancelled()) {
                    try {

                        double s = scale;
                        final Image renderedImage = renderPDFPage(page, (float) s);
                        if (getSkinnable().isCacheThumbnails() && thumbnail) {
                            imageCache.put(page, renderedImage);
                        }
                        return renderedImage;

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            return null;
        }

        private Image renderPDFPage(int page, float scale) throws IOException {
            BufferedImage bufferedImage = renderer.renderImage(page, scale, ImageType.ARGB, RenderDestination.VIEW);
            Image image = SwingFXUtils.toFXImage(bufferedImage, null);
            return image;
        }
    }

    private void updatePagesList() {
        final PDDocument document = getSkinnable().getDocument();
        pdfFilePages.clear();
        if (document != null) {
            renderer = new PDFRenderer(document);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                pdfFilePages.add(i);
            }
        }
    }

    class PdfPageListCell extends ListCell<Integer> {

        private ImageView imageView = new ImageView();
        private Label pageNumberLabel = new Label();

        private final RenderService renderService = new RenderService(true);

        public PdfPageListCell() {
            StackPane stackPane = new StackPane(imageView);
            stackPane.getStyleClass().add("image-view-wrapper");
            stackPane.setMaxWidth(Region.USE_PREF_SIZE);
            stackPane.visibleProperty().bind(imageView.imageProperty().isNotNull());

            pageNumberLabel.getStyleClass().add("page-number-label");
            pageNumberLabel.visibleProperty().bind(imageView.imageProperty().isNotNull());

            VBox vBox = new VBox(5, stackPane, pageNumberLabel);
            vBox.setAlignment(Pos.CENTER);
            vBox.setFillWidth(true);
            vBox.visibleProperty().bind(emptyProperty().not());
            setGraphic(vBox);

            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);

            imageView.setPreserveRatio(true);

            setAlignment(Pos.CENTER);
            setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            setMinSize(0, 0);

            indexProperty().addListener(it -> {
                final Image image = imageCache.get(getIndex());
                if (getSkinnable().isCacheThumbnails() && image != null) {
                    imageView.setImage(image);
                } else {
                    renderService.setPage(getIndex());
                }
            });

            renderService.scaleProperty().bind(PDFViewSkin.this.getSkinnable().thumbnailPageScaleProperty());
            renderService.valueProperty().addListener(it -> imageView.setImage(renderService.getValue()));
        }

        @Override
        protected void updateItem(Integer pageNumber, boolean empty) {
            super.updateItem(pageNumber, empty);

            if (pageNumber != null && !empty) {
                final PDDocument document = getSkinnable().getDocument();
                final PDPage page = document.getPage(pageNumber);
                final PDRectangle cropBox = page.getCropBox();

                if (cropBox.getHeight() < cropBox.getWidth()) {
                    imageView.fitWidthProperty().bind(getSkinnable().thumbnailSizeProperty());
                    imageView.fitHeightProperty().unbind();
                } else {
                    imageView.fitWidthProperty().unbind();
                    imageView.fitHeightProperty().bind(getSkinnable().thumbnailSizeProperty());
                }

                pageNumberLabel.setText(Integer.toString(getIndex() + 1));
            }
        }
    }

}
