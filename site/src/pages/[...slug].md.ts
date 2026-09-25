import type { APIRoute, GetStaticPaths } from "astro";
import { getCollection, type CollectionEntry } from "astro:content";

// Each docs page is also served as Markdown at <path>.md, for agents and the "Copy page" menu.
export const getStaticPaths: GetStaticPaths = async () => {
  const docs = await getCollection("docs");
  return docs
    .filter((entry) => entry.body !== undefined)
    .map((entry) => ({ params: { slug: entry.id === "" ? "index" : entry.id }, props: { entry } }));
};

export const GET: APIRoute = ({ props }) => {
  const entry = (props as { entry: CollectionEntry<"docs"> }).entry;
  // MDX imports are not Markdown; the rest of the body is.
  const body = (entry.body ?? "").replace(/^import .+;\s*$/gm, "").trim();
  const head = [`# ${entry.data.title}`, entry.data.description ? `\n> ${entry.data.description}` : ""].join("\n");
  return new Response(`${head}\n\n${body}\n`, { headers: { "Content-Type": "text/markdown; charset=utf-8" } });
};
