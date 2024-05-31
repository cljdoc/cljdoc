export {};

interface HighlightJSPlugin {
  // what we are using, add more if we ever use more
  'before:highlightElement'?: (args: { el: HTMLElement }) => void;
  'after:highlightElement'?: (args: { el: HTMLElement; result: { value: string }; text: string }) => void;
}

declare global {
  interface Window {
    mergeHTMLPlugin: HighlightJSPlugin;
    copyButtonPlugin: HighlightJSPlugin;
  }
}
