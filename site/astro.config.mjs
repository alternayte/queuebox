// @ts-check
import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";
import starlightLlmsTxt from "starlight-llms-txt";
import { readdirSync, readFileSync } from "node:fs";
import path from "node:path";

// Cloudflare Web Analytics is cookie-free; it is on only when the deploy passes a beacon token.
const beacon = process.env.PUBLIC_CF_BEACON_TOKEN;
const site = process.env.DOCS_SITE ?? "https://queuebox-docs.pages.dev";

const sections = [
  ["tutorials", "Tutorials"],
  ["how-to", "How-to guides"],
  ["concepts", "Concepts"],
  ["reference", "Reference"],
  ["operations", "Operations"],
];

// llms.txt lists every page with the URL of its Markdown copy, section by section.
function pageIndex() {
  const root = "src/content/docs";
  const field = (src, key) =>
    src.match(new RegExp(`^${key}:\\s*(.+)$`, "m"))?.[1].trim().replace(/^["']|["']$/g, "") ?? "";
  const out = ["## Pages", "", "Each page is also Markdown at its URL plus `.md`."];
  for (const [dir, label] of sections) {
    const files = readdirSync(path.join(root, dir)).filter((f) => /\.mdx?$/.test(f)).sort();
    out.push("", `### ${label}`, "");
    for (const f of files) {
      const src = readFileSync(path.join(root, dir, f), "utf8");
      const slug = `${dir}/${f.replace(/\.mdx?$/, "")}`;
      out.push(`- [${field(src, "title")}](${site}/${slug}.md): ${field(src, "description")}`);
    }
  }
  return out.join("\n");
}

export default defineConfig({
  site,
  integrations: [
    starlight({
      title: "QueueBox",
      description:
        "A transactional outbox and an idempotent inbox as one service, on Postgres or SQL Server. Delivers to RabbitMQ, Kafka, NATS and HTTP.",
      social: [{ icon: "github", label: "GitHub", href: "https://github.com/alternayte/queuebox" }],
      editLink: { baseUrl: "https://github.com/alternayte/queuebox/edit/main/site/" },
      lastUpdated: false,
      components: { PageTitle: "./src/components/PageTitle.astro" },
      head: beacon
        ? [
            {
              tag: "script",
              attrs: {
                defer: true,
                src: "https://static.cloudflareinsights.com/beacon.min.js",
                "data-cf-beacon": JSON.stringify({ token: beacon }),
              },
            },
          ]
        : [],
      sidebar: sections.map(([dir, label]) => ({ label, items: [{ autogenerate: { directory: dir } }] })),
      plugins: [
        starlightLlmsTxt({
          projectName: "QueueBox",
          details: pageIndex(),
          description:
            "QueueBox is a self-hosted service that runs the transactional outbox and the idempotent inbox on Postgres or SQL Server. Applications write outbox rows in their own transaction; QueueBox delivers them to RabbitMQ, Kafka, NATS or HTTP. Brokers and webhooks feed the inbox, which deduplicates and hands messages to push relays or pull clients in Go, TypeScript and C#.",
        }),
      ],
    }),
  ],
});
