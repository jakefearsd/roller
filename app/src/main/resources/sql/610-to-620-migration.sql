-- Migration script: 6.1.0 to 6.2.0
-- Removes tables for features that were deleted:
--   Planet Aggregator, Bookmarks/Blogroll, Pings/Trackbacks

-- Drop foreign key constraints first (order matters)

-- Bookmarks
alter table bookmark drop constraint bm_folderid_fk;
alter table bookmark_folder drop constraint fo_weblogid_fk;

-- Pings
alter table autoping drop constraint ap_weblogid_fk;
alter table autoping drop constraint ap_pingtargetid_fk;

-- Drop tables (order: children before parents)

-- Ping tables
drop table if exists pingqueueentry;
drop table if exists autoping;
drop table if exists pingtarget;

-- Bookmark tables
drop table if exists bookmark;
drop table if exists bookmark_folder;

-- Planet tables
drop table if exists rag_entry;
drop table if exists rag_group_subscription;
drop table if exists rag_subscription;
drop table if exists rag_group;
drop table if exists rag_planet;
drop table if exists rag_properties;
