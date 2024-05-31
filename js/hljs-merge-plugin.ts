// highlight.js 11 removed the 'HTML auto-merging' internal plugin
// however this is required for AsciiDoc to insert callouts
// fortunately the issue description shows how to re-enable it, by registering the plugin
// See https://github.com/highlightjs/highlight.js/issues/2889

// Naively transcribed to TypeScript
let originalStream: Event[];

/**
 * @param value
 * @returns escaped html
 */
function escapeHTML(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#x27;");
}

const mergeHTMLPlugin = {
  // preserve the original HTML token stream
  "before:highlightElement": ({ el }: { el: HTMLElement }) => {
    originalStream = nodeStream(el);
  },
  // merge it afterwards with the highlighted token stream
  "after:highlightElement": ({
    el,
    result,
    text
  }: {
    el: HTMLElement;
    result: Result;
    text: string;
  }) => {
    if (!originalStream.length) return;

    const resultNode = document.createElement("div");
    resultNode.innerHTML = result.value;
    result.value = mergeStreams(originalStream, nodeStream(resultNode), text);
    el.innerHTML = result.value;
  }
};

/* Stream merging support functions */

type Result = {
  value: string;
};

type Event = {
  event: "start" | "stop";
  offset: number;
  node: HTMLElement;
};

/**
 * @param {HTMLElement} node
 */
function tag(node: HTMLElement): string {
  return node.nodeName.toLowerCase();
}

/**
 * @param node
 * returns events
 */
function nodeStream(node: HTMLElement): Event[] {
  const result: Event[] = [];
  (function _nodeStream(node: HTMLElement, offset: number): number {
    for (let child = node.firstChild; child; child = child.nextSibling) {
      if (child.nodeType === Node.TEXT_NODE) {
        if (child.nodeValue) {
          offset += child.nodeValue.length;
        }
      } else if (child.nodeType === Node.ELEMENT_NODE) {
        result.push({
          event: "start",
          offset: offset,
          node: child as HTMLElement
        });
        offset = _nodeStream(child as HTMLElement, offset);
        // Prevent void elements from having an end tag that would actually
        // double them in the output. There are more void elements in HTML
        // but we list only those realistically expected in code display.
        if (!tag(child as HTMLElement).match(/br|hr|img|input/)) {
          result.push({
            event: "stop",
            offset: offset,
            node: child as HTMLElement
          });
        }
      }
    }
    return offset;
  })(node, 0);
  return result;
}

/**
 * @param original - the original stream
 * @param highlighted - stream of the highlighted source
 * @param value - the original source itself
 */
function mergeStreams(original: Event[], highlighted: Event[], value: string): string {
  let processed = 0;
  let result = "";
  const nodeStack: HTMLElement[] = [];

  function selectStream(): Event[] {
    if (!original.length || !highlighted.length) {
      return original.length ? original : highlighted;
    }
    if (original[0].offset !== highlighted[0].offset) {
      return original[0].offset < highlighted[0].offset
        ? original
        : highlighted;
    }

    /*
        To avoid starting the stream just before it should stop the order is
        ensured that original always starts first and closes last:
        if (event1 == 'start' && event2 == 'start')
          return original;
        if (event1 == 'start' && event2 == 'stop')
          return highlighted;
        if (event1 == 'stop' && event2 == 'start')
          return original;
        if (event1 == 'stop' && event2 == 'stop')
          return highlighted;
         ... which is collapsed to:
      */
    return highlighted[0].event === "start" ? original : highlighted;
  }

  /**
   * @param node
   */
  function open(node: HTMLElement): void {
    /** @param attr */
    function attributeString(attr: Attr): string {
      return " " + attr.nodeName + '="' + escapeHTML(attr.value) + '"';
    }
    result +=
      "<" +
      tag(node) +
      [].map.call(node.attributes, attributeString).join("") +
      ">";
  }

  /**
   * @param node
   */
  function close(node: HTMLElement): void {
    result += "</" + tag(node) + ">";
  }

  /**
   * @param event
   */
  function render(event: Event): void {
    (event.event === "start" ? open : close)(event.node);
  }

  while (original.length || highlighted.length) {
    let stream = selectStream();
    result += escapeHTML(value.substring(processed, stream[0].offset));
    processed = stream[0].offset;
    if (stream === original) {
      /* On any opening or closing tag of the original markup we first close
           the entire highlighted node stack, then render the original tag along
           with all the following original tags at the same offset and then
           reopen all the tags on the highlighted stack. */
      nodeStack.reverse().forEach(close);
      do {
        render(stream.splice(0, 1)[0]);
        stream = selectStream();
      } while (
        stream === original &&
        stream.length &&
        stream[0].offset === processed
      );
      nodeStack.reverse().forEach(open);
    } else {
      if (stream[0].event === "start") {
        nodeStack.push(stream[0].node);
      } else {
        nodeStack.pop();
      }
      render(stream.splice(0, 1)[0]);
    }
  }
  return result + escapeHTML(value.substring(processed));
}

export { mergeHTMLPlugin };
