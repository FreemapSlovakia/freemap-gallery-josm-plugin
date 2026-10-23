package sk.freemap.josm.plugins.gallery;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.Objects;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.layer.geoimage.ImageEntry;
import org.openstreetmap.josm.gui.layer.geoimage.ImageUtils;
import org.openstreetmap.josm.tools.HttpClient;
import org.openstreetmap.josm.tools.Logging;

public class GalleryImageEntry extends ImageEntry {
    private static final String PHOTO_URL_TEMPLATE = "https://backend.freemap.sk/gallery/pictures/%d/image";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final int id;

    private final String user;

    private final String title;

    // Kept apart from getPos(): its CachedLatLon also compares the lazily cached east/north in equals.
    private final LatLon latLon;

    // Set when the backend refused the photo as premium; shown in the viewer's error message.
    private volatile boolean premiumRefused;

    GalleryImageEntry(int id, String title, String user, Instant takenAt, LatLon latLon) {
        this.id = id;
        this.title = title;
        this.user = user;
        this.latLon = latLon;

        setPos(latLon);

        if (takenAt != null) {
            setExifTime(takenAt);
        }
    }

    int getId() {
        return id;
    }

    @Override
    protected URL getImageUrl() throws MalformedURLException {
        return getImageURI().toURL();
    }

    @Override
    public URI getImageURI() {
        return URI.create(String.format(PHOTO_URL_TEMPLATE, id));
    }

    // Downloads the photo itself, as JOSM's own loader cannot send the access token.
    @Override
    public BufferedImage read(Dimension target) throws IOException {
        HttpClient.Response response = GalleryLayer.createRequest(getImageUrl()).connect();

        int code = response.getResponseCode();

        premiumRefused = code == 402;

        // Returning null rather than throwing: on an exception the viewer keeps showing "Loading" instead of the error.
        if (code != 200) {
            response.disconnect();

            Logging.warn("Freemap Gallery: unable to load photo " + id + ": HTTP " + code + " "
                    + response.getResponseMessage());

            return null;
        }

        // Closing the stream instead of disconnecting keeps the connection alive for the next photo.
        try (InputStream content = response.getContent();
                ImageInputStream input = ImageIO.createImageInputStream(content)) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);

            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format");
            }

            ImageReader reader = readers.next();

            try {
                reader.setInput(input, true, true);

                ImageReadParam param = target == null ? reader.getDefaultReadParam()
                        : ImageUtils.withSubsampling(reader, target);

                return ImageUtils.applyExifRotation(reader.read(0, param), getExifOrientation());
            } finally {
                reader.dispose();
            }
        }
    }

    @Override
    public String getDisplayName() {
        Instant takenAt = getExifInstant();

        return (title == null ? "Photo" : title) + (user == null ? "" : " by " + user)
                + (takenAt == null ? "" : " taken at " + DATE_FORMAT.format(takenAt))
                + (premiumRefused ? " (premium photo; set freemap.gallery.token to a Freemap.sk access token)" : "");
    }

    boolean hasSameData(GalleryImageEntry other) {
        return Objects.equals(title, other.title) && Objects.equals(user, other.user)
                && Objects.equals(getExifInstant(), other.getExifInstant()) && Objects.equals(latLon, other.latLon);
    }

    @Override
    public String toString() {
        return "Image:" + id;
    }

    // The inherited equals compares mutable state such as the loaded thumbnail.
    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof GalleryImageEntry other && id == other.id;
    }

    // ImageData.mergeFrom de-duplicates by file and ThumbsLoader keys its cache by it, so it must be non-null and unique.
    @Override
    public File getFile() {
        return new File("freemap-gallery", id + ".jpg");
    }

    // Photos are reloaded with the view, so removing one from the layer would not last.
    @Override
    public boolean isRemoveSupported() {
        return false;
    }

    @Override
    public boolean isDeleteSupported() {
        return false;
    }
}
