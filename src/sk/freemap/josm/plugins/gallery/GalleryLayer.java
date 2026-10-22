package sk.freemap.josm.plugins.gallery;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.Action;
import javax.swing.Timer;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.ImageData;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.dialogs.layer.MergeAction;
import org.openstreetmap.josm.gui.layer.GpxLayer;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.MapViewPaintable.LayerPainter;
import org.openstreetmap.josm.gui.layer.MapViewPaintable.MapViewEvent;
import org.openstreetmap.josm.gui.layer.geoimage.CorrelateGpxWithImages;
import org.openstreetmap.josm.gui.layer.geoimage.EditImagesSequenceAction;
import org.openstreetmap.josm.gui.layer.geoimage.GeoImageLayer;
import org.openstreetmap.josm.gui.layer.geoimage.ImageEntry;
import org.openstreetmap.josm.gui.layer.geoimage.ImageViewerDialog;
import org.openstreetmap.josm.gui.layer.geoimage.ThumbsLoader;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;

import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

/**
 * Photo layer that reloads Freemap.sk gallery pictures for the visible area whenever the map view changes.
 */
public class GalleryLayer extends GeoImageLayer {

    private static final String API_URL_TEMPLATE = "https://backend.freemap.sk/gallery/pictures?by=bbox&bbox=%f,%f,%f,%f&fields=id&fields=user&fields=takenAt&fields=title";

    // Zoom 14, with some slack: JOSM opens an empty map at 10 m/px, which is zoom 13.93.
    private static final double MIN_ZOOM = 13.9;

    // Ground resolution of web mercator zoom 0 at the equator.
    private static final double ZOOM_0_METERS_PER_PIXEL = 156543.03;

    // A Freemap.sk access token with the gallery:read scope; gives premium users their premium photos.
    private static final String TOKEN_PREF = "freemap.gallery.token";

    private static final ExecutorService FETCH_EXECUTOR = Executors.newSingleThreadExecutor(r -> newDaemonThread(r, "freemap-gallery"));

    private static final ExecutorService THUMBS_EXECUTOR = Executors.newSingleThreadExecutor(r -> newDaemonThread(r, "freemap-gallery-thumbs"));

    private final Timer refreshTimer = new Timer(500, e -> refresh());

    // Bumped on every refresh so that responses for an outdated view are dropped.
    private volatile int generation;

    // Bumped whenever the photos needing thumbnails change, which stops the running thumbnail batch.
    private volatile int thumbsGeneration;

    private volatile HttpClient activeRequest;

    private volatile boolean destroyed;

    public GalleryLayer() {
        super(new ArrayList<>(), (GpxLayer) null, "Freemap Gallery");

        refreshTimer.setRepeats(false);

        addPropertyChangeListener(e -> {
            if (VISIBLE_PROP.equals(e.getPropertyName()) && isVisible()) {
                refreshTimer.restart();
            }
        });
    }

    static HttpClient createRequest(URL url) {
        HttpClient client = HttpClient.create(url);

        String token = Config.getPref().get(TOKEN_PREF, "").strip();

        // A header rather than ?authToken=, which JOSM would log with the URL.
        if (!token.isEmpty()) {
            client.setHeader("Authorization", "Bearer " + token);
        }

        return client;
    }

    private static Thread newDaemonThread(Runnable r, String name) {
        Thread thread = new Thread(r, name);
        thread.setDaemon(true);
        return thread;
    }

    @Override
    public LayerPainter attachToMapView(MapViewEvent event) {
        LayerPainter painter = super.attachToMapView(event);

        refreshTimer.restart();

        return painter;
    }

    @Override
    public void zoomChanged() {
        super.zoomChanged();

        if (!destroyed) {
            refreshTimer.restart();
        }
    }

    @Override
    public synchronized void destroy() {
        destroyed = true;

        generation++;

        refreshTimer.stop();

        cancelActiveRequest();

        super.destroy();
    }

    // Photos come and go with the view, so merging them into another layer, or correlating or deleting them, makes no sense.
    @Override
    public boolean isMergable(Layer other) {
        return false;
    }

    @Override
    public Action[] getMenuEntries() {
        return Arrays.stream(super.getMenuEntries())
                .filter(action -> !(action instanceof MergeAction || action instanceof CorrelateGpxWithImages
                        || action instanceof EditImagesSequenceAction || action instanceof LayerGpxExportAction))
                .toArray(Action[]::new);
    }

    // GeoImageLayer loads thumbnails only once per layer, so loading is done here, also for photos added later.
    @Override
    public void setUseThumbs(boolean useThumbs) {
        if (useThumbs) {
            // Keeps GeoImageLayer from starting its own loader.
            thumbsLoaded();
        }

        super.setUseThumbs(useThumbs);

        if (useThumbs) {
            loadThumbs();
        } else {
            thumbsGeneration++;
        }
    }

    // Loads one at a time so that each thumbnail shows up as soon as it is ready and an outdated batch stops early.
    private void loadThumbs() {
        int gen = ++thumbsGeneration;

        List<ImageEntry> missing = getImageData().getImages().stream().filter(entry -> !entry.hasThumbnail()).toList();

        if (missing.isEmpty()) {
            return;
        }

        THUMBS_EXECUTOR.execute(() -> {
            for (ImageEntry entry : missing) {
                if (gen != thumbsGeneration || destroyed || !isUseThumbs()) {
                    return;
                }

                if (!entry.hasThumbnail()) {
                    new ThumbsLoader(List.of(entry)).run();

                    GuiHelper.runInEDT(this::updateBufferAndRepaint);
                }
            }
        });
    }

    private void cancelActiveRequest() {
        HttpClient request = activeRequest;

        if (request != null) {
            // Http1Client.disconnect sleeps 100 ms, so keep it off the EDT.
            CompletableFuture.runAsync(request::disconnect);
        }
    }

