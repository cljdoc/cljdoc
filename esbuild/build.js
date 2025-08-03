import * as esbuild from 'esbuild'
import path from 'path';
import process from 'process';
import {squintPlugin} from './squint-plugin.js';

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
      resolveExtensions: ['.cljs', '.js', '.json'],
      plugins: [squintPlugin]
    });
    console.log(await esbuild.analyzeMetafile(result.metafile))
  } catch (error) {
    console.error('❌ Build failed:', error);
    // TODO: don't want to exit when watching, comment out for now
    // process.exit(1);
  }
}

// Extract parameters from command line arguments
const [jsDir, jsEntryPoint, jsOutName, targetDir] = process.argv.slice(2);
if (!jsDir || !jsEntryPoint || !jsOutName || !targetDir) {
  console.error('Usage: node esbuild.js <jsDir> <jsEntryPoint> <jsOutName> <targetDir>');
  process.exit(1);
}

compileJs({ jsDir, jsEntryPoint, jsOutName, targetDir });
