// A small component to navigate users to documentation pages based on clojars ID and version inputs

import { Component } from "preact";

class Navigator extends Component {
  clojarsIdInput: HTMLFormElement;
  versionInput: HTMLFormElement;

  constructor() {
    super();
    this.navigate = this.navigate.bind(this);
  }

  navigate() {
    let clojarsId = this.clojarsIdInput.value;
    let version = this.versionInput.value;

    if (0 != clojarsId.length) {
      if (clojarsId.includes("/")) {
        window.location.href = "/d/" + clojarsId + "/" + version;
      } else {
        window.location.href =
          "/d/" + clojarsId + "/" + clojarsId + "/" + version;
      }
    }
  }

  render(_props: any, _state: any) {
    return (
      <div>
        <div class="cf nl2 nr2">
          <fieldset class="fl w-50-ns pa2 bn mh0">
            <label class="b db mb3">
              Group ID / Artifact ID
              <span class="normal ml2 gray f6">may be identical</span>
            </label>
            <input
              class="w-90 pa2 b--blue br2 ba no-outline"
              autocorrect="off"
              autocapitalize="none"
              onKeyUp={(e: KeyboardEvent) =>
                e.key == "Enter" ? this.navigate() : null
              }
              ref={(node: HTMLFormElement) => (this.clojarsIdInput = node)}
              placeholder="e.g. 're-frame' or 'ring/ring-core'"
            />
          </fieldset>
          <fieldset class="fl w-50-ns pa2 bn mh0">
            <label class="b db mb3">
              Version
              <span class="normal ml2 gray f6">optional</span>
            </label>
            <input
              class="w-90 pa2 b--blue br2 ba no-outline"
              onKeyUp={(e: KeyboardEvent) =>
                e.key == "Enter" ? this.navigate() : null
              }
              ref={(node: HTMLFormElement) => (this.versionInput = node)}
              placeholder="e.g. '1.0.2'"
            />
          </fieldset>
        </div>
        <input
          class="bg-blue white bn pv2 ph3 br2"
          type="button"
          onClick={this.navigate}
          value="Go to Documentation"
        />
      </div>
    );
  }
}

export { Navigator };
