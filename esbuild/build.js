import * as esbuild from 'esbuild'
import path from 'path';
import process from 'process';
import {squintPlugin} from './squint-plugin.js';

// TODO: --minify
// TODO: define:

// esbuild.build({
//   entryPoints: ['js/index.tsx'],
//   target: ['es2017'],
//   bundle: true,
//   outdir: 'resources-compiled/public/out', // Can I pass this in somehow?
//   entryNames: '[name].[hash]',
//   plugins: [squintPlugin],
// }).catch(() => process.exit(1));


async function compileJs({ jsDir, jsEntryPoint, jsOutName, targetDir }) {
  try {
    const result = await esbuild.build({
      entryPoints: {
        [jsOutName]: path.join(jsDir, jsEntryPoint)
      },
      outdir: targetDir,
      target: 'es2017',
      minify: true,
      define: { lunr: 'window.lunr' },
      sourcemap: true,
      entryNames: '[name].[hash]',
      bundle: true,
      metafile: true,
      resolveExtensions: ['.ts', '.tsx', '.cljs', '.json'],
      plugins: [squintPlugin]
    });
    console.log(await esbuild.analyzeMetafile(result.metafile))
  } catch (error) {
    console.error('❌ Build failed:', error);
    process.exit(1);
  }
}

// Extract parameters from command line arguments
const [jsDir, jsEntryPoint, jsOutName, targetDir] = process.argv.slice(2);
if (!jsDir || !jsEntryPoint || !jsOutName || !targetDir) {
  console.error('Usage: node esbuild.js <jsDir> <jsEntryPoint> <jsOutName> <targetDir>');
  process.exit(1);
}

compileJs({ jsDir, jsEntryPoint, jsOutName, targetDir });
