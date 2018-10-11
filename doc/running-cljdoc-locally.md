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

1. Install utility libraries into local repository

    ```sh
    ./script/build-libs.sh
    ```

    > **Note:** This will build and install two jars into your local `~/.m2` (Maven repo). Previously
    > this was handled using `:local/root` dependencies as supported by tools.deps but these caused
    > some problems when running cljdoc via Cursive. While they are now technically separate artifacts
    > you should still be able to easily develop (i.e. redefine) the code of those artifacts in a REPL.
    >  Related tickets: [TDEPS-74](https://dev.clojure.org/jira/browse/TDEPS-74) & [Cursive #2065](https://github.com/cursive-ide/cursive/issues/2065)

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

1. Clone `muuntaja` to a direcory of your liking and install it into your local Maven repo:

    ```sh
    git clone --branch 0.6.1 https://github.com/metosin/muuntaja.git
    cd muuntaja/
    scripts/lein-modules install
    ```

    > **Tip:** You can checkout the specific release version instead of 0.6.1.
    > When you do so, remember to change the version number in the commend in the
    > next step.

1. Ingest all relevant information into the cljdoc system:

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
