import { build } from 'esbuild';

await build({
  entryPoints: ['src/editor.js'],
  bundle: true,
  minify: true,
  format: 'iife',
  globalName: 'RollerEditor',
  target: ['es2020'],
  outfile: '../target/classes/static/roller-ui/scripts/roller-editor.js',
  legalComments: 'none',
  logLevel: 'info'
});
