/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package org.apache.roller.weblogger.business;

import java.io.InputStream;
import java.sql.Timestamp;
import java.util.Date;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifDirectoryBase;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;

/**
 * Reads camera/lens/exposure/GPS metadata from an uploaded image using
 * metadata-extractor. Called once per create/update from a freshly re-opened
 * stream on the file already saved to disk (see JPAMediaFileManagerImpl#updateThumbnail)
 * rather than the original upload stream, which storage has already consumed.
 *
 * <p>Unsupported formats, images with no EXIF block at all, and any parsing
 * error all produce an {@link ExifData} of all-null fields -- extraction
 * failure is never a reason to fail an upload.
 */
public final class ExifSupport {

    private static final Log log = LogFactory.getFactory().getInstance(ExifSupport.class);

    private ExifSupport() {
    }

    /** Immutable holder for whatever EXIF/GPS fields could be read; every field may be null. */
    public static final class ExifData {
        public final String camera;
        public final String lens;
        public final String exposure;
        public final String aperture;
        public final Integer iso;
        public final String focalLength;
        public final Timestamp taken;
        public final Double gpsLatitude;
        public final Double gpsLongitude;

        ExifData(String camera, String lens, String exposure, String aperture, Integer iso,
                String focalLength, Timestamp taken, Double gpsLatitude, Double gpsLongitude) {
            this.camera = camera;
            this.lens = lens;
            this.exposure = exposure;
            this.aperture = aperture;
            this.iso = iso;
            this.focalLength = focalLength;
            this.taken = taken;
            this.gpsLatitude = gpsLatitude;
            this.gpsLongitude = gpsLongitude;
        }

        public static final ExifData EMPTY =
                new ExifData(null, null, null, null, null, null, null, null, null);
    }

    /**
     * Extracts whatever EXIF/GPS fields are present in {@code is}. Never
     * throws: any exception (unreadable format, corrupt EXIF block, I/O
     * error) is logged at DEBUG and produces {@link ExifData#EMPTY}.
     */
    public static ExifData extract(InputStream is) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(is);

            String camera = null;
            String lens = null;
            String exposure = null;
            String aperture = null;
            Integer iso = null;
            String focalLength = null;
            Timestamp taken = null;

            ExifIFD0Directory ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
            if (ifd0 != null) {
                camera = combineMakeAndModel(ifd0.getString(ExifDirectoryBase.TAG_MAKE),
                        ifd0.getString(ExifDirectoryBase.TAG_MODEL));
            }

            ExifSubIFDDirectory subIfd = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (subIfd != null) {
                lens = nullIfBlank(subIfd.getString(ExifDirectoryBase.TAG_LENS_MODEL));
                exposure = nullIfBlank(subIfd.getDescription(ExifDirectoryBase.TAG_EXPOSURE_TIME));
                aperture = nullIfBlank(subIfd.getDescription(ExifDirectoryBase.TAG_FNUMBER));
                iso = subIfd.getInteger(ExifDirectoryBase.TAG_ISO_EQUIVALENT);
                focalLength = nullIfBlank(subIfd.getDescription(ExifDirectoryBase.TAG_FOCAL_LENGTH));
                // EXIF's DateTimeOriginal (0x9003) is a bare "YYYY:MM:DD HH:MM:SS"
                // string with no timezone/offset of its own -- the camera simply
                // doesn't record one. getDate(int) with no TimeZone argument has
                // metadata-extractor parse it in the JVM's default zone, which is
                // an approximation (the photo may have been taken somewhere else)
                // but matches how every other timestamp in this codebase is
                // handled absent better information. EXIF 2.31+ can carry a
                // separate OffsetTimeOriginal (0x9011) tag with the true UTC
                // offset; a future refinement could consult it when present and
                // fall back to this approximation only when it's absent.
                Date takenDate = subIfd.getDate(ExifDirectoryBase.TAG_DATETIME_ORIGINAL);
                if (takenDate != null) {
                    taken = new Timestamp(takenDate.getTime());
                }
            }

            Double gpsLatitude = null;
            Double gpsLongitude = null;
            GpsDirectory gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory.class);
            if (gpsDir != null) {
                GeoLocation location = gpsDir.getGeoLocation();
                if (location != null && !location.isZero()) {
                    gpsLatitude = location.getLatitude();
                    gpsLongitude = location.getLongitude();
                }
            }

            return new ExifData(camera, lens, exposure, aperture, iso, focalLength, taken,
                    gpsLatitude, gpsLongitude);
        } catch (Exception e) {
            // Not every upload is a format metadata-extractor understands (or has EXIF
            // at all) -- that is the normal case, not a failure worth surfacing.
            log.debug("No EXIF metadata extracted", e);
            return ExifData.EMPTY;
        }
    }

    /**
     * Combines EXIF Make/Model into one display string, e.g. "Canon" + "Canon EOS R5"
     * to "Canon EOS R5" (avoiding the duplicated make many cameras embed in Model),
     * or "Canon" + "EOS R5" to "Canon EOS R5". Returns null if both are blank.
     */
    private static String combineMakeAndModel(String make, String model) {
        make = nullIfBlank(make);
        model = nullIfBlank(model);
        if (make == null) {
            return model;
        }
        if (model == null) {
            return make;
        }
        if (model.toLowerCase().startsWith(make.toLowerCase())) {
            return model;
        }
        return make + " " + model;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
