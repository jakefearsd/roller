package org.apache.roller.weblogger.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;
import org.apache.roller.weblogger.util.RollerMessages;

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

    /**
     * One uploaded file's outcome. {@code file} is populated only when
     * {@code status} is {@code "created"} -- a refused file was never
     * persisted, so there is nothing to return a view of.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UploadResult(String fileName, String status, String detail, MediaView file) {
    }

    /**
     * The whole batch's outcome. {@code created}/{@code failed} are always
     * derived from {@code results} by {@link #summarise} rather than
     * threaded through independently, so they can never drift out of sync
     * with the list a caller would count by hand.
     */
    public record UploadResponse(List<UploadResult> results, int created, int failed) {
    }

    public static UploadResponse summarise(List<UploadResult> results) {
        int created = (int) results.stream().filter(r -> "created".equals(r.status())).count();
        return new UploadResponse(results, created, results.size() - created);
    }

    /**
     * Reads the newest (most recently added) error out of {@code messages}
     * -- the one {@code createMediaFile}/{@code canSave} just added for
     * THIS file -- and maps its key onto one of the three refusal statuses
     * the API promises. {@code FileContentManagerImpl.canSave} is the only
     * source of these keys today: {@code error.upload.dirmax} (the
     * weblog's total quota) and {@code error.upload.filemax} (the per-file
     * size cap) are both a size limit from the caller's point of view, so
     * both map to {@code "quota_exceeded"}; {@code error.upload.forbiddenFile}
     * maps to {@code "forbidden_extension"}; anything else (chiefly
     * {@code error.upload.disabled}, and defensively any key this method
     * has never seen) is {@code "error"}.
     */
    public static UploadResult refusal(String fileName, RollerMessages messages) {
        RollerMessages.RollerMessage last = null;
        Iterator<RollerMessages.RollerMessage> it = messages.getErrors();
        while (it.hasNext()) {
            last = it.next();
        }
        if (last == null) {
            return new UploadResult(fileName, "error", "Upload failed.", null);
        }
        String status = switch (last.getKey()) {
            case "error.upload.dirmax", "error.upload.filemax" -> "quota_exceeded";
            case "error.upload.forbiddenFile" -> "forbidden_extension";
            default -> "error";
        };
        return new UploadResult(fileName, status, detailFor(last), null);
    }

    private static String detailFor(RollerMessages.RollerMessage msg) {
        String[] args = msg.getArgs();
        return switch (msg.getKey()) {
            case "error.upload.dirmax" -> "Adding this file would exceed this weblog's "
                    + (args != null && args.length > 0 ? args[0] : "configured") + " MB storage limit.";
            case "error.upload.filemax" -> "This file is larger than the "
                    + (args != null && args.length > 1 ? args[1] : "configured") + " MB per-file limit.";
            case "error.upload.forbiddenFile" -> "Files of this type may not be uploaded.";
            case "error.upload.disabled" -> "File upload is disabled for this site.";
            default -> "Upload failed: " + msg.getKey();
        };
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
            ColumnLimits.requireMaxLength("altText", patch.altText(), ColumnLimits.MEDIA_ALT_TEXT);
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
            ColumnLimits.requireMaxLength("name", name, ColumnLimits.MEDIA_NAME);
            file.setName(name);
        }
    }
}
