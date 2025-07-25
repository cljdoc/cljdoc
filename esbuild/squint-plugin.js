import {compileString} from  'squint-cljs'
import fs from 'node:fs';

export const squintPlugin = {
  name: 'squint-compile',
  setup(build) {
    build.onLoad({ filter: /\.cljs$/ }, async (args) => {
      let source = await fs.promises.readFile(args.path, 'utf8')
      try {
        const js = compileString(source);
        return {
          contents: js,
          loader: 'jsx',
        };
      } catch (error) {
        return { errors: [{ text: error.message }] };
      }
    });
  },
};
