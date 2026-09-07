import { defineConfig } from "tsup";

// The package ships ESM and CJS, so it runs on Node, Bun and Deno. The constraint is the
// database driver, not the runtime, so one package serves all three.
export default defineConfig({
  entry: ["src/index.ts"],
  format: ["esm", "cjs"],
  dts: true,
  sourcemap: true,
  clean: true,
  target: "node22",
});
