-- Migration: alt text on media files, and the last of the gallery-sharing flag.
--
-- alt_text is nullable on purpose. NULL means "nobody has described this image
-- yet", which MediaFileView surfaces as a marker so the work is findable;
-- defaulting it to '' or to the filename would erase that distinction and
-- report every image as done.
--
-- is_public backed MediaFile.sharedForGallery, whose last reader (a checkbox
-- and the endpoint behind it) was deleted in W2. Any true values are discarded
-- deliberately: the flag has meant nothing since that wave.

ALTER TABLE roller_mediafile
    ADD COLUMN IF NOT EXISTS alt_text varchar(255);

ALTER TABLE roller_mediafile
    DROP COLUMN IF EXISTS is_public;
