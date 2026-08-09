# Roller Design System — "Quiet Instrument"

This is the committed reference copy of the design spec used to build the
admin UI's preview cards and, from there, the shipped tokens/components. The
visual source of truth is the companion claude.ai/design project ("Quiet
Instrument") where the preview cards themselves live and render; this file is
the spec text that project and this repository both build from, kept here so
implementation tasks have something to diff against without leaving the repo.

---

# Roller Design System — "Quiet Instrument" — build spec for preview cards

Client decisions (locked): quiet tool personality; teal accent from the travel theme; light AND dark; one project.

## Tokens (exact values — every card derives from these, no other colors)

Light: --paper:#F7F9F9 --surface:#FFFFFF --ink:#17262A --ink-soft:#5A6E72 --line:#DCE4E4
       --accent:#0F6E68 --accent-quiet:#E3F0EE --good:#2F7D4F --warn:#9A6A1F --bad:#A33B2E --focus:#2AA198
Dark:  --paper:#131C1C --surface:#1A2626 --ink:#DCE7E5 --ink-soft:#8FA5A2 --line:#2A3838
       --accent:#4FB3AA --accent-quiet:#1E3230 --good:#6FBF8F --warn:#D0A45C --bad:#D0705F --focus:#2AA198

Radius: 6px (cards/inputs), 99px (pills). Shadows: none in light except overlay contexts; rely on --line borders. Spacing scale: 4/8/12/16/24/32.

## Type

- UI + headings: "IBM Plex Sans", system-ui fallback. Weights: 450 body, 600 headings/labels.
- Data (slugs, dates, counts, ids): "IBM Plex Mono", ui-monospace fallback, font-variant-numeric: tabular-nums.
- Scale: 12px caps-labels (letter-spacing .08em, uppercase, --ink-soft), 14.5px/1.55 body, 16px section heads, 20px page titles (600), 28px reserved.
- Card/section headers (`.card-header`, `.section-head` — accordion panel titles, settings-form "display group" headers) use the caps-label role, not an h3/h4's own default size — a heading label, not a second page title.
- Preview cards may load Plex from Google Fonts <link> ONLY IF the design pane allows external fetches — safer: use font-family:"IBM Plex Sans", system-ui and accept fallback rendering in cards; note in the tokens card that the implementation self-hosts Plex as a webjar.

## Signature moves

1. THE SPINE: active nav item in the rail = 2px inset-left rule in --accent + --accent-quiet background + 600 weight. Nothing else in the rail is colored.
2. THE RAIL (replaces the "Powered by Apache Roller" card): 232px sidebar; top block = weblog context (weblog name 600, handle in mono, small status dot --good "Live"); below: tool groups under caps-labels (CONTENT: Entries, Pages, Media, Comments, Inquiries / DESIGN: Theme, Stylesheet, Templates / SETTINGS: Weblog, Members, Maintenance). Footer of rail: user + Sign out, quiet.
3. EMPTY STATES AS INVITATIONS: icon-free; one 16px 600 line ("No entries yet."), one --ink-soft sentence ("Your first post starts the archive."), one primary button ("Write an entry"). Never a bare table strip.

## Card format (every file)

- First line EXACTLY: <!-- @dsCard group="GROUP" name="NAME" subtitle="SUBTITLE" -->
- Self-contained HTML: inline <style>, no external requests (fonts get family names + fallbacks only).
- Body = two panels side by side (flex, gap 24, wrap): .panel--light and .panel--dark, each a rounded container painting its OWN --paper and carrying its full token set via scoped CSS custom properties (define both sets as classes; do NOT rely on prefers-color-scheme — the card must show both at once regardless of viewer theme).
- Each panel labeled "Light" / "Dark" in caps-label style.
- Realistic Roller content ONLY (weblog "Coastal Guides", handle coastal-guides; entries like "Field Notes from the Coast", "Harbor Cottage Guide"; pages About/Contact; inquiry from "Maren Vole <maren@example.com>"). No lorem.

## Files to produce in this directory (12)

1. tokens-colors.html      group="Foundations" — both palettes as labeled swatch grids (name, hex, usage note per token).
2. tokens-type.html        group="Foundations" — type specimen: caps-label, body para, section head, page title, mono data row; the self-hosting note.
3. tokens-spacing.html     group="Foundations" — spacing/radius demo: a card anatomy diagram with measurements.
4. shell-admin.html        group="Shell" — FULL admin frame at ~1100px wide per panel (allowed to stack panels vertically for room): top bar (product name left tiny; weblog switcher center-left; user right), the RAIL with spine on "Entries", content area with page header ("Entries" title + "Write an entry" primary button right) over a table placeholder. This is the hero card.
5. components-buttons.html group="Components" — primary (accent bg), secondary (line border, ink text), destructive (bad, outline until hover), disabled, small size; focus ring state shown on one.
6. components-pills.html   group="Components" — status pills: Published(good tint)/Draft(warn tint)/Pending(accent-quiet)/Scheduled(line tint + mono date); plus counts style.
7. components-nav-rail.html group="Components" — the rail alone, all three groups, spine on Pages, hover state on one item.
8. forms-sections.html     group="Forms" — a Weblog Settings excerpt: two grouped sections ("General", "Newsletter") each with 2-3 fields; labels ABOVE inputs (kill the label-column gutter), help text under, one field showing an inline validation error (bad text + border, message "That is not a list UUID. Copy it exactly from Listmonk.").
9. tables-list.html        group="Tables" — entries table: checkbox col, title (link, 600) + mono slug under, status pill, category, mono date, row hover; header row caps-labels; selection bar variant shown above (2 selected · Delete).
10. tables-empty-states.html group="Tables" — three empty states per the signature: Entries, Inquiries ("No inquiries yet." / "Messages from your contact form land here." / no button), Media ("Add a photo" button).
11. public-travel-page.html  group="Public themes" — travel-theme _page treatment: teal guide-card header (site title, nav All/General/About), page title, prose, and the CONTACT FORM styled to travel's identity (teal accents, card fields). Single light panel only (travel is a light theme) — note says so.
12. public-portfolio.html    group="Public themes" — two stacked sections, dark only (portfolio is dark by identity): (a) _page treatment: header + prose + contact form on near-black; (b) the no-image entry card: grid card with generated placeholder (title set large in Plex over a subtle teal-to-transparent linear-gradient wash + mono date) next to the current empty-box for contrast, labeled "before / after".

## Shipped reference cards: journal + front door (Theme Wave)

Unlike the twelve preview cards above (which live in the companion design
project / `.superpowers/design`, not committed), the `journal` theme and the
`frontpage` restyle shipped their reference cards into the repo, under
`docs/design/journal/`, because they were built to spec alongside the theme
templates rather than before them:

- `journal-home.html` — group="Quiet Journal" — the reading-first entry
  list: date marginalia, serif `qj-title`s, teal hover spine.
- `journal-permalink.html` — group="Quiet Journal" — the reading view: serif
  prose at reading measure, quiet comments.
- `journal-page.html` — group="Quiet Journal" — the `_page` treatment: About
  prose plus the contact form in journal dress.
- `frontpage-front-door.html` — group="Frontpage" — the aggregator: recent
  posts across weblogs plus the teal-wash weblog directory.

Same `@dsCard` header convention and token/type rules as the cards above;
treat these as the worked examples for any future theme's reference cards
that need to live in-repo rather than in the design project.

## Quality bar

Focus states visible; text-wrap balance on headings; tabular-nums wherever digits column; no horizontal scroll inside a panel; every color from the token set; both panels legible standalone.
