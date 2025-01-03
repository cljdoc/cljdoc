import { defineConfig } from "@rsbuild/core";
import { pluginPreact } from "@rsbuild/plugin-preact";

const inputResourcePath = "./resources/public";
const outputResourcePath = "./resources-compiled/public";

export default defineConfig({
  plugins: [pluginPreact()],
  root: ".",
  html: {
    template: `${inputResourcePath}/cljdoc.html`
  },
  source: {
    include: [/\.tsx?$/, /\.css$/],
    entry: {
      index: {
        import: "./js/index.tsx",
        filename: "cljdoc.[contenthash:8].js"
      }
    }
  },

  output: {
    distPath: { root: `${outputResourcePath}/out` },
    minify: true,
    emitAssets: true,
    target: "web",
    filenameHash: true,
    filename: {
      js: "[name].[contenthash:8].js",
      css: "[name].[contenthash:8].css"
    },
    copy: [
      {
        from: `${inputResourcePath}/**/*.svg`,
        to: "[name].[contenthash:8][ext]"
      },
      {
        from: `${inputResourcePath}/**/*.css`,
        to: "[name].[contenthash:8][ext]"
      },
      {
        from: `${inputResourcePath}/**/*.png`,
        to: "[name].[contenthash:8][ext]"
      },
      {
        from: `${inputResourcePath}/static/*`,
        to: "[name][ext]"
      }
    ]
  }
});
