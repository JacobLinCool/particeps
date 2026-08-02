/**
 * Tells a component when it has been scrolled into view, once.
 *
 * The action reports rather than writing to the DOM, so the state stays in the component and the
 * attribute stays in the template — which is also what keeps the scoped CSS that keys on it from
 * being pruned as unused. Nothing is reported at all where `IntersectionObserver` is missing or
 * during prerender, so the state never leaves its initial value and the section is simply
 * visible: an entrance that cannot run must not be able to hide anything.
 *
 * The observer disconnects on the first crossing, so scrolling back up to re-read a section does
 * not replay it.
 */
export function reveal(
  node: HTMLElement,
  onchange: (visible: boolean) => void
): { destroy(): void } {
  if (typeof IntersectionObserver === 'undefined') return { destroy() {} };

  onchange(false);
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        onchange(true);
        observer.disconnect();
      }
    },
    { rootMargin: '0px 0px -12% 0px' }
  );
  observer.observe(node);

  return {
    destroy() {
      observer.disconnect();
    }
  };
}
