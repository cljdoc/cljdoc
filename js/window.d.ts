export {};

declare global {
  interface Window {
    mergeHTMLPlugin: any; // allows us to expose our hljs plugin
  }
}
