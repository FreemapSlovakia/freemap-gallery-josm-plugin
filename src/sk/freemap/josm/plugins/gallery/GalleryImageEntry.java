package sk.freemap.josm.plugins.gallery;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.gui.layer.geoimage.ImageEntry;

public class GalleryImageEntry extends ImageEntry {
    private static final String PHOTO_URL_TEMPLATE = "https://backend.freemap.sk/gallery/pictures/%d/image";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final int id;

    private final String user;

    private final String title;

    GalleryImageEntry(int id, String title, String user, Instant takenAt, LatLon latLon) {
        this.id = id;
        this.title = title;
        this.user = user;

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

    @Override
    public String getDisplayName() {
        Instant takenAt = getExifInstant();

        return (title == null ? "Photo" : title) + (user == null ? "" : " by " + user)
                + (takenAt == null ? "" : " taken at " + DATE_FORMAT.format(takenAt));
    }

    boolean hasSameData(GalleryImageEntry other) {
        return Objects.equals(title, other.title) && Objects.equals(user, other.user)
                && Objects.equals(getExifInstant(), other.getExifInstant()) && Objects.equals(getPos(), other.getPos());
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
