/* Bundles the editor into the webapp root -- the ONE static location this
   deployment actually serves, and NOT a classpath one.

   DispatcherServlet is mapped to *.rol only (application.properties), so
   Spring MVC's static-resource handling never sees an asset request and
   classpath:/static is dead here. The servlet-spec classpath location,
   WEB-INF/classes/META-INF/resources, is dead too: the app ships as a Boot
   executable WAR, where WEB-INF/classes is a NESTED classpath entry that
   Boot's StaticResourceJars scan cannot open, so nothing ever registers it
   as a web resource. Both were tried; both 404'd, and the browser suite is
   what said so -- a bundle in the wrong place builds, packages and ships,
   and then fails with one console line on the one page that needs it.

   The webapp root works in both shapes that matter: maven-war-plugin copies
   src/main/webapp into the WAR, and spring-boot:run (./roller dev) uses
   src/main/webapp as the document root directly. The file itself is a build
   output and is git-ignored; EditorBundlePomTest pins that. */
import { build } from 'esbuild';

await build({
  entryPoints: ['src/editor.js'],
  bundle: true,
  minify: true,
  format: 'iife',
  globalName: 'RollerEditor',
  target: ['es2020'],
  outfile: '../src/main/webapp/roller-ui/scripts/roller-editor.js',
  legalComments: 'none',
  logLevel: 'info'
});
