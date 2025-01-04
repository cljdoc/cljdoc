import { defineConfig } from "tsup";

export default defineConfig({
  format: ["esm"],
  entry: ["./js/index.tsx"],
  publicDir: "./resources/public",
  outDir: "./resources-compiled/public/out",
  dts: false,
  shims: true,
  skipNodeModulesBundle: true,
  sourcemap: true,
  clean: true,
  target: "es2022",
  platform: "browser",
  minify: false,
  keepNames: true,
  bundle: true,
  // https://github.com/egoist/tsup/issues/619
  noExternal: [/(.*)/],
  splitting: false
});
