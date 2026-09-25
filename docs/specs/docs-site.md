# Docs site

## What it does
The QueueBox user docs move to an Astro Starlight site in `site/`, hosted on Cloudflare Pages at `queuebox-docs.pages.dev`. The site has a splash landing page and Diátaxis sections: Tutorials, How-to guides, Concepts, Reference and Operations. Agents read it through llms.txt and a Markdown copy of each page. A skill in `skills/queuebox/SKILL.md` gives a coding agent the rules for application code that uses QueueBox, and it installs with `npx skills add` or as a Claude Code plugin.

## Decisions
- Stack: Astro Starlight with `starlight-llms-txt`, as in deedbox and sluice — one pattern across the projects.
- Host: the Cloudflare Pages project `queuebox-docs`. `DOCS_SITE` sets the site URL — a custom domain is a one-line change.
- The site is the only home of the user docs. `docs/` keeps only `specs/`, `build/`, `adr/`, `development/` and `superpowers/` — one source, so no page drifts from another.
- The guides get a rewrite into the Diátaxis sections, not a file-by-file copy — each page answers one kind of question.
- Tutorials: run QueueBox with Compose and deliver a first outbox row; receive a webhook into the inbox and consume it with a pull client.
- How-to guides cover each broker bridge, HTTP fan-out, CDC capture, transforms, authentication, custom tables and columns, dead letters and replay, upgrades, and "Use QueueBox with coding agents".
- Concepts cover the outbox, the inbox, the relay, delivery semantics and order, claims and leases, and capture.
- Reference covers configuration, the outbox and inbox columns, HTTP routes, metrics, headers and the three pull clients.
- Operations covers the runbook, security, metrics and alerts, and dead letters.
- The client READMEs stay in `clients/` — npm, NuGet and pkg.go.dev show them. The client reference pages link to them.
- README shrinks to the pitch, the guarantees, the quickstart and a link to the site. `GuaranteesTest` still reads the README.
- `IntegrationDocSqlTest`, `MetricsDocTest`, `ReadmeStructureTest` and `MigrationParityTest` read the site pages instead of `docs/` — the SQL, the metric names and the migration list on the site stay tested.
- A new doc test loads every `yaml` block marked as a QueueBox config through the real config loader and validator — a config sample that does not load fails `./gradlew check`.
- The site publishes llms.txt, llms-full.txt, llms-small.txt and a `.md` copy of each page. The llms.txt index lists every page with its Markdown URL, as in sluice.
- Vale lints the site with a copy of the deedbox style, renamed QueueBox.
- `.github/workflows/docs.yml` copies the deedbox workflow: Vale, site build, deploy. Main deploys to production. A pull request gets a preview alias and a comment with its URL. Nothing deploys until the repo has `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` — only the user can create the token.
- `skills/queuebox/SKILL.md` holds the application-side rules: outbox row columns, key order, headers, `scheduled_at`, `queuebox.yml` sources, destinations, routes and transforms, the pull clients, idempotent consumers, and a "Do not" list. It links to `llms-full.txt` — the skill stays short and the site holds the detail.
- `.claude-plugin/marketplace.json` and `.claude-plugin/plugin.json` make the repo a Claude Code plugin marketplace with one plugin, `queuebox`, that ships the skill — `/plugin marketplace add alternayte/queuebox` then `/plugin install queuebox`.
- The skill also installs with `npx skills add alternayte/queuebox` or by copying the directory — agents other than Claude Code get the same file.

## Out
- A custom domain.
- Versioned docs.
- A docs MCP server.
- A CLI command that writes the skill into a repo.
- Compiled snippets for the C#, Go and TypeScript samples.
- Screenshots. QueueBox has no UI.

## How I know it works
- `cd site && npm ci && npx astro build` writes `site/dist` with no error.
- `site/dist/llms.txt` lists every page. `site/dist/concepts/delivery-semantics.md` is Markdown.
- The landing page shows in light and dark themes in a browser.
- A config sample with an invalid value in a site page fails `./gradlew check`. The loader ignores an unknown key, so an unknown key cannot fail it.
- A wrong metric name in the metrics page fails `./gradlew check`.
- `vale site/src/content` passes.
- `npx skills add ./` in a scratch directory lists the `queuebox` skill.
- `claude plugin validate .` passes for the marketplace manifest.
- `docs/` holds no user guide, and every README link resolves.
- `./gradlew check detekt` passes.
