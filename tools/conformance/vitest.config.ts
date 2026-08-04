import { fileURLToPath } from 'node:url';

const repository = fileURLToPath(new URL('../..', import.meta.url));

export default {
  root: `${repository}/web`,
  resolve: {
    alias: { $lib: `${repository}/web/src/lib` }
  },
  server: {
    fs: { allow: [repository] }
  },
  test: {
    include: [`${repository}/tools/conformance/typescript/**/*.spec.ts`]
  }
};
