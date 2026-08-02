/**
 * Applies to every route, because adapter-static has to know the whole site up front.
 *
 * `trailingSlash: 'always'` is what makes each route emit `<route>/index.html` rather than
 * `<route>.html`. GitHub Pages serves the former directly; the latter costs a redirect on every
 * navigation and breaks relative asset resolution under a project-site base path.
 */

export const prerender = true;
export const trailingSlash = 'always';
