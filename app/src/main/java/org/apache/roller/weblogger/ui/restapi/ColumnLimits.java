package org.apache.roller.weblogger.ui.restapi;

/**
 * Column-width limits for the varchar columns this API's patchable string
 * fields land in, sourced from {@code bin/db/migrations/V002__baseline_schema
 * .sql} ({@code roller_user}/{@code weblog} table definitions). A value that
 * overflows one of these reaches JPA's INSERT/UPDATE and Postgres answers
 * "value too long for type character varying(N)", wrapped in a bare
 * {@code WebloggerException} -- an unguarded 500. Centralized here rather
 * than duplicated per controller, so a future migration that changes a
 * column's width has exactly one place to update and every {@code *Api}
 * controller checks the same numbers.
 *
 * <p>This is currently wired into {@code AdminApi}/{@code WeblogsApi} only
 * (review round 1, Important 2). The rest of {@code ui.restapi} has the same
 * gap and is recorded for the wave's whole-branch sweep, not fixed here.
 */
public final class ColumnLimits {

    private ColumnLimits() {
    }

    // roller_user (V002__baseline_schema.sql)
    public static final int USERNAME = 255;
    public static final int SCREEN_NAME = 255;
    public static final int FULL_NAME = 255;
    public static final int USER_EMAIL_ADDRESS = 255;

    // weblog (V002__baseline_schema.sql)
    public static final int WEBLOG_NAME = 255;
    public static final int TAGLINE = 255;
    public static final int WEBLOG_EMAIL_ADDRESS = 255;
    public static final int LOCALE = 20;
    public static final int TIME_ZONE = 50;

    /**
     * Throws a 400 {@link ApiException} naming {@code field} when
     * {@code value} exceeds {@code max} characters. A {@code null} value is
     * never rejected here -- whether null is itself acceptable (PATCH
     * "leave unchanged" vs. POST "required") is each caller's own business,
     * decided before or after this check as appropriate.
     */
    public static void requireMaxLength(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw ApiException.badRequest(
                    "'" + field + "' must be " + max + " characters or fewer.");
        }
    }
}
