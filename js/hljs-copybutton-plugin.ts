// A plugin to add a copy button to code blocks
// There is https://github.com/arronhunt/highlightjs-copy but it seems to have some
// unresolved issues. There is not much code to this, so let's just do a bare bones ourselves.

const copyButtonPlugin = {
  "before:highlightElement": ({
    el
  }: {
    el: HTMLElement;
  }) => {
    const button: HTMLButtonElement = Object.assign(
      document.createElement("button"),
      {
        innerHTML: "copy",
        className: "hljs-copy-button"
      }
    );
    button.dataset.copied = "false";
    el.parentElement?.classList.add("hljs-copy-wrapper");
    el.parentElement?.appendChild(button);
    button.onclick = () => {
      if (navigator.clipboard) {
        navigator.clipboard.writeText(el.innerText).then(() => {
          button.innerHTML = "copied";
          button.dataset.copied = "true";
        });
        setTimeout(() => {
          button.innerHTML = "copy";
          button.dataset.copied = "false";
        }, 2000);
      }
    };
  }
};

export { copyButtonPlugin };