    private void refresh() {
        if (destroyed || !isVisible() || !MainApplication.isDisplayingMapView()) {
            return;
        }

        int gen = ++generation;

        cancelActiveRequest();

        MapView mapView = MainApplication.getMap().mapView;

        if (getZoom(mapView) < MIN_ZOOM) {
            update(gen, List.of());
            return;
        }

        Bounds bounds = mapView.getProjection().getLatLonBoundsBox(mapView.getProjectionBounds());

        FETCH_EXECUTOR.execute(() -> {
            try {
                List<GalleryImageEntry> entries = fetch(bounds, gen);

                GuiHelper.runInEDT(() -> update(gen, entries));
            } catch (IOException | RuntimeException ex) {
                // A cancelled request fails too; that is expected.
                if (gen == generation) {
                    Logging.warn("Freemap Gallery: failed to fetch photos");
                    Logging.warn(ex);
                }
            } finally {
                activeRequest = null;
            }
        });
    }

    // Web mercator zoom level with the same ground resolution as the view; independent of the projection JOSM uses.
    private static double getZoom(MapView mapView) {
        double metersPerPixel = mapView.getDist100Pixel() / 100;

        double lat = mapView.getLatLon(mapView.getWidth() / 2, mapView.getHeight() / 2).lat();

        return Math.log(ZOOM_0_METERS_PER_PIXEL * Math.cos(Math.toRadians(lat)) / metersPerPixel) / Math.log(2);
    }

    private List<GalleryImageEntry> fetch(Bounds bounds, int gen) throws IOException {
        String url = String.format(Locale.US, API_URL_TEMPLATE, bounds.getMinLon(), bounds.getMinLat(),
                bounds.getMaxLon(), bounds.getMaxLat());

        HttpClient client = createRequest(URI.create(url).toURL());

        activeRequest = client;

        // Checked only after publishing the client: a racing refresh has then either bumped the generation or can cancel it.
        if (gen != generation) {
            return List.of();
        }

        try {
            HttpClient.Response response = client.connect();

            if (response.getResponseCode() != 200) {
                throw new IOException("HTTP " + response.getResponseCode() + " " + response.getResponseMessage());
            }

            try (JsonReader jsonReader = Json.createReader(response.getContentReader())) {
                List<GalleryImageEntry> entries = new ArrayList<>();

                for (JsonValue value : jsonReader.readArray()) {
                    try {
                        entries.add(parsePhoto(value.asJsonObject()));
                    } catch (RuntimeException ex) {
                        Logging.warn("Freemap Gallery: skipping invalid photo " + value);
                    }
                }

                return entries;
            }
        } finally {
            client.disconnect();
        }
    }

    private static GalleryImageEntry parsePhoto(JsonObject photo) {
        return new GalleryImageEntry(photo.getInt("id"), getNonBlankString(photo, "title"),
                getNonBlankString(photo, "user"), parseTakenAt(photo),
                new LatLon(photo.getJsonNumber("lat").doubleValue(), photo.getJsonNumber("lon").doubleValue()));
    }

    private static String getNonBlankString(JsonObject photo, String key) {
        return photo.get(key) instanceof JsonString s && !s.getString().isBlank() ? s.getString() : null;
    }

    private static Instant parseTakenAt(JsonObject photo) {
        var takenAt = photo.get("takenAt");

        if (takenAt instanceof JsonString s) {
            try {
                return Instant.parse(s.getString());
            } catch (DateTimeParseException ex) {
                return null;
            }
        }

        if (takenAt instanceof JsonNumber n) {
            return Instant.ofEpochSecond(n.longValue());
        }

        return null;
    }

    // Changes the data in place so already loaded thumbnails and the selection survive panning.
    private void update(int gen, List<GalleryImageEntry> entries) {
        if (gen != generation || destroyed) {
            return;
        }

        ImageData data = getImageData();

        List<ImageEntry> previouslySelected = data.getSelectedImages();

        // The open image viewer navigates from the selection, so keep it even when it leaves the view.
        boolean keepSelected = ImageViewerDialog.hasInstance() && ImageViewerDialog.getInstance().isDialogShowing();

        List<ImageEntry> selected = new ArrayList<>();

        Map<Integer, GalleryImageEntry> incoming = new LinkedHashMap<>();

        for (GalleryImageEntry entry : entries) {
            incoming.put(entry.getId(), entry);
        }

        for (ImageEntry image : new ArrayList<>(data.getImages())) {
            GalleryImageEntry fresh = incoming.get(((GalleryImageEntry) image).getId());

            boolean wasSelected = previouslySelected.contains(image);

            boolean unchanged = fresh != null && fresh.hasSameData((GalleryImageEntry) image);

            if (unchanged) {
                incoming.remove(fresh.getId());
            } else if (fresh != null || !wasSelected || !keepSelected) {
                data.removeImage(image, false);

                image = fresh;
            }

            if (wasSelected && image != null) {
                selected.add(image);
            }
        }

        if (!incoming.isEmpty()) {
            List<ImageEntry> added = new ArrayList<>(incoming.values());

            // ImageData has no public way to add entries other than merging another ImageData.
            data.mergeFrom(new ImageData(added));

            // The temporary ImageData claimed them; viewer navigation goes through the owning ImageData.
            added.forEach(image -> image.setDataSet(data));
        }

        if (isUseThumbs()) {
            loadThumbs();
        }

        // Selection is index based, so re-point it after the list changed.
        data.setSelectedImage(selected.isEmpty() ? null : selected.get(0));

        selected.stream().skip(1).forEach(data::addImageToSelection);

        data.notifyImageUpdate();

        invalidate();
    }
}
