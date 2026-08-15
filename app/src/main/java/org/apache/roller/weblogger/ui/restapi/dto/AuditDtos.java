package org.apache.roller.weblogger.ui.restapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.MediaFileDirectory;
import org.apache.roller.weblogger.pojos.WeblogEntry;

/**
 * Views for the SEO and media audit endpoints. The whole point of these
 * endpoints is that they report the same thing the renderer and the admin
 * UI's own markers consider missing -- see {@link #gapsFor} and {@link
 * MediaDtos#isAltTextMissing}, both keyed off {@code StringUtils.isBlank}
 * rather than {@code == null} or EL's notion of {@code empty}.
 */
public final class AuditDtos {

    private AuditDtos() {
    }

    /** One entry's SEO gaps, and the fields an agent needs to go fix them. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SeoGap(String entryId, String anchor, String title, List<String> gaps) {
    }

    /**
     * {@code total} and {@code counts} describe every gappy entry the
     * search turned up; {@code entries} is only the requested page -- an
     * agent asking for totals does not have to page through everything to
     * get them.
     */
    public record SeoAudit(int total, Map<String, Integer> counts, List<SeoGap> entries) {
    }

    /**
     * {@code directory} is the owning directory's NAME, not its id --
     * unlike {@code MediaDtos.MediaView.directory}. An audit item is a
     * human/agent work-list entry, not a round-trippable write payload (the
     * fix goes through {@code mediaId} via {@code MediaApi.update}), so the
     * readable name is more useful here than an id the caller would have to
     * look up again. Null for a file not filed in any directory.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaGap(String mediaId, String name, String directory) {
    }

    public record MediaAudit(int missingAltText, List<MediaGap> items) {
    }

    /**
     * The SEO gaps for a single entry, in a stable order. Every field check
     * is {@code StringUtils.isBlank}, matching the renderer's own
     * isNotBlank-gated fallbacks (search description, meta title, featured
     * image) rather than {@code == null} -- a whitespace-only value must
     * report as missing here exactly as it does everywhere else, or the
     * audit would quietly disagree with reality. {@code noindex} is the one
     * gap that is not a blank-string check: it fires only when the entry is
     * explicitly opted out of search, {@code Boolean.TRUE.equals(...)}
     * rather than a null check, since {@code noindex} defaults to
     * {@code Boolean.FALSE} and null must not be mistaken for "opted out".
     */
    public static List<String> gapsFor(WeblogEntry entry) {
        List<String> gaps = new ArrayList<>();
        if (StringUtils.isBlank(entry.getSearchDescription())) {
            gaps.add("missing_search_description");
        }
        if (StringUtils.isBlank(entry.getMetaTitle())) {
            gaps.add("missing_meta_title");
        }
        if (StringUtils.isBlank(entry.getFeaturedImageId())) {
            gaps.add("missing_featured_image");
        }
        if (Boolean.TRUE.equals(entry.getNoindex())) {
            gaps.add("noindex");
        }
        return gaps;
    }

    public static SeoGap toSeoGap(WeblogEntry entry) {
        return new SeoGap(entry.getId(), entry.getAnchor(), entry.getTitle(), gapsFor(entry));
    }

    public static MediaGap toMediaGap(MediaFile file) {
        MediaFileDirectory directory = file.getDirectory();
        return new MediaGap(file.getId(), file.getName(), directory == null ? null : directory.getName());
    }
}
