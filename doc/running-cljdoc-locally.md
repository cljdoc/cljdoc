# Running cljdoc locally

Besides development purposes running cljdoc locally can be useful to
build documentation for projects without pushing any changes to
Clojars and your Git remote.

Here's a step by step instruction to run cljdoc locally and build docs
for `muuntaja` from a local Jar and Git repository:

1. Clone cljdoc

   ```sh
   git clone https://github.com/cljdoc/cljdoc.git
   cd cljdoc/
   ```

1. Start the cljdoc server

    ```sh
    ./script/cljdoc run
    ```

    > This will start the cljdoc server process, requiring you to open another shell for the following steps.

    Alternatively you can start a REPL with `clj`,
    then load the system, move into its namespace and start the server:

    ```clj
    (require '[cljdoc.server.system])
    (in-ns 'cljdoc.server.system)
    (require '[integrant.repl])
    (integrant.repl/set-prep! #(system-config (cfg/config)))
    (integrant.repl/go)
    ```

1. To build the Javascript files used by the cljdoc pages, run the following in a terminal:

    ```sh
    npm ci                 # reproducibly install packages into node_modules
    npm run format         # format JS code with Prettier
    npm run lint-format    # check if JS code is properly formatted (used in CI)
    npm run build          # production build
    npm run dev            # watch mode
    ```

    > Note: You only need to run `build` **or** `dev`. Usually you would only use `dev` if you plan on working on JS files.

1. Run tests using [Kaocha](https://github.com/lambdaisland/kaocha):

    ```
    clj -A:test
    ```

# Importing a Project from Local Sources

1. Clone `muuntaja` to a directory of your liking and install it into your local Maven repo:

    ```sh
    git clone --branch 0.6.1 https://github.com/metosin/muuntaja.git
    cd muuntaja/
    scripts/lein-modules install
    ```

    > **Tip:** You can checkout the specific release version instead of 0.6.1.
    > When you do so, remember to change the version number in the command in the
    > next step.

1. Ingest all relevant information into the cljdoc system:

    ```sh
    cd cljdoc/
    ./script/cljdoc ingest -p metosin/muuntaja -v 0.6.1
    ```

    Or, if you want to specify the jar, pom, git repo and revision explicitly:

    ```sh
    cd cljdoc/
    ./script/cljdoc ingest -p metosin/muuntaja \
                           -v 0.6.1 \
                           --jar ~/.m2/repository/metosin/muuntaja/0.6.1/muuntaja-0.6.1.jar \
                           --pom ~/.m2/repository/metosin/muuntaja/0.6.1/muuntaja-0.6.1.pom \
                           --git /path/to/muuntaja/repo \
                           --rev "master"
    ```

    > **Tip:** See `./script/cljdoc ingest --help` for details.

    > **Note:** For Git-based changes to take effect you need to
    > commit them so there is a revision that the repo can be analysed
    > at. This can be done in a branch of course.

1. Open the docs for muuntaja on the local cljdoc server: http://localhost:8000/d/metosin/muuntaja

---

**Thats pretty much it!** Stop by on Slack if you have any problems. :wave:
