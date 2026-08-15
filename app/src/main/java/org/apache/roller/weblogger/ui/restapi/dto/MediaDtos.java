package org.apache.roller.weblogger.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.ui.restapi.ApiException;

/**
 * Views of a media file and a media file directory, for the automation API.
 */
public final class MediaDtos {

    private MediaDtos() {
    }

    /**
     * {@code directory} is the owning directory's id, not its name -- unlike
     * {@code EntryDtos.EntryView.category}, which is a name because
     * {@code EntryDtos.EntryWrite.category} is also a name. Here the write
     * side ({@link MediaPatch#directoryId}) is explicitly an id, so the read
     * side matches it: a client can round-trip what it read straight back
     * into a patch without a second lookup.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaView(
            String id, String name, String altText, String contentType, long length,
            int width, int height, String directory, Double focalX, Double focalY,
            String url, String blurhash) {
    }

    /**
     * A partial update. Every component is nullable and null means ABSENT,
     * not "clear this" -- with one deliberate exception: an explicitly empty
     * {@code altText} ("") IS a real value, because an author who cleared
     * the field did that on purpose (see {@link #applyPatch}).
     * {@code directoryId} is not applied by {@link #applyPatch} -- moving a
     * file needs an ownership-checked directory lookup this pure mapper has
     * no business making, so the controller applies it after an explicit
     * lookup of its own.
     */
    public record MediaPatch(String altText, Double focalX, Double focalY, String directoryId, String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DirectoryView(String id, String name, String description, boolean isPrivate, int fileCount) {
    }

    /**
     * The body of a directory create request. {@code description} is
     * optional; {@code name} is required, enforced by {@code MediaApi}, not
     * this record.
     */
    public record DirectoryWrite(String name, String description) {
    }

    public static MediaView toView(MediaFile file, String url) {
        MediaFileDirectory directory = file.getDirectory();
        return new MediaView(
                file.getId(),
                file.getName(),
                file.getAltText(),
                file.getContentType(),
                file.getLength(),
                file.getWidth(),
                file.getHeight(),
                directory == null ? null : directory.getId(),
                file.getFocalX(),
                file.getFocalY(),
                url,
                file.getBlurhash());
    }

    public static DirectoryView toView(MediaFileDirectory directory) {
        return new DirectoryView(
                directory.getId(),
                directory.getName(),
                directory.getDescription(),
                directory.isPrivate(),
                directory.getMediaFiles().size());
    }

    /**
     * Whitespace-only counts as missing, matching the renderer's fallback to
     * the filename and the admin UI's own marker ({@code MediaFileView.jsp}
     * uses {@code fn:trim}) -- deliberately NOT EL's notion of {@code empty},
     * which would report a whitespace-only value as "described". Task 14's
     * media audit endpoint consumes this exact definition, so there is
     * exactly one definition of "missing" for both to share.
     */
    public static boolean isAltTextMissing(MediaFile file) {
        return StringUtils.isBlank(file.getAltText());
    }

    /**
     * Applies every field {@code patch} actually carries onto {@code file}.
     * {@code altText}, {@code focalX} and {@code focalY} are plain field
     * assignments; {@code directoryId} is deliberately left untouched here --
     * see {@link MediaPatch}'s javadoc. {@code name} is null-means-absent
     * like the others, but a PRESENT, blank value is rejected rather than
     * stored -- unlike {@code altText}, where an explicit empty string is a
     * real "the author cleared it" value, there is no equivalent "the file
     * is deliberately unnamed" concept a blank name could mean, only a
     * silently broken one.
     */
    public static void applyPatch(MediaFile file, MediaPatch patch) {
        if (patch.altText() != null) {
            file.setAltText(patch.altText());
        }
        if (patch.focalX() != null) {
            file.setFocalX(patch.focalX());
        }
        if (patch.focalY() != null) {
            file.setFocalY(patch.focalY());
        }
        if (patch.name() != null) {
            String name = patch.name().trim();
            if (name.isEmpty()) {
                throw ApiException.badRequest("name cannot be blank.");
            }
            file.setName(name);
        }
    }
}
