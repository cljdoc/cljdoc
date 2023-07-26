export {};

declare global {
  interface Window {
    // allows us to expose our hljs plugins
    mergeHTMLPlugin: any;
    copyButtonPlugin: any;
  }
}
