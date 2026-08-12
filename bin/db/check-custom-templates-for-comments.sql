-- Pre-deploy check for the W1 comment-removal wave.
--
-- W1 deleted the comment subsystem. Shipped themes were swept and are clean,
-- but a weblog running a CUSTOM theme stores its templates as rows in this
-- database, where no test or compiler can see them. Velocity in this codebase
-- is lenient (no runtime.references.strict, runtime.log.invalid.reference=false),
-- so a custom template still calling a deleted member does not fail -- it
-- prints the literal text "$entry.commentCount" onto the public page, silently.
--
-- Run this against production BEFORE deploying W1:
--   docker compose exec postgres psql -U roller -d rollerdb \
--     -f /path/to/check-custom-templates-for-comments.sql
--
-- Zero rows from every query below means nothing to do.

\echo '== 1. Weblogs running a custom theme (only these can be affected) =='
SELECT handle, name, editortheme
FROM weblog
WHERE editortheme = 'custom'
ORDER BY handle;

\echo ''
\echo '== 2. Custom templates referencing a DELETED member or file =='
-- Each pattern is something W1 removed. A hit is a template that will render
-- broken text (or 404 a stylesheet) after the deploy.
SELECT w.handle,
       t.name         AS template_name,
       t.action,
       r.templatelang,
       -- which pattern matched, so you know what to edit
       concat_ws(', ',
         CASE WHEN r.template ILIKE '%showWeblogEntryComments%'    THEN '#showWeblogEntryComments'    END,
         CASE WHEN r.template ILIKE '%showMobileWeblogEntryComments%' THEN '#showMobileWeblogEntryComments' END,
         CASE WHEN r.template ILIKE '%showWeblogEntryCommentForm%' THEN '#showWeblogEntryCommentForm' END,
         CASE WHEN r.template ILIKE '%commentCount%'               THEN '$…commentCount'             END,
         CASE WHEN r.template ILIKE '%commentsStillAllowed%'       THEN '$entry.commentsStillAllowed' END,
         CASE WHEN r.template ILIKE '%recentComments%'             THEN '$weblog.recentComments'      END,
         CASE WHEN r.template ILIKE '%feed.comments%'              THEN '$url.feed.comments.*'        END,
         CASE WHEN r.template ILIKE '%url.comment%'                THEN '$url.comment(s)'             END,
         CASE WHEN r.template ILIKE '%commentAuthenticator%'       THEN '$url.commentAuthenticator'   END,
         CASE WHEN r.template ILIKE '%config.commentHtmlAllowed%'  THEN '$config.commentHtmlAllowed'  END,
         CASE WHEN r.template ILIKE '%config.commentEscapeHtml%'   THEN '$config.commentEscapeHtml'   END,
         CASE WHEN r.template ILIKE '%config.commentEmailNotify%'  THEN '$config.commentEmailNotify'  END,
         CASE WHEN r.template ILIKE '%config.commentAutoFormat%'   THEN '$config.commentAutoFormat'   END,
         CASE WHEN r.template ILIKE '%getMostCommentedWeblogs%'    THEN '$site.getMostCommentedWeblogs' END,
         CASE WHEN r.template ILIKE '%themes/base.css%'            THEN 'themes/base.css (now 404)'   END,
         CASE WHEN r.template ILIKE '%popupcomments%'              THEN '_popupcomments'              END,
         CASE WHEN r.template ILIKE '%openSearch%'                 THEN '$url.openSearch* (pre-existing)' END
       ) AS broken_references
FROM custom_template_rendition r
JOIN weblog_custom_template t ON t.id = r.templateid
JOIN weblog w                 ON w.id = t.websiteid
WHERE r.template ILIKE ANY (ARRAY[
        '%showWeblogEntryComments%', '%showMobileWeblogEntryComments%',
        '%showWeblogEntryCommentForm%', '%commentCount%',
        '%commentsStillAllowed%', '%recentComments%',
        '%feed.comments%', '%url.comment%', '%commentAuthenticator%',
        '%config.commentHtmlAllowed%', '%config.commentEscapeHtml%',
        '%config.commentEmailNotify%', '%config.commentAutoFormat%',
        '%getMostCommentedWeblogs%', '%themes/base.css%',
        '%popupcomments%', '%openSearch%'
      ])
ORDER BY w.handle, t.name;

\echo ''
\echo '== 3. Broad net: any custom template mentioning "comment" at all =='
-- Wider than query 2 on purpose. Catches spellings the pattern list missed,
-- including a theme author''s own macros. Prose in a ## Velocity comment is a
-- false positive -- read the excerpt before acting.
SELECT w.handle,
       t.name AS template_name,
       substring(r.template from '(?i).{0,60}comment.{0,60}') AS excerpt
FROM custom_template_rendition r
JOIN weblog_custom_template t ON t.id = r.templateid
JOIN weblog w                 ON w.id = t.websiteid
WHERE r.template ILIKE '%comment%'
ORDER BY w.handle, t.name;
