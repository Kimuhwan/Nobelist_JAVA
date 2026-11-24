# Scientific Lens Harness — Workflow Bench

## Nobellist council opening

The first launch screen uses the original web edition's council-room illustration and
four title translations from `Kimuhwan/Nobellist` (web checkout `d94e25f`,
`web/static/index.html`, `app.js` and `styles.css`). The illustration and its
MIT notice travel inside the desktop JAR. The title, prompt, language selector and
window controls are native JavaFX controls layered over the artwork.

At 1280 × 860 the web title group is centered at 39% of the viewport height,
the prompt sits 8% above the bottom and the language selector is inset 24 px.
Preserve centered cover cropping, warm ink (#2d2218), 76 px title, 18 px subtitle
and the translucent dark language tray. Native window controls occupy the opposite
corner. The separate motion toggle is accessible on the first screen.

The heading uses the four titles' glyphs from Noto Sans CJK KR Black. The Chinese
controls use a subset of Noto Sans CJK SC Bold. Both opening faces are bundled
under the OFL notice in `fonts/OFL.txt`. Their source is `notofonts/noto-cjk`,
revision `f8d157532fbfaeda587e826d4cd5b21a49186f7c`.

Use awesome-ai-motion's Fade Slide and Stagger for the title entrance (12 px,
500 ms, 60 ms offsets). Once settled, hold until activation. The web edition's
centered iris closes over the actual question composer in 920 ms. A pointer
shifts the art by up to 5 × 4 px. Reduced motion shows the final title immediately
and goes straight to the composer. Click, Enter and Space enter once. Language
and window controls stay independent. Returning home retains the current draft
and result and goes directly to the composer.

## 1. Design direction and reference

The selected user screenshot is the visual contract: a light desktop workbench,
top question/mode/actions toolbar, four connected workflow stages, a wide
hypothesis/experiment column, a narrower assumption/red-team column, and a
four-part evaluation band. This is a native JavaFX implementation; browser,
React and Lighthouse tooling do not apply.

Primary user: an idea owner who needs an actionable first experiment. The reading
order is hypothesis → weakest assumption and critique → experiment → data,
success/failure criteria and next decision. Scientist names remain methodological
lenses. Results are proposals to test, never proof or historical impersonation.

## 2. Color tokens

JavaFX looked-up colors in `app.css` are the single runtime palette.

| Token | Value | Purpose |
|---|---|---|
| `-c-primary` | `#1764d8` | Primary action, selected stage, outline icons |
| `-c-primary-dark` | `#1454b7` | Hover/pressed action |
| `-c-primary-soft` | `#eef5ff` | Selected surfaces |
| `-c-text` | `#12223f` | Main text |
| `-c-muted` | `#60728e` | Supporting text |
| `-c-border` | `#dce6f2` | Fine dividers and outlines |
| `-c-bg` | `#f8fbff` | App background |
| `-c-surface` | `#ffffff` | Content and input surface |
| `-c-warn` / `-c-warn-soft` | `#b46908` / `#fff7e9` | Assumption and risk |
| `-c-success` / `-c-success-soft` | `#178442` / `#eaf7ef` | Completed/predeclared success |
| `-c-danger` / `-c-danger-soft` | `#cb3038` / `#fff0f1` | Error/predeclared failure |

The success/failure colors label criteria, not experimental outcomes.

The live debate Canvas uses the CSS graph token family `-c-graph-1` through
`-c-graph-8` for categorical lens identity. Its JavaFX styleable color properties
resolve those tokens; neutral arrows, hover accents and labels reuse `-c-muted`,
`-c-warn` and `-c-surface`. Graph color changes therefore follow the same stylesheet.

## 3. Typography

Use the Windows-installed Noto Sans KR family, with Malgun Gothic as the native
fallback. Body 15px, supporting copy 13px, field labels 14px, section headings
18px, product name 20px, core hypothesis 26px. Paragraphs use regular weight;
headings use bold. Monospace Consolas 13px is reserved for logs and raw exports.
All generated prose wraps and remains readable in a vertically scrolling view.
When a multiline question is quoted inside report prose, its separators render as
spaces. The full input, analysis data and Markdown/JSON exports keep the original
question. Intentional paragraph breaks in generated results are retained.

## 4. Spacing and native layout

