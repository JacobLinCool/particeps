import adapter from '@sveltejs/adapter-static';
import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

// GitHub Pages serves a project site under /<repo>/, so the base path has to be baked in at build
// time. BASE_PATH is set by the Pages workflow and left empty for local development.
const base = process.env.BASE_PATH ?? '';

/** @type {import('@sveltejs/kit').Config} */
export default {
  preprocess: vitePreprocess(),
  kit: {
    adapter: adapter({ fallback: '404.html', strict: true }),
    paths: { base, relative: false },
    prerender: { handleHttpError: 'fail' }
  }
};