Spacing scale: 4, 8, 12, 16, 20, 24, 32 logical pixels. Outer content inset 24;
panel gap 16; panel inset 20; evaluation inset 16. Header height about 64;
question input shell 48; primary action 48; workflow buttons about 88.

The native window uses a header and a fixed toolbar/workflow area above one
scrolling result surface. Target reference viewport: 1568 × 1003. At 1200px and
above, results use 62/38 columns and four evaluation columns. Below 1200px,
results stack and evaluation becomes two columns; toolbar uses two rows. The
minimum supported desktop size is 1000 × 700. Long generated content grows
vertically and never becomes a screenshot, an image backdrop or a fixed-height
clipped panel. The window is resizable and has working native-style controls. Graph hover details
are positioned within the visible report viewport and dismissed on resize; the
full critique cards remain available below the graph.

## 5. Reusable primitives and states

| Primitive | Structure and states | Accessibility |
|---|---|---|
| Question field | Actual TextArea with search icon, clear and expanded editor; empty, focus, long/multiline, busy | Named input; Ctrl+Enter runs; full text preserved |
| Segmented mode | Three native ToggleButtons; default, hover, focus, selected, disabled | Exactly one mode stays selected, including keyboard activation |
| Primary/secondary/icon action | Native Buttons/MenuButtons; hover, pressed, focus, disabled | Accessible labels; minimum 36px icon target |
| Workflow stage | Icon, title and caption; pending, active, done, stopped, viewed | Button navigates to its actual page; no fake completed status |
| Report section | Outline icon, title, optional helper/badge and live report text | All seven decision fields and critique remain available |
| Risk quotation | Light amber surface and left rule; real severity badge | Risk is conveyed with words and icons as well as color |
| Evaluation item | Icon/title/value; four or two columns | Criteria labeled as predeclared; no fabricated measurements |
| Settings dialog | Existing engine/model/download controls and optional domain/examples | Modal focus and close action; controls disabled while busy |
| Detail navigation | Native pages for lenses, critique/graph, experiment, logs, Markdown, JSON | Keyboard-accessible view menu and workflow controls |

Outline icons use the published Ikonli Material Design 2 pack. Product screens
are composed from these primitives; their actual JavaFX states are exercised in
the UI QA harness before final screenshot review.

## 6. Interaction

Keep the existing worker-thread analysis, cooperative cancellation, connection
probe, model download, report formatting and file save behavior. Settings are
available from the header. Workflow progress follows the orchestrator, and
workflow buttons navigate to detailed views. Summary is the default finished
view. No decorative animation is added. Focus and progress provide immediate
state feedback. Only one run can be active; cancel and logs remain accessible.

## 7. Depth and surfaces

Reference-matched white panels with 1px light borders and 8px corners. No heavy
shadows or large purple gradient. Small blue/amber tints communicate selection
and risk; the remaining content relies on spacing, alignment and typography.
Cards are used for distinct report regions rather than every text row.

## 8. Accessibility and verification

Keyboard-reachable actions, meaningful accessible names, visible focus, readable
Korean text, and status expressed without color alone. Native minimum desktop
size is intentional; phone/browser breakpoints are outside this JavaFX app.
Verify at 1568×1003, 1280×860, and 1000×740, plus long Korean content. Exercise
empty input, all modes, settings/engine selection, mock run, repeat run, page
navigation, raw reports and error/cancel state wiring. Capture the actual rendered
JavaFX scene and inspect it against the selected reference. Real paid CLI/local
model inference is outside visual QA; their existing runtime code is preserved.

Sources: [Ikonli JavaFX documentation](https://kordamp.org/ikonli/).

Implementation checklist:
- [x] Inspect existing app/data/actions and run baseline tests (29; one optional model test skipped).
- [x] Record the reference geometry, palette, reusable primitives and native QA scope.
- [x] Implement shell, workflow navigation and live result layout.
- [x] Verify native primitives, functional scenarios, build/tests and screenshots.

Final verification: Maven package (28 executed unit/eval tests; two opt-in skips),
separate native UI scenarios, shaded-JAR Mock analysis, and direct `java -jar`
startup passed. Fresh source and visual reviewers both approved the same source
snapshot and all 29 captures. See `.omo/evidence/workflow-bench-qa-final.md`.
Paid CLI and real GGUF inference were not run as part of this visual change.
## 9. Report Reader + PDF upgrade (current implementation contract)

The user explicitly requests a much stronger finished report, better readability,
and PDF export. This evolves the former screenshot contract while preserving the
Scientific Lens Harness brand, real data and analysis workflow. The older design
remains the editing/progress shell; it is not an exact pixel target for the new reader.

### Brief and persona

An idea owner reads the final result, identifies the testable hypothesis, the next
experiment, the weakest assumption, and the decision rule, then shares a portable
PDF. A long-text reader and a 1000px-window user need comfortable line length and
reliable vertical scrolling. Evidence and critique take priority over ornament.

### Native visual direction

Editorial research workspace. Restrained ink, cool paper and a single blue brand
accent; warning/success/failure remain semantic. Keep reused native controls and
Material Design 2 outline icons. No invented metrics, avatars, results, validations,
fabricated elapsed times, raster UI backgrounds or fake controls. Science names
remain methodological lenses, never impersonations.

### Extended tokens and typography

Existing semantic colors remain the runtime palette. Additional named CSS tokens:
`-c-paper: #f3f6fa`, `-c-ink: #10243d`, `-c-subtle: #e9eff6`,
`-c-reading-muted: #50647c`, `-c-reader-blue: #235eaa`.
Bundle OFL Noto Sans KR Regular/Bold static TrueType fonts for both native reading
and embedded PDF output; Malgun Gothic remains the native fallback.
Native type scale: hero 30px, hypothesis 22px, section 20px, body 16px, helper 13px,
folio/eyebrow 12px. Use regular body weight and modest bold only for hierarchy.
Paragraph line spacing 4px, section gaps 24/32px, paragraph gaps 12px.
Max report width 1180px; reading prose roughly 60-70 characters per line.
Outer desktop inset 24px, section inset 24px, hero inset 28px; paper corners 16px,
inner quote/fact corners 8px, native controls keep their existing radii.
Use tonal surface separation and fine rules; one very subtle cool shadow is allowed
for the report hero only. No decorative animation; existing real state feedback stays.

### Report grammar and primitives

1. Report masthead: real question and analysis metadata, clear unvalidated status.
2. Dominant hypothesis with an actual next-decision companion; no proof claims.
3. First experiment: full prose and readable actual design facts.
4. Judgment rules: data as a list, success/failure as predeclared criteria.
5. Assumption and critique: real severity, issue and mitigation; all details reachable.
6. Market strategy and provenance remain available; methodological disclaimer present.

Shared primitives: numbered report section/eyebrow, paragraph/bullet group,
readable fact rows, semantic callout, outcome/decision block, disclosure for details.
All variable result text grows vertically. Do not truncate generated content.
Long hypotheses use the hypothesis size, not giant hero typography. Do not force
four narrow equal-height text columns. At narrow desktop widths, sections stack.
Detailed lens/critique/experiment pages must use the same readable body typography
and preserve every value. Markdown/JSON remain unchanged. Only balanced display
formatting may be cleaned; never delete mathematical or literal source content.

### Reader interactions and accessibility

Add a native focused-reading toggle that hides the editing/workflow region while
keeping settings, export, detail navigation and analysis-settings restoration
reachable. Restore the editing/progress region when a new analysis begins.
Finished results may enter focused reading automatically if the return path is
obvious. Provide keyboard-visible focus; retain native accessible names, correct
busy/disabled/export states and working cancellation/retry. No browser/React/
Lighthouse claim applies to this JavaFX product. Exercise real native scenes at
1568x1003, 1280x860 and 1000x740 plus long Korean report content.

### PDF output contract

Native Java export with Apache PDFBox 3.0.8, no Python/browser/remote service at
runtime. Embed the bundled Korean font subsets; readable text remains selectable
and searchable. A4 pages, 48pt margins, body 11.5pt, heading 18pt, title 28pt,
leading 18pt. Ink/paper/blue palette mirrors the reader. Add page numbers,
continuation headings and bookmarks. Keep headings with following text; wrap long
CJK/English text and preserve all content over pages. Include question/provenance,
all seven final decision fields and market strategy, full experiment design,
selected lens profiles/output fields, cross-lens debate and red-team findings.
A label such as 분석 완료 describes generation only; the hypothesis is unverified.
No claim that a study has actually run. Export after completion only; use a real
FileChooser, background generation, clear busy/success/failure state, .pdf extension,
and valid standard PDF. Cancelled/failed/in-progress results cannot export stale data.

### Verification and accepted debt

Native app, full Mock analysis, export action and real PDF writer are driven and
captured. PDF extraction checks every field and embedded fonts; render EVERY page
of representative and long Korean samples and inspect for overlap/tofu/orphans.
Two independent fresh reviewers cover source/behavior and screen/PDF craft.
No public-model inference is needed for this formatting/export task. No functional,
readability, accessibility or content-loss blocker may be deferred.
## 10. Real research council transcript

The reader retains section9's tokens/type/spacing/native controls. On real CLI/local LLM runs, the coordinator LLM selects agent assignments and debate pairs. The debate tab adds a native disclosure titled '에이전트 호출·토론 기록' using existing readable report fields. Entries show sequence, actual actor/recipient, action and full public statement; the transcript grows/scrolls without truncation. Actual critic and target replies are separate entries. No hidden reasoning is displayed or requested. The same full transcript is preserved in Markdown/JSON/PDF; PDFs add a bookmarked ninth section only when actual events exist. Legacy Mock keeps its honest test label and has no fabricated orchestration events. Planning/progress uses actual selected agents and reports manager/debate status; cancel/error never becomes a completed report. All7existingtabs/export formats and the8finaldecisionfields remain available.

## 11. Research library and readable execution

Preserve the existing paper/ink/blue palette, Noto Sans KR type scale,16px body,24px sections and1000px minimum desktop. New header `연구 보관함` action has a native icon and remains visible in focused reading. A native owned dialog uses a searchable report list and vertically growing detail/editor panel; at narrow widths they stack. Entries show actual title, save time, model/mode and generated/imported origin, with no decorative metrics. Selected report actions are clear and keyboard reachable. Empty/loading/corrupt/permission-error states explain recovery without hiding valid records. Hiding moves a record reversibly; a toggle exposes hidden records and restore. Notes clearly stay local and never enter AI prompts or default AI exports. Async library I/O has visible status and cannot block model execution or replace a newer report through a stale callback.

Actual debate cards put the target's real answer immediately below the critique. A labeled `실제 수정 전후 비교` disclosure shows every changed field, before and after, in reading columns that stack below1000px content width. Fields are compared from typed objects; no artificial acceptance label or legacy reconstruction. Unchanged replies explicitly say no analysis field changed. Full transcript remains available. PDF/Markdown include the same actual response and changed values without truncation.

The live-log tab uses a readable activity list with actor/recipient/action headers, expandable full public statements, real current status and monotonic elapsed time. Auto-follow can be turned off to inspect prior entries. A native raw-output toggle exposes the retained diagnostic TextArea; saved reports disclose that no original CLI stream was retained. Timer changes provide liveness, not a fabricated estimate. No decorative animation; existing focus/hover/disabled tokens apply.

Required QA: all7tabs at1568x1003,1280x860,1000x740; library empty/search/no-match/import/open/edit/hide/restore/corrupt/write-error/restart persistence; genuine revision before/after and legacy missing-revision state; live/saved/raw/auto-follow/cancel transitions; full-scroll long CJK notes/reports; actual async exports and every PDF page. Native OS chooser remains source-verified when OS automation is unavailable. Two independent fresh source-and-surface reviews bind to the final complete source/JAR hashes. No product/readability/content-loss blocker deferred.

## 12. Welcome and interactive council — 2026-10-15 contract

This request explicitly evolves the static workbench into a welcoming interactive
research product. The supplied science folder contains the Python backend and
design references; no standalone web source was found there. The old
workflow screenshot is a brand/layout input, not a pixel target for these new states.
Native JavaFX remains the product surface; React, Lighthouse and browser viewport
requirements are inapplicable. Existing report/PDF/library contracts remain intact.

### Journey, people and visual diagnosis

An idea owner first needs to understand the product and enter a question. During
long model calls they need to know who is working, what the agents exchanged, and
whether they can inspect or stop it. The prior empty result tabs, repetitive cards,
and raw-log-first loading make the service feel inert. A narrow-window reader and
a keyboard user must have the same complete path. Welcome → question → actual
council → readable result is the new hierarchy; home, settings, library and return
to an existing report stay reachable. Do not clear a draft merely by navigating.

### Material and tokens

Use the current paper/ink/blue palette, layered blue light on pale paper, a fine
technical dot grid, and floating native lens controls with restrained cool shadows.
Signature object: a native interactive methodological constellation, not scientist
portraits or raster UI. Existing graph identity colors remain categorical.
New CSS tokens: `-c-stage:#edf4fd`, `-c-stage-light:#f8fbff`,
`-c-orbit:#c7daf4`, `-c-signal:#448bed`, `-c-stage-ink:#16365f`.
New type sizes: welcome display44 (36 below1200), intro18, composer20,
lens monogram20; supporting14/13/12 retain existing Noto Sans KR.
Spacing scale extends to40/48/64; welcome max width1280, content inset32
(24 below1200), hero/composer radius24, native choice radius12, lens node radius16.
Hero object has layered radial light, thin concentric orbital rings, low-opacity
link lines, labeled methodological nodes, and a clearly labeled central coordinator.
Do not invent performance metrics, discussions, historical thoughts or agent work.

### Reusable primitives, states and real data

- WelcomeView: responsive hero, question composer bidirectionally synchronized with
  the existing question, real mode selection, start/return/library actions, example
  question buttons that insert text without triggering paid inference.
- CouncilStageView: real native interactive node buttons, bounded orbital layout,
  actual selected IDs, actual active route, completed analysis count from accepted
  events, full selected-node public record and role details. Planning has no
  selected/finished agents before onLensesSelected. Mock explicitly says template
  test and never fabricates agent events. Saved display explicitly says saved record.
- Activity surface: graphical council above the retained full event list/raw-output
  controls; scroll ownership remains explicit. Every actual event remains readable.
- Home navigation: returns to welcome without losing draft/result; disabled while
  analysis/export is busy. Return to report works without rerunning the model.
- Motion control: user-facing `움직임 줄이기` toggle, applied to all new motion.

### Interaction mechanisms

Consulted beui.dev `animated-badge` and `action-swap` actual registry sources on
2026-10-15: loading-only opacity pulse, short state crossfade, immediate reduced
motion swap. Adapt using existing JavaFX FadeTransition/Timeline, no new library.
Named durations: state160ms, entrance240ms, active pulse1600ms; opacity only on
active work signal, never arbitrary idle simulation. Route transfer700ms is a
one-shot visual acknowledgement of an actual public event, not a request progress
estimate. Stop all live pulses on finish/cancel/error/replay/hide/dispose; reduced
motion immediately freezes at readable settled state. Animated content never
blocks interaction or token updates. Full content never disappears during a fade.

### Native verification and debt

Enumerate welcome empty/typed/examples/return, planning, selected analysis, actual
challenge/reply, record inspection, full transcript/raw toggle, completion, cancel,
error, Mock and saved states at1568x1003,1280x860,1000x740. Drive native buttons,
keyboard-equivalent actions and reduced-motion toggle. Capture rest/mid/settled
state frames and inspect CJK wrapping, all7tabs and reachable export/library.
All new UI is live JavaFX nodes, CSS tokens and scalable scene-graph geometry.
No known functional/accessibility blocker may be silently deferred. Screenshots
and behavior receipts must bind to the final built source; two fresh independent
reviews cover implementation and visual/CJK quality.

Section12 integration detail: the council and full transcript/raw surfaces are
selectable views of the same ActivityView. `전체 기록` opens the retained complete
record list; `토론 무대로` restores the graph. The stage fills the center, rather
than squeezing a graph above a second scroll area. Inspector width260–380
(preferred340), graph/detail min body260, coordinator120x72, normal nodes124x60,
dense(>9)88x44 on two orbits, role chips100x34,24px dot grid. During analysis the
editing/workflow controls fold away; the result tabs and cancel remain reachable.
Welcome hides the inactive workbench status controls; header navigation remains.
Compact dense council: below330px graph body, lens buttons76x36 and coordinator84x48,
11 outer/four inner nodes. This preserves selection and detail access for all15
methods at the1000x740 minimum window; full names/roles are available in the inspector.
The council has a native vertical scroll owner that fills the available height.
When cancellation/error restores editing controls in a small window, the stage
retains legible minimum geometry and its entire footer and inspector remain
reachable by scrolling. Full transcript and raw output keep their own scroll owners.
